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
        dispatcher = testDispatcher(vehicleApi, helper, context, splitSessionManager = splitManager)
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

    @Test fun `each manager result maps to success or failure`() = runBlocking {
        val cases = listOf(
            SplitStartResult.OK to true,
            SplitStartResult.FREEFORM_UNAVAILABLE to false,
            SplitStartResult.LAUNCH_FAILED to false,
            SplitStartResult.DISABLED to false,
        )
        for ((managerResult, success) in cases) {
            coEvery { splitManager.start(any()) } returns managerResult
            assertEquals("case: $managerResult", success, dispatcher.dispatch(splitAction(), null).success)
        }
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

    @Test fun `a malformed payload fails without calling the manager`() = runBlocking {
        fun raw(payload: String?) = ActionDef(command = "", displayName = "x", kind = "split_screen", payload = payload)
        val cases = listOf(
            "blank narrow package" to splitAction(narrow = ""),
            "blank wide package" to splitAction(wide = ""),
            "unknown side" to splitAction(side = "center"),
            "null payload" to raw(null),
            "malformed json payload" to raw("not-json"),
        )
        for ((label, action) in cases) assertFalse("case: $label", dispatcher.dispatch(action, null).success)
        coVerify(exactly = 0) { splitManager.start(any()) }
    }
}
