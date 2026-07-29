package com.bydmate.app.ui.widget

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [WidgetController.relocale] — specifically that SplitOverlayController.relocale()
 * is reached even when the floating widget is not attached (widgetView == null).
 *
 * The test uses [WidgetController.splitOverlayRelocaleAction] as a hook to verify the call without
 * requiring a Hilt-initialized application context in the unit test environment.
 */
class WidgetRelocaleTest {

    /**
     * Verifies that [WidgetController.relocale] invokes [SplitOverlayController.relocale] (via
     * [WidgetController.splitOverlayRelocaleAction]) even when no widget is currently attached.
     *
     * Anti-vacuity: reverting to deriving appCtx from widgetView (the broken implementation)
     * means the action is never reached when widgetView is null — capturedCtx stays null and
     * assertSame fails, proving the guard is meaningful.
     *
     * In tests, widgetView is always null (attach() requires a real WindowManager and is never
     * called), so this mirrors the exact production scenario (Settings Activity foregrounded →
     * WidgetController.setAppForegrounded(true) → detach() → widgetView = null).
     */
    @Test
    fun `relocale reaches splitOverlayController even without attached widget (C-5)`() {
        val productionAction = WidgetController.splitOverlayRelocaleAction
        var capturedCtx: Context? = null
        WidgetController.splitOverlayRelocaleAction = { ctx -> capturedCtx = ctx }
        try {
            val fakeCtx = mockk<Context>(relaxed = true)
            // widgetView is null in unit tests — widget was never attached.
            WidgetController.relocale(fakeCtx)
            assertSame(
                "splitOverlayController.relocale() must be reached with the supplied Context " +
                    "even when no widget is attached (C-5 fix: caller-supplied ctx, not widgetView?.context)",
                fakeCtx,
                capturedCtx,
            )
        } finally {
            WidgetController.splitOverlayRelocaleAction = productionAction
        }
    }
}
