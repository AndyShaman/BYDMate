package com.bydmate.app.split

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Native 3:7 session ([Split37Engine]): the panes, the divider and the swap gesture belong to
 * the firmware, so the pill has nothing to control and must never appear over them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitPillNativePanesTest {

    private val sessionManager = mockk<SplitSessionManager>(relaxed = true)
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val splitPrefs = mockk<SplitPreferences>(relaxed = true)
    private val events = MutableSharedFlow<SplitEvent>(extraBufferCapacity = 4)
    private val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)
    private val state = MutableStateFlow<SplitSessionState>(SplitSessionState.Idle)

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun startController(): SplitOverlayController {
        every { splitPrefs.isFeatureEnabled() } returns true
        every { sessionManager.events } returns events
        every { sessionManager.state } returns state
        val controller = SplitOverlayController(ctx, Provider { sessionManager }, helperClient, splitPrefs)
        controller.start(CoroutineScope(Dispatchers.Unconfined + SupervisorJob()))
        idle()
        return controller
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun active(nativePanes: Boolean) =
        SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10, nativePanes = nativePanes)

    @Test fun `a native session attaches no pill`() {
        val controller = startController()

        state.value = active(nativePanes = true)
        idle()

        assertNull("the firmware's panes carry no pill of ours", controller.pillView)
    }

    @Test fun `switching from our panes to the native ones removes the pill`() {
        // start(pair) over a live legacy session replaces it in place — Active → Active, no Idle
        // in between, so this branch is the only place the stale pill can be removed.
        val controller = startController()
        state.value = active(nativePanes = false)
        idle()
        assertNotNull("Precondition: a legacy session attaches the pill", controller.pillView)

        state.value = active(nativePanes = true)
        idle()

        assertNull("the pill must not survive into a native session", controller.pillView)
    }

    @Test fun `leaving our app during a native session brings no pill back`() {
        val controller = startController()
        state.value = active(nativePanes = true)
        idle()

        controller.setOwnAppForegrounded(true)
        controller.setOwnAppForegrounded(false)

        assertNull("nothing to restore: the panes are the firmware's", controller.pillView)
    }

    @Test fun `a legacy session still gets its pill`() {
        // Guards the default: nativePanes = false is the whole fleet's session.
        val controller = startController()

        state.value = active(nativePanes = false)
        idle()

        assertNotNull(controller.pillView)
    }
}
