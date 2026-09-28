package com.bydmate.app.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
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
        assertEquals(Intent.ACTION_MY_PACKAGE_REPLACED, bootLog.getString(BootReceiver.KEY_LAST_BOOT_ACTION, null))
    }

    @Test fun `the manifest delivers the app update broadcast to the boot receiver`() {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName), 0)

        assertTrue(receivers.any { it.activityInfo.name == BootReceiver::class.java.name })
    }
}
