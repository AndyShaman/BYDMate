package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ClusterProjectionMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(
        ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun `fresh install defaults to factory transport`() {
        assertFalse(ClusterProjectionManager.isDirectProjectionEnabled(context))
        // Migration must not invent a pref for a fresh install.
        assertFalse(prefs.contains(ClusterProjectionManager.KEY_DIRECT_PROJECTION))
    }

    @Test
    fun `direct-era reboot marker migrates user to extended transport`() {
        prefs.edit().putBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, false).commit()
        assertTrue(ClusterProjectionManager.isDirectProjectionEnabled(context))
        assertTrue(prefs.getBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION, false))
    }

    @Test
    fun `direct display id marker migrates user to extended transport`() {
        prefs.edit().putInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2).commit()
        assertTrue(ClusterProjectionManager.isDirectProjectionEnabled(context))
    }

    @Test
    fun `explicit factory choice survives migration despite markers`() {
        prefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION, false)
            .putInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2)
            .commit()
        assertFalse(ClusterProjectionManager.isDirectProjectionEnabled(context))
    }

    @Test
    fun `compositor marker alone does not migrate - it is written on both transports`() {
        // A passive user who projected via the factory/VD path has this marker but
        // neither direct-only marker; they must stay on factory with no pref invented.
        prefs.edit().putBoolean("compositor_powered_on", false).commit()
        assertFalse(ClusterProjectionManager.isDirectProjectionEnabled(context))
        assertFalse(prefs.contains(ClusterProjectionManager.KEY_DIRECT_PROJECTION))
    }

    @Test
    fun `realign does nothing for a passive user - no transport choice no daemon calls`() {
        // Passive user: prefs are empty (no KEY_DIRECT_PROJECTION, no direct-era markers).
        val helper = mockk<HelperClient>(relaxed = true)
        val bootstrap = mockk<HelperBootstrap>(relaxed = true)
        ClusterProjectionManager.realignFreeformFlag(context, helper, bootstrap)
        // Let the manager's scope (Dispatchers.Main + SupervisorJob) run to completion.
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 0) { bootstrap.ensureRunning() }
        coVerify(exactly = 0) { helper.putGlobalSetting(any(), any()) }
    }
}
