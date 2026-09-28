package com.bydmate.app.helper.offreport

import com.bydmate.app.data.telegram.TelegramReportBuilder
import com.bydmate.app.helper.push.FID_PUSH_OK
import com.bydmate.app.helper.push.FidPushSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The native location the daemon sends under a power-off report Telegram took, with the harness of
 * [OffReportTest]: a fake listener, a fake Telegram for the text and for the point, pending reports
 * in a temp folder, sender jobs and worker tasks on the calling thread.
 */
class OffReportPointTest {

    @get:Rule val tmp = TemporaryFolder()

    private val level = 315621418

    private class Point(val token: String, val chatId: Long, val latitude: Double, val longitude: Double)

    private var sink: FidPushSink? = null
    private var wall = 1_790_000_000_000L
    private var mono = 50_000L
    private val sent = mutableListOf<String>()
    private val points = mutableListOf<Point>()
    private var answers: () -> AttemptResult = { ok }
    private var pointAnswer: () -> AttemptResult = { ok }
    private var onSend: () -> Unit = {}
    private var onPoint: () -> Unit = {}
    private val reads = mutableMapOf<Int, Int?>()
    private val queued = ArrayDeque<Runnable>()
    private lateinit var pending: PendingReports

    private val ok = AttemptResult("200", AttemptResult.Verdict.SENT)
    private val noNet = AttemptResult("io:UnknownHostException", AttemptResult.Verdict.RETRY)
    private val refused = AttemptResult("403", AttemptResult.Verdict.STOP)

    private val realRegistrar = OffReport.registrar
    private val realSender = OffReport.sender
    private val realLocator = OffReport.locator
    private val realWall = OffReport.wallClock
    private val realMono = OffReport.monoClock
    private val realSleep = OffReport.sleep
    private val realSend = OffReport.send
    private val realReader = OffReport.reader
    private val realSchedule = OffReport.schedule
    private val realPending = OffReport.pending

    private val map = "📍 <a href=\"https://yandex.ru/maps/?pt=27.560000,53.900000&amp;z=16&amp;l=map\">Открыть на карте</a>"
    private val placed = "<b>off at ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>\n\n🔋 Заряд <b>64%</b>\n🧭 Пробег 23 456 км\n\n$map"

    @Before fun setUp() {
        OffReport.resetForTest()
        pending = PendingReports(tmp.newFolder("offreport"), syncDir = {})
        OffReport.pending = pending
        OffReport.registrar = { _, _, s -> sink = s; FID_PUSH_OK }
        OffReport.schedule = { delayMs, task -> if (delayMs == 0L) queued.addLast(task) }
        OffReport.sender = { _, _, text, _, _ ->
            onSend()
            sent += text
            mono += 100
            answers()
        }
        OffReport.locator = { token, chatId, latitude, longitude, _, _ ->
            onPoint()
            points += Point(token, chatId, latitude, longitude)
            pointAnswer()
        }
        OffReport.wallClock = { wall }
        OffReport.monoClock = { mono }
        OffReport.sleep = { mono += it }
        OffReport.send = { it.run() }
        OffReport.reader = { _, fid -> reads[fid] }
    }

    @After fun tearDown() {
        OffReport.resetForTest()
        OffReport.registrar = realRegistrar
        OffReport.sender = realSender
        OffReport.locator = realLocator
        OffReport.wallClock = realWall
        OffReport.monoClock = realMono
        OffReport.sleep = realSleep
        OffReport.send = realSend
        OffReport.reader = realReader
        OffReport.schedule = realSchedule
        OffReport.pending = realPending
    }

    private fun arm(id: String, text: String = placed) {
        OffReport.arm(ArmRequest(id, "tok-$id", 42L, text, "<i>(at ${TelegramReportBuilder.TIME_PLACEHOLDER})</i>"))
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private fun push(value: Int) = sink!!.onEvent(level, value, 0.0, mono)

    /** The car on, armed with [id], off. */
    private fun powerOff(id: String, text: String = placed) {
        reads[level] = 2
        arm(id, text)
        push(2)
        push(0)
    }

    @Test fun `the power-off report with the place is followed by its point, the file is gone before it`() {
        val order = mutableListOf<String>()
        onSend = { order += "text" }
        onPoint = { order += "point pending=${pending.count()}" }
        powerOff("a1")
        assertEquals(listOf("text", "point pending=0"), order)
        assertEquals("the text goes as the app built it", TelegramReportBuilder.fillTime(placed, wall), sent.single())
        val point = points.single()
        assertEquals("tok-a1", point.token)
        assertEquals(42L, point.chatId)
        assertEquals(53.9, point.latitude, 0.0)
        assertEquals(27.56, point.longitude, 0.0)
        assertEquals(OffReportState.SENT, OffReport.status("a1").queried.state)
    }

    @Test fun `a report delivered later from disk sends its text, then its point, never a point before`() {
        answers = { noNet }
        powerOff("a1")
        assertTrue("the text never went out at the power-off", points.isEmpty())
        push(2)
        answers = { ok }
        val order = mutableListOf<String>()
        onSend = { order += "text" }
        onPoint = { order += "point pending=${pending.count()}" }
        arm("b1")
        assertEquals(listOf("text", "point pending=0"), order)
        assertEquals("tok-b1", points.single().token)
        assertEquals(OffReportState.SENT, OffReport.status("a1").queried.state)
    }

    @Test fun `a text Telegram refused sends no point`() {
        answers = { refused }
        powerOff("a1")
        assertEquals(1, sent.size)
        assertTrue(points.isEmpty())
    }

    @Test fun `a point that fails leaves the report delivered and is never tried again`() {
        pointAnswer = { noNet }
        powerOff("a1")
        assertEquals(1, sent.size)
        assertEquals(1, points.size)
        assertEquals(OffReportState.SENT, OffReport.status("a1").queried.state)
        assertEquals(0, pending.count())
        push(2)
        mono += OffReport.PENDING_BACKOFF_MAX_MS
        arm("b1") // the next drive: nothing waits
        assertEquals(1, sent.size)
        assertEquals(1, points.size)
    }

    @Test fun `a crashing point send does not turn a delivered report into a failure`() {
        OffReport.locator = { _, _, _, _, _, _ -> throw IllegalStateException("boom") }
        powerOff("a1")
        assertEquals(OffReportState.SENT, OffReport.status("a1").queried.state)
        assertEquals(0, pending.count())
    }

    @Test fun `a report without the place sends no point`() {
        powerOff("a1", "<b>off at ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>")
        assertEquals(1, sent.size)
        assertTrue(points.isEmpty())
    }

    @Test fun `a disarm while the text is in flight stops its point`() {
        onSend = { OffReport.disarm() }
        powerOff("a1")
        assertEquals(1, sent.size)
        assertTrue(points.isEmpty())
    }
}
