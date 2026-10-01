package com.bydmate.app.data.telegram

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.trips.TripCounterResets
import com.bydmate.app.util.AppStrings
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * The native location under a report (Bot API `sendLocation`), against a local Bot API: what goes
 * out, in which order, and what a failure of either request does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReporterLocationTest {

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override suspend fun set(setting: SettingEntity) { map[setting.key] = setting.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var settings: SettingsRepository
    private lateinit var reporter: TelegramReporter

    /** What the local Bot API answers, per method; an empty queue answers ok. */
    private val messageAnswers = ArrayDeque<MockResponse>()
    private val pointAnswers = ArrayDeque<MockResponse>()

    private val chat = 555L
    private val minsk = 53.9 to 27.56

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@TelegramReporterLocationTest) {
                val queue = if (request.path.orEmpty().endsWith("/sendLocation")) pointAnswers else messageAnswers
                queue.removeFirstOrNull() ?: ok()
            }
        }
        server.start()
        LocalePreferences(ctx).setLanguage("ru")
        settings = SettingsRepository(FakeSettingsDao(), LocalePreferences(ctx))
        val sink = TelegramBackupSink(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        reporter = TelegramReporter(
            ctx, sink, settings, mockk(relaxed = true), TripCounterResets(settings), AppStrings(ctx), PowerOffArmState(mockk()),
        )
        place(minsk)
        reporter.language = { "ru" }
        runBlocking { settings.saveTgBackup("tok", "bot", chat, "Андрей") }
        ShadowLog.clear()
    }

    @After fun tearDown() { server.shutdown() }

    private fun place(at: Pair<Double, Double>?) {
        reporter.inputs = { ReportInputs(diParsData(soc = 64), 312.0, at?.first, at?.second, null, null) }
    }

    private fun ok() = MockResponse().setBody("""{"ok":true,"result":{}}""")

    private fun refused(code: Int) =
        MockResponse().setResponseCode(code).setBody("""{"ok":false,"error_code":$code,"description":"Bad Request"}""")

    /** Every request the local Bot API got, in order. */
    private fun calls(): List<RecordedRequest> = (0 until server.requestCount).map { server.takeRequest(1, TimeUnit.SECONDS)!! }

    private fun method(request: RecordedRequest): String = request.path.orEmpty().substringAfterLast('/')

    private fun form(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private suspend fun report(fields: Set<ReportField> = setOf(ReportField.SOC, ReportField.LOCATION)) =
        reporter.sendRuleReport("Где машина", fields, "")

    @Test fun `a report with the place is followed by a silent native point with the same coordinates`() = runBlocking {
        assertEquals(TelegramReporter.SendResult.Sent, report())
        val (message, point) = calls()
        assertEquals("/bottok/sendMessage", message.path)
        val link = form(message)["text"]!!.lines().last()
        assertTrue(link, link.startsWith("📍 <a href=\"https://yandex.ru/maps/?pt=27.560000,53.900000&amp;z=16&amp;l=map\">"))
        assertEquals("/bottok/sendLocation", point.path)
        assertEquals(
            mapOf("chat_id" to "555", "latitude" to "53.900000", "longitude" to "27.560000", "disable_notification" to "true"),
            form(point),
        )
    }

    @Test fun `the point matches a Google link too, west and south included`() = runBlocking {
        reporter.language = { "pt" }
        place(-22.906847 to -43.172897)
        assertEquals(TelegramReporter.SendResult.Sent, report())
        val (message, point) = calls()
        assertTrue(form(message)["text"]!!.contains("query=-22.906847,-43.172897"))
        val fields = form(point) // the body reads once
        assertEquals("-22.906847", fields["latitude"])
        assertEquals("-43.172897", fields["longitude"])
    }

    @Test fun `no coordinates or no place in the report sends no point, the place in it does`() = runBlocking {
        place(null)
        assertEquals(TelegramReporter.SendResult.Sent, report())
        place(0.0 to 0.0)
        assertEquals(TelegramReporter.SendResult.Sent, report())
        place(minsk)
        assertEquals(TelegramReporter.SendResult.Sent, report(setOf(ReportField.SOC)))
        assertEquals(TelegramReporter.SendResult.Sent, report())
        assertEquals(listOf("sendMessage", "sendMessage", "sendMessage", "sendMessage", "sendLocation"), calls().map(::method))
    }

    @Test fun `a report that did not go out sends no point, the queued one gets it once it goes out`() = runBlocking {
        messageAnswers += MockResponse().setResponseCode(502)
        messageAnswers += refused(403)
        assertEquals(TelegramReporter.SendResult.Queued, report())
        assertEquals(TelegramReporter.SendResult.Failed("HTTP:403"), report())
        assertEquals(1, reporter.outboxSize())
        reporter.drainOutbox("network")
        assertEquals(listOf("sendMessage", "sendMessage", "sendMessage", "sendLocation"), calls().map(::method))
        assertEquals(0, reporter.outboxSize())
    }

    @Test fun `a point that fails leaves the report delivered, one attempt, one log line without the place`() = runBlocking {
        pointAnswers += refused(400)
        pointAnswers += MockResponse().setResponseCode(502) // busy: worth a retry for a report, not for a point
        assertEquals(TelegramReporter.SendResult.Sent, report())
        assertEquals(TelegramReporter.SendResult.Sent, report())
        reporter.drainOutbox("network")
        assertEquals(listOf("sendMessage", "sendLocation", "sendMessage", "sendLocation"), calls().map(::method))
        assertEquals(0, reporter.outboxSize())
        val lines = ShadowLog.getLogsForTag("TgReport").filter { it.msg.startsWith("location ") }
        assertEquals(lines.map { it.msg }.toString(), 2, lines.size)
        assertTrue(lines[0].msg, lines[0].msg.matches(Regex("location id=\\w+ rc=HTTP:400")))
        assertTrue(lines[1].msg, lines[1].msg.matches(Regex("location id=\\w+ rc=HTTP:502")))
        assertTrue(lines.all { it.type == Log.WARN })
    }

    @Test fun `a point cut short never brings its report back from the outbox`() = runBlocking {
        messageAnswers += MockResponse().setResponseCode(502)
        assertEquals(TelegramReporter.SendResult.Queued, report())
        pointAnswers += MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        val drain = launch(Dispatchers.IO) { reporter.drainOutbox("network") }
        val seen = (1..3).map { method(server.takeRequest(5, TimeUnit.SECONDS)!!) }
        assertEquals(listOf("sendMessage", "sendMessage", "sendLocation"), seen)
        drain.cancelAndJoin() // the app is stopped while the point hangs
        assertEquals(0, reporter.outboxSize())
    }

    @Test fun `the log names the point's outcome and never the coordinates or the link`() = runBlocking {
        report()
        val logs = ShadowLog.getLogs().map { it.msg }
        assertTrue(logs.toString(), logs.any { it.matches(Regex("location id=\\w+ rc=ok")) })
        for (secret in listOf("53.9", "27.56", "yandex", "google", "maps")) {
            assertFalse("$secret in $logs", logs.any { it.contains(secret) })
        }
    }
}
