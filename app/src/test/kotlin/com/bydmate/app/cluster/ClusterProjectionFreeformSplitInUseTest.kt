package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.split.DisabledSplitPreferences
import com.bydmate.app.split.SplitPair
import com.bydmate.app.split.SplitPreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Split preferences whose feature switch and freeform use are set independently. */
private class FakeSplitPrefsInUse(
    private val featureEnabled: Boolean,
    private val inUse: Boolean,
) : SplitPreferences {
    override fun isFeatureEnabled(): Boolean = featureEnabled
    override fun isFreeformSplitInUse(): Boolean = inUse
    override fun setFeatureEnabled(enabled: Boolean) {}
    override fun getLastPair(): SplitPair? = null
    override fun saveLastPair(pair: SplitPair) {}
    override fun clearLastPair() {}
}

/** The freeform flag follows whether the split actually uses it, not the bare feature switch:
 *  native split mode and platformized firmware keep the pre-split value, the freeform split
 *  arms 1 at service start and on the toggle. */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionFreeformSplitInUseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val written = mutableListOf<Int>()
    private val helper = mockk<HelperClient>()
    private val bootstrap = mockk<HelperBootstrap>()

    @Before
    fun setUp() {
        written.clear()
        prefs().edit().clear().commit()
        ClusterProjectionManager.splitPreferences = DisabledSplitPreferences
        coEvery { helper.putGlobalSetting("enable_freeform_support", any()) } answers {
            written += secondArg<Int>(); true
        }
        coEvery { bootstrap.ensureRunning() } returns true
    }

    @After
    fun tearDown() {
        ClusterProjectionManager.splitPreferences = DisabledSplitPreferences
    }

    private fun prefs() =
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)

    private fun chooseVdTransport() {
        prefs().edit().putBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION, false).commit()
    }

    private fun realign() {
        ClusterProjectionManager.realignFreeformFlag(context, helper, bootstrap, force = false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `align writes 0 with VD transport when the split is on but not on the freeform path`() = runTest {
        chooseVdTransport()
        ClusterProjectionManager.splitPreferences = FakeSplitPrefsInUse(featureEnabled = true, inUse = false)
        assertTrue(ClusterProjectionManager.alignFreeformFlag(context, helper))
        assertEquals(listOf(0), written)
    }

    @Test
    fun `realign writes 0 with VD transport when the split is on but not on the freeform path`() {
        chooseVdTransport()
        ClusterProjectionManager.splitPreferences = FakeSplitPrefsInUse(featureEnabled = true, inUse = false)
        realign()
        assertEquals(listOf(0), written)
    }

    @Test
    fun `realign writes nothing without a transport choice when the split is not on the freeform path`() {
        ClusterProjectionManager.splitPreferences = FakeSplitPrefsInUse(featureEnabled = true, inUse = false)
        realign()
        assertEquals(emptyList<Int>(), written)
    }

    @Test
    fun `realign writes 1 with VD transport when the freeform split is in use`() {
        chooseVdTransport()
        ClusterProjectionManager.splitPreferences = FakeSplitPrefsInUse(featureEnabled = true, inUse = true)
        realign()
        assertEquals(listOf(1), written)
    }

    @Test
    fun `realign writes 1 without a transport choice when the freeform split is in use`() {
        ClusterProjectionManager.splitPreferences = FakeSplitPrefsInUse(featureEnabled = true, inUse = true)
        realign()
        assertEquals(listOf(1), written)
    }
}
