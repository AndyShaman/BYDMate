package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.split.SplitPair
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitSessionState
import com.bydmate.app.split.SplitSide
import com.bydmate.app.split.SplitStartResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispatcher routing for the payload-less split kinds (W6-F5):
 *  - "split_screen_close"  — always ends the session, idempotent when idle
 *  - "split_screen_toggle" — session -> exit, no session -> restore the last pair
 * Neither has a speed gate; the old "split_screen" kind is untouched.
 */
class ActionDispatcherSplitToggleTest {
    private val splitManager = mockk<SplitSessionManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val dispatcher: ActionDispatcher

    private val pair = SplitPair("com.nav", "com.music", SplitSide.RIGHT)

    init {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns
            mockk<NotificationManager>(relaxed = true)
        dispatcher = ActionDispatcher(
            mockk<VehicleApi>(relaxed = true), mockk<HelperClient>(relaxed = true), context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
            splitManager,
        )
    }

    private fun action(kind: String) =
        ActionDef(command = "", displayName = "x", kind = kind, payload = null)

    private fun stateIs(s: SplitSessionState) {
        every { splitManager.state } returns MutableStateFlow(s)
    }

    private fun active() = SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)

    // ── split_screen_close ──────────────────────────────────────────────────────

    @Test fun `close ends an active session and reports success`() = runBlocking {
        stateIs(active())
        val result = dispatcher.dispatch(action("split_screen_close"), null)
        assertTrue(result.success)
        coVerify(exactly = 1) { splitManager.exit() }
        coVerify(exactly = 0) { splitManager.startLastPair() }
    }

    @Test fun `close with no session is a successful no-op`() = runBlocking {
        stateIs(SplitSessionState.Idle)
        val result = dispatcher.dispatch(action("split_screen_close"), null)
        assertTrue(result.success)
        coVerify(exactly = 0) { splitManager.startLastPair() }
    }

    // ── split_screen_toggle ─────────────────────────────────────────────────────

    @Test fun `toggle with an active session exits`() = runBlocking {
        stateIs(active())
        val result = dispatcher.dispatch(action("split_screen_toggle"), null)
        assertTrue(result.success)
        coVerify(exactly = 1) { splitManager.exit() }
        coVerify(exactly = 0) { splitManager.startLastPair() }
    }

    @Test fun `toggle without a session restores the last pair`() = runBlocking {
        stateIs(SplitSessionState.Idle)
        coEvery { splitManager.startLastPair() } returns SplitStartResult.OK
        val result = dispatcher.dispatch(action("split_screen_toggle"), null)
        assertTrue(result.success)
        coVerify(exactly = 1) { splitManager.startLastPair() }
        coVerify(exactly = 0) { splitManager.exit() }
    }

    @Test fun `toggle without a saved pair fails without opening the picker`() = runBlocking {
        stateIs(SplitSessionState.Idle)
        coEvery { splitManager.startLastPair() } returns null
        val result = dispatcher.dispatch(action("split_screen_toggle"), null)
        assertFalse(result.success)
        assertTrue(result.reason?.isNotBlank() == true)
    }

    @Test fun `toggle maps FREEFORM_UNAVAILABLE to failure`() = runBlocking {
        stateIs(SplitSessionState.Idle)
        coEvery { splitManager.startLastPair() } returns SplitStartResult.FREEFORM_UNAVAILABLE
        assertFalse(dispatcher.dispatch(action("split_screen_toggle"), null).success)
    }

    @Test fun `toggle maps LAUNCH_FAILED to failure`() = runBlocking {
        stateIs(SplitSessionState.Idle)
        coEvery { splitManager.startLastPair() } returns SplitStartResult.LAUNCH_FAILED
        assertFalse(dispatcher.dispatch(action("split_screen_toggle"), null).success)
    }

    @Test fun `toggle maps DISABLED to failure`() = runBlocking {
        stateIs(SplitSessionState.Idle)
        coEvery { splitManager.startLastPair() } returns SplitStartResult.DISABLED
        assertFalse(dispatcher.dispatch(action("split_screen_toggle"), null).success)
    }

    // ── The launch kind stays as it was ─────────────────────────────────────────

    @Test fun `split_screen still starts the configured pair when a session is active`() = runBlocking {
        stateIs(active())
        coEvery { splitManager.start(any()) } returns SplitStartResult.OK
        val result = dispatcher.dispatch(
            ActionDef(
                command = "", displayName = "x", kind = "split_screen",
                payload = """{"narrow":"com.nav","wide":"com.music","side":"right"}""",
            ),
            null,
        )
        assertTrue(result.success)
        coVerify(exactly = 1) { splitManager.start(pair) }
        coVerify(exactly = 0) { splitManager.exit() }
    }
}
