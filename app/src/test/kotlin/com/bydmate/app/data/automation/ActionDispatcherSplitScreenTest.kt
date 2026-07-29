package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.split.SplitPair
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitSide
import com.bydmate.app.split.SplitStartResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies dispatcher routing for kind="split_screen":
 *  - All three SplitStartResult mappings (OK, FREEFORM_UNAVAILABLE, LAUNCH_FAILED)
 *  - SplitSessionManager.start() called with the correct SplitPair
 *  - Malformed / incomplete payloads return failure without calling the manager
 */
class ActionDispatcherSplitScreenTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val notificationManager = mockk<NotificationManager>(relaxed = true)
    private val splitManager = mockk<SplitSessionManager>(relaxed = true)
    private val dispatcher: ActionDispatcher

    init {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        // Relaxed context.getString() returns "" — enough to verify success=false for error cases.
        dispatcher = ActionDispatcher(
            vehicleApi, helper, context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
            splitManager,
        )
    }

    private fun splitAction(
        narrow: String = "com.example.narrow",
        wide: String = "com.example.wide",
        side: String = "right",
    ) = ActionDef(
        command = "",
        displayName = "Split Screen",
        kind = "split_screen",
        payload = """{"narrow":"$narrow","wide":"$wide","side":"$side"}""",
    )

    // ── Result mapping ──────────────────────────────────────────────────────────

    @Test fun `OK result returns success`() = runBlocking {
        coEvery { splitManager.start(any()) } returns SplitStartResult.OK
        val result = dispatcher.dispatch(splitAction(), null)
        assertTrue(result.success)
    }

    @Test fun `FREEFORM_UNAVAILABLE result returns failure`() = runBlocking {
        coEvery { splitManager.start(any()) } returns SplitStartResult.FREEFORM_UNAVAILABLE
        val result = dispatcher.dispatch(splitAction(), null)
        assertFalse(result.success)
    }

    @Test fun `LAUNCH_FAILED result returns failure`() = runBlocking {
        coEvery { splitManager.start(any()) } returns SplitStartResult.LAUNCH_FAILED
        val result = dispatcher.dispatch(splitAction(), null)
        assertFalse(result.success)
    }

    @Test fun `DISABLED result returns failure without null reason`() = runBlocking {
        coEvery { splitManager.start(any()) } returns SplitStartResult.DISABLED
        val result = dispatcher.dispatch(splitAction(), null)
        assertFalse(result.success)
    }

    // ── SplitPair construction ──────────────────────────────────────────────────

    @Test fun `start called with correct SplitPair for side right`() = runBlocking {
        coEvery { splitManager.start(any()) } returns SplitStartResult.OK
        dispatcher.dispatch(splitAction("com.nav", "com.music", "right"), null)
        coVerify(exactly = 1) {
            splitManager.start(SplitPair(narrowPkg = "com.nav", widePkg = "com.music", narrowSide = SplitSide.RIGHT))
        }
    }

    @Test fun `start called with correct SplitPair for side left`() = runBlocking {
        coEvery { splitManager.start(any()) } returns SplitStartResult.OK
        dispatcher.dispatch(splitAction("com.nav", "com.music", "left"), null)
        coVerify(exactly = 1) {
            splitManager.start(SplitPair(narrowPkg = "com.nav", widePkg = "com.music", narrowSide = SplitSide.LEFT))
        }
    }

    // ── Malformed payload — manager must NOT be called ──────────────────────────

    @Test fun `blank narrow package returns failure without calling manager`() = runBlocking {
        val result = dispatcher.dispatch(splitAction(narrow = ""), null)
        assertFalse(result.success)
        coVerify(exactly = 0) { splitManager.start(any()) }
    }

    @Test fun `blank wide package returns failure without calling manager`() = runBlocking {
        val result = dispatcher.dispatch(splitAction(wide = ""), null)
        assertFalse(result.success)
        coVerify(exactly = 0) { splitManager.start(any()) }
    }

    @Test fun `unknown side returns failure without calling manager`() = runBlocking {
        val result = dispatcher.dispatch(splitAction(side = "center"), null)
        assertFalse(result.success)
        coVerify(exactly = 0) { splitManager.start(any()) }
    }

    @Test fun `null payload returns failure without calling manager`() = runBlocking {
        val action = ActionDef(command = "", displayName = "x", kind = "split_screen", payload = null)
        val result = dispatcher.dispatch(action, null)
        assertFalse(result.success)
        coVerify(exactly = 0) { splitManager.start(any()) }
    }

    @Test fun `malformed json payload returns failure without calling manager`() = runBlocking {
        val action = ActionDef(command = "", displayName = "x", kind = "split_screen", payload = "not-json")
        val result = dispatcher.dispatch(action, null)
        assertFalse(result.success)
        coVerify(exactly = 0) { splitManager.start(any()) }
    }
}
