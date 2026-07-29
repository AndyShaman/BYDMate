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
 * On-car 390-4: the pill kept floating above our own fullscreen UI when BYDMate was opened
 * over a live split. The pill follows the same foreground edges as the widget (390-1); the
 * SESSION is never touched, so the 390-2 Back flow still restores a live split.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitPillForegroundTest {

    private val sessionManager = mockk<SplitSessionManager>(relaxed = true)
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val splitPrefs = mockk<SplitPreferences>(relaxed = true)
    private val events = MutableSharedFlow<SplitEvent>(extraBufferCapacity = 4)
    private val state = MutableStateFlow<SplitSessionState>(
        SplitSessionState.Active(
            SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT), narrowTaskId = 11, wideTaskId = 10,
        ),
    )

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

    @Test fun `our app coming to the foreground removes the pill`() {
        val controller = startController()
        assertNotNull("Precondition: an active session attaches the pill", controller.pillView)

        controller.setOwnAppForegrounded(true)

        assertNull("the pill must not float above our own UI", controller.pillView)
    }

    @Test fun `going back to the background restores the pill of a live session`() {
        val controller = startController()
        controller.setOwnAppForegrounded(true)

        controller.setOwnAppForegrounded(false)

        assertNotNull("a live session gets its pill back", controller.pillView)
    }

    @Test fun `a session that ended while we were in the foreground gets no pill back`() {
        val controller = startController()
        controller.setOwnAppForegrounded(true)
        // The split was torn down while BYDMate was open (pane closed, exit, native split…).
        state.value = SplitSessionState.Idle
        idle()

        controller.setOwnAppForegrounded(false)

        assertNull("no session — nothing to control", controller.pillView)
    }

    @Test fun `session teardown while the pill is hidden keeps it gone`() {
        // F-3 is unchanged: teardown removes the pill regardless of this flag. With the pill
        // already hidden the teardown must stay a no-op rather than resurrect anything.
        val controller = startController()
        controller.setOwnAppForegrounded(true)

        events.tryEmit(SplitEvent.SessionEnded(EndReason.EXIT))
        idle()

        assertNull(controller.pillView)
    }

    @Test fun `a state emission while we are in the foreground does not float the pill back`() {
        // mirror()/pane change re-emits Active; without the gate the observer would re-attach.
        val controller = startController()
        controller.setOwnAppForegrounded(true)

        state.value = SplitSessionState.Active(
            SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT), narrowTaskId = 11, wideTaskId = 10,
        )
        idle()

        assertNull("the pill must stay away while our UI is on screen", controller.pillView)
    }
}
