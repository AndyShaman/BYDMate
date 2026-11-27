package com.bydmate.app.service

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A start skipped on a cold boot waits for the screen: a firmware that wakes the screen without a
 * fresh BOOT_COMPLETED must not leave the car without the service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ColdBootDeferralTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var starts = 0
    private val start: (Context) -> Unit = { starts++ }

    @After fun tearDown() = ColdBootDeferral.disarm(context)

    private fun broadcast(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `the screen coming on starts the service once`() {
        ColdBootDeferral.arm(context, "cold_boot", start)
        broadcast(Intent.ACTION_SCREEN_ON)
        broadcast(Intent.ACTION_SCREEN_ON)
        assertEquals(1, starts)
    }

    @Test fun `unlock starts it too`() {
        ColdBootDeferral.arm(context, "cold_boot", start)
        broadcast(Intent.ACTION_USER_PRESENT)
        assertEquals(1, starts)
    }

    @Test fun `arming twice still starts once`() {
        ColdBootDeferral.arm(context, "cold_boot", start)
        ColdBootDeferral.arm(context, "cold_boot_guard", start)
        broadcast(Intent.ACTION_SCREEN_ON)
        assertEquals(1, starts)
    }

    @Test fun `nothing starts while the screen stays off`() {
        ColdBootDeferral.arm(context, "cold_boot", start)
        broadcast(Intent.ACTION_SCREEN_OFF)
        assertEquals(0, starts)
    }
}
