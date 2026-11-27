package com.bydmate.app.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** After an APK update Android kills the app and sends only MY_PACKAGE_REPLACED: without it the
 *  service stayed down until the next boot or unlock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BootReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bootLog get() = context.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)

    private fun deliver(action: String): String? {
        bootLog.edit().clear().commit()
        BootReceiver().onReceive(context, Intent(action))
        return bootLog.getString(BootReceiver.KEY_LAST_BOOT_METHOD, null)
    }

    @Test fun `an app update starts the same chain as a boot`() {
        val onBoot = deliver(Intent.ACTION_BOOT_COMPLETED)
        val onUpdate = deliver(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertNotNull(onUpdate)
        assertEquals(onBoot, onUpdate)
    }

    @Test fun `the manifest delivers the app update broadcast to the boot receiver`() {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName), 0)

        assertTrue(receivers.any { it.activityInfo.name == BootReceiver::class.java.name })
    }

    /** JobScheduler on DiLink blocks third-party jobs while the car is off, so a start job can sit
     *  enqueued until morning. Both boot and screen unlock must replace a stale job instead of
     *  letting it swallow the wake. */
    @Test fun `boot and screen unlock replace a pending start job, update keeps it`() {
        assertEquals(ExistingWorkPolicy.REPLACE, BootReceiver.workPolicyFor(Intent.ACTION_USER_PRESENT))
        assertEquals(ExistingWorkPolicy.REPLACE, BootReceiver.workPolicyFor(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(ExistingWorkPolicy.KEEP, BootReceiver.workPolicyFor(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(ExistingWorkPolicy.KEEP, BootReceiver.workPolicyFor(null))
    }

    /** The firmware marks a car wake with from_quickboot=true; reboot,bydcloud sends a bare boot, guarded only on Android 12. */
    @Test fun `the worker learns whether the boot was cold`() {
        val bare = Intent(Intent.ACTION_BOOT_COMPLETED)
        val wake = Intent(Intent.ACTION_BOOT_COMPLETED).putExtra("from_quickboot", true)

        assertEquals(false, BootReceiver.workInput(bare).getBoolean(AutostartTrace.KEY_COLD_BOOT, true))
        assertEquals(false, BootReceiver.workInput(wake).getBoolean(AutostartTrace.KEY_COLD_BOOT, true))
        assertEquals("BOOT_COMPLETED", BootReceiver.workInput(bare).getString(AutostartTrace.KEY_WORKER_SOURCE))
        assertEquals(false, BootReceiver.workInput(Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
            .getBoolean(AutostartTrace.KEY_COLD_BOOT, true))
    }

    @Test
    @Config(sdk = [32])
    fun `on Android 12 the worker marks a bare boot cold`() {
        val bare = Intent(Intent.ACTION_BOOT_COMPLETED)
        val wake = Intent(Intent.ACTION_BOOT_COMPLETED).putExtra("from_quickboot", true)

        assertEquals(true, BootReceiver.workInput(bare).getBoolean(AutostartTrace.KEY_COLD_BOOT, false))
        assertEquals(false, BootReceiver.workInput(wake).getBoolean(AutostartTrace.KEY_COLD_BOOT, true))
        assertEquals("BOOT_COMPLETED", BootReceiver.workInput(bare).getString(AutostartTrace.KEY_WORKER_SOURCE))
        assertEquals(false, BootReceiver.workInput(Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
            .getBoolean(AutostartTrace.KEY_COLD_BOOT, true))
    }
}
