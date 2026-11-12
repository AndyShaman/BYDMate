package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.NetworkAvailableMonitor
import com.bydmate.app.data.automation.OneShotTrigger
import com.bydmate.app.data.automation.RuleShare
import com.bydmate.app.data.automation.testDispatcher
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * The rule kinds the share format accepts (RuleShare.KNOWN_*_KINDS) are the kinds the app runs
 * (ActionDispatcher, AutomationEngine) and can describe (RuleImportSummary). The three places
 * hold the same table by hand; a kind added to one and forgotten in another fails here.
 */
// SDK 33: the engine's init needs it (see AutomationEngineEdgeTest).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RuleKindTablesLockTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { LocalePreferences(ctx).setLanguage("ru") }

    private val unknownKind = "no_such_kind"

    // --- the dispatcher runs every known action kind ---

    private fun dispatchAnswer(kind: String): String? = runBlocking {
        // Robolectric context, relaxed mocks for everything else; a null payload makes "delay" answer "missing" at once, no waiting.
        testDispatcher(context = ctx, appStrings = AppStrings(ctx)).dispatch(ActionDef("", "x", kind, null), null).reason
    }

    @Test fun `the dispatcher routes every known action kind and refuses an unknown one`() {
        assertEquals("Unknown action kind: $unknownKind", dispatchAnswer(unknownKind))
        for (kind in RuleShare.KNOWN_ACTION_KINDS) {
            assertFalse("dispatch has no branch for '$kind'", dispatchAnswer(kind) == "Unknown action kind: $kind")
        }
    }

    // --- the import preview describes every known kind ---

    private val actionPayloads = mapOf(
        "media_key" to "play",
        "screen" to "0",
        "toggle" to ActionDispatcher.TOGGLE_SUNROOF,
    )

    private fun isFallback(line: String, kind: String) = line == kind || line.startsWith("$kind:")

    @Test fun `the import preview describes every known action kind`() {
        val control = RuleImportSummary.action(ActionDef("", "x", unknownKind, "{}"), ctx)
        assertEquals(unknownKind, control)
        for (kind in RuleShare.KNOWN_ACTION_KINDS) {
            val line = RuleImportSummary.action(ActionDef("", "x", kind, actionPayloads[kind] ?: "{}"), ctx)
            assertFalse("the preview of '$kind' falls back to the raw kind: $line", isFallback(line, kind))
        }
    }

    private val triggerValues = mapOf(
        "time_of_day" to "DAY",
        "steering_key" to "5",
        "voice" to "go",
        OneShotTrigger.KIND to OneShotTrigger.format(LocalDateTime.of(2030, 1, 1, 8, 30)),
    )

    private val triggerKinds = RuleShare.KNOWN_TRIGGER_KINDS - "param"

    @Test fun `the import preview describes every known trigger kind`() {
        fun trigger(kind: String, value: String) =
            TriggerDef(param = "P", chineseName = "", operator = "==", value = value, displayName = "label", kind = kind)
        assertEquals("P == 0", RuleImportSummary.trigger(trigger(unknownKind, "0"), ctx))
        for (kind in triggerKinds) {
            val value = triggerValues[kind] ?: "0"
            val line = RuleImportSummary.trigger(trigger(kind, value), ctx)
            assertFalse("the preview of '$kind' falls back to the parameter line: $line", line == "P == $value")
        }
    }

    // --- the engine evaluates every known trigger kind by its own branch ---

    private fun ruleOf(kind: String) = RuleEntity(
        id = 1, name = "r", triggerLogic = "AND",
        triggers = TriggerDef.listToJson(
            listOf(TriggerDef(param = "Speed", chineseName = "", operator = ">", value = "0", displayName = "s", kind = kind)),
        ),
        actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification"))),
        cooldownSeconds = 0,
    )

    /** True when a rule whose only trigger has [kind] fires on a poll with speed 50 after one at 0. */
    private fun firesOnSpeed(kind: String, screenOn: Boolean = true): Boolean = runBlocking {
        val ruleDao = mockk<RuleDao>(relaxed = true) { coEvery { getEnabled() } returns listOf(ruleOf(kind)) }
        val engine = AutomationEngine(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true),
            actionDispatcher = mockk(relaxed = true),
            placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() },
            networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
                every { lastAvailableAt } returns 0L
                every { probePending } returns false
            },
            context = ctx,
            appStrings = AppStrings(ctx),
        )
        engine.interactiveProvider = { screenOn }
        engine.evaluate(diParsData(speed = 0), null)
        engine.evaluate(diParsData(speed = 50), null)
        var fired = true
        try {
            coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
        } catch (_: AssertionError) {
            fired = false
        }
        fired
    }

    @Test fun `the engine evaluates every known trigger kind by its own branch, not as a parameter`() {
        assertEquals("a parameter trigger must fire on speed 50", true, firesOnSpeed("param"))
        assertEquals("a parameter trigger fires with the screen off too", true, firesOnSpeed("param", screenOn = false))
        for (kind in triggerKinds) {
            // service_start is true on the first poll with the screen on whatever the speed, so it
            // is checked with the screen off, where only its own branch can keep it false.
            val fired = firesOnSpeed(kind, screenOn = kind != "service_start")
            assertFalse("'$kind' is evaluated as a parameter trigger", fired)
        }
    }
}
