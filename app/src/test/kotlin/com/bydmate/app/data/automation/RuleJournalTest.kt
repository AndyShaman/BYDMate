package com.bydmate.app.data.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.util.AppStrings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
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

/** Audit 3.19 items 7-9: journal reasons, retention and the dump section, on a real Room DB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RuleJournalTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: RuleLogDao
    private lateinit var journal: RuleJournal
    private val rule = RuleEntity(id = 7, name = "Окна", triggers = "[]", actions = "[]")

    @Before
    fun setUp() {
        LocalePreferences(ctx).setLanguage("ru")
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.ruleLogDao()
        journal = RuleJournal(dao, AppStrings(ctx))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun only(): RuleLogEntity = dao.getAll().first().single()

    private fun reasonOf(entry: RuleLogEntity): String =
        JSONArray(entry.actionsResult).getJSONObject(0).getString("reason")

    @Test fun `a cancelled confirmation is journaled with its reason`() = runTest {
        journal.cancelled(rule, "{}", 1_000L)

        val e = only()
        assertFalse(e.success)
        assertEquals(1_000L, e.triggeredAt)
        assertEquals("Отменено в окне подтверждения", reasonOf(e))
    }

    @Test fun `an unanswered confirmation is journaled with its reason`() = runTest {
        journal.timeout(rule, "{}", 1_000L)

        assertEquals("Нет ответа в окне подтверждения, не выполнено", reasonOf(only()))
    }

    @Test fun `a park-only refusal is journaled with its reason`() = runTest {
        journal.parkRequired(rule, """{"button_press":"1"}""", 4)

        assertEquals("Не выполнено: правило только на парковке, а передача не P", reasonOf(only()))
    }

    @Test fun `prune drops entries older than 30 days and keeps the newest 2000`() = runTest {
        val now = 100L * 24 * 60 * 60 * 1000
        val old = now - RuleJournal.MAX_AGE_MS - 1
        dao.insert(RuleLogEntity(ruleId = 1, ruleName = "r", triggeredAt = old, triggersSnapshot = "{}", actionsResult = "[]", success = true))
        repeat(RuleJournal.MAX_ROWS + 5) { i ->
            dao.insert(RuleLogEntity(ruleId = 1, ruleName = "r", triggeredAt = now - i, triggersSnapshot = "{}", actionsResult = "[]", success = true))
        }

        journal.prune(now)

        val left = dao.getAll().first()
        assertEquals(RuleJournal.MAX_ROWS, left.size)
        assertTrue(left.all { it.triggeredAt > now - RuleJournal.MAX_ROWS })
    }

    @Test fun `dump lists the newest entries with every step and its reason`() = runTest {
        dao.insert(
            RuleLogEntity(
                ruleId = 7, ruleName = "Окна", triggeredAt = 5_000L, triggersSnapshot = """{"Speed":0}""",
                actionsResult = """[{"kind":"param","success":true},{"kind":"param","success":false,"reason":"Машина не подтвердила команду"}]""",
                success = false,
            )
        )
        journal.timeout(rule, "{}", 9_000L)

        val lines = journal.dumpLines()

        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("rule=7 \"Окна\" ok=false steps=[timeout:fail(Нет ответа в окне подтверждения, не выполнено)]"))
        assertTrue(lines[1], lines[1].contains("steps=[param:ok, param:fail(Машина не подтвердила команду)] trig={\"Speed\":0}"))
    }

    @Test fun `dump caps at the newest 50 entries`() = runTest {
        repeat(60) { i -> journal.timeout(rule, "{}", i.toLong()) }

        assertEquals(RuleJournal.DUMP_ENTRIES, journal.dumpLines().size)
    }

    @Test fun `dump never prints a link a stored reason carries`() = runTest {
        val reasons = listOf(
            "Нет приложения для обработки: No Activity found to handle Intent { act=android.intent.action.VIEW " +
                "dat=geo:53.9045,27.5615?q=53.9045,27.5615 flg=0x10000000 }",
            "Нет разрешения: https://api.example.com/hook?token=SECRET123&chat=42",
            "yandexnavi://build_route_on_map?lat_to=53.9&lon_to=27.5 и geo:53.9,27.5",
        )
        reasons.forEach { reason ->
            dao.insert(
                RuleLogEntity(
                    ruleId = 7, ruleName = "Окна", triggeredAt = 5_000L, triggersSnapshot = "{}",
                    actionsResult = JSONArray().put(
                        org.json.JSONObject().put("kind", "url").put("success", false).put("reason", reason)
                    ).toString(),
                    success = false,
                )
            )
        }

        val dump = journal.dumpLines().joinToString("\n")

        listOf("53.9", "27.5", "SECRET123", "token", "api.example.com", "chat=42").forEach {
            assertFalse("$it leaked: $dump", dump.contains(it))
        }
        assertTrue(dump, dump.contains("url:fail(Нет приложения для обработки: No Activity found to handle Intent { act=android.intent.action.VIEW <uri> flg=0x10000000 })"))
        assertTrue(dump, dump.contains("url:fail(Нет разрешения: <uri>)"))
        assertTrue(dump, dump.contains("url:fail(<uri> и <uri>)"))
    }

    // Journal rows stay per attempt; the log line is once per rule and reason per minute.
    @Test fun `refusal log lines are throttled per rule and reason, rows are not`() = runTest {
        var now = 1_000_000L
        journal = RuleJournal(dao, AppStrings(ctx), com.bydmate.app.data.autoservice.LogThrottle()) { now }
        ShadowLog.clear()

        repeat(3) {
            journal.cancelled(rule, "{}", now)
            journal.timeout(rule, "{}", now)
            journal.parkRequired(rule, "{}", 4)
            now += 1_000
        }
        now += 60_000
        journal.cancelled(rule, "{}", now)

        val lines = ShadowLog.getLogsForTag("AutomationEngine").map { it.msg }
        assertEquals(10, dao.getAll().first().size)
        assertEquals(2, lines.count { it == "rule 7 'Окна' skipped: confirm cancelled" })
        assertEquals(1, lines.count { it == "rule 7 'Окна' skipped: confirm timed out" })
        assertEquals(1, lines.count { it == "rule 7 'Окна' skipped: park only, gear=4" })
    }

    @Test fun `an empty journal says so`() = runTest {
        assertEquals(listOf("(journal empty)"), journal.dumpLines())
    }
}
