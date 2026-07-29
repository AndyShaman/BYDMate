package com.bydmate.app.cluster

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.split.DisabledSplitPreferences
import com.bydmate.app.split.SplitPair
import com.bydmate.app.split.SplitPreferences
import com.bydmate.app.split.SplitSide
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Fake SplitPreferences with a togglable feature flag for align tests. */
private class FakeSplitPrefsForAlign(featureEnabled: Boolean = false) : SplitPreferences {
    private var enabled = featureEnabled
    override fun isFeatureEnabled(): Boolean = enabled
    override fun setFeatureEnabled(enabled: Boolean) { this.enabled = enabled }
    override fun getLastPair(): SplitPair? = null
    override fun saveLastPair(pair: SplitPair) {}
    override fun clearLastPair() {}
}

/** Deterministic interleavings of the linearized freeform-flag write (codex Major fix):
 *  the flip-time write must wait out an in-flight projection attempt and write the pref
 *  value as re-read INSIDE the lock, not the value captured at flip time. */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionAlignTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val written = mutableListOf<Int>()
    private val helper = mockk<HelperClient>()

    @Before
    fun setUp() {
        written.clear()
        prefs().edit().clear().commit()
        ClusterProjectionManager.splitPreferences = DisabledSplitPreferences
        coEvery { helper.putGlobalSetting("enable_freeform_support", any()) } answers {
            written += secondArg<Int>(); true
        }
    }

    @After
    fun tearDown() {
        ClusterProjectionManager.splitPreferences = DisabledSplitPreferences
    }

    private fun prefs() =
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)

    private fun setDirectPref(direct: Boolean) {
        prefs().edit().putBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION, direct).commit()
    }

    @Test
    fun `flip to VD during a direct attempt lands 0 after the lock is released`() = runTest {
        setDirectPref(true)
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val attempt = launch {
            ClusterProjectionManager.withProjectionLock {
                lockHeld.complete(Unit)
                releaseLock.await()
            }
        }
        lockHeld.await()
        val align = launch { ClusterProjectionManager.alignFreeformFlag(context, helper) }
        testScheduler.advanceUntilIdle()
        assertTrue("aligned write must wait for the projection lock", written.isEmpty())
        setDirectPref(false)  // user flips to VD while the attempt is still in flight
        releaseLock.complete(Unit)
        attempt.join(); align.join()
        assertEquals(listOf(0), written)
    }

    @Test
    fun `flip back to direct during an attempt lands 1 after the lock is released`() = runTest {
        setDirectPref(false)
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val attempt = launch {
            ClusterProjectionManager.withProjectionLock {
                lockHeld.complete(Unit)
                releaseLock.await()
            }
        }
        lockHeld.await()
        val align = launch { ClusterProjectionManager.alignFreeformFlag(context, helper) }
        testScheduler.advanceUntilIdle()
        assertTrue(written.isEmpty())
        setDirectPref(true)
        releaseLock.complete(Unit)
        attempt.join(); align.join()
        assertEquals(listOf(1), written)
    }

    @Test
    fun `align writes the current pref value when uncontended`() = runTest {
        setDirectPref(true)
        assertTrue(ClusterProjectionManager.alignFreeformFlag(context, helper))
        assertEquals(listOf(1), written)
    }

    @Test
    fun `align reports failure when the daemon write throws`() = runTest {
        setDirectPref(false)
        coEvery {
            helper.putGlobalSetting("enable_freeform_support", any())
        } throws RuntimeException("binder down")
        assertFalse(ClusterProjectionManager.alignFreeformFlag(context, helper))
    }

    // --- split as second freeform consumer ---

    @Test
    fun `align writes 1 when VD transport but split feature is enabled`() = runTest {
        // VD transport alone would produce 0; split ON keeps the flag at 1.
        setDirectPref(false)
        ClusterProjectionManager.splitPreferences = FakeSplitPrefsForAlign(featureEnabled = true)
        assertTrue(ClusterProjectionManager.alignFreeformFlag(context, helper))
        assertEquals(listOf(1), written)
    }

    @Test
    fun `align writes 1 when both direct and split are enabled`() = runTest {
        setDirectPref(true)
        ClusterProjectionManager.splitPreferences = FakeSplitPrefsForAlign(featureEnabled = true)
        assertTrue(ClusterProjectionManager.alignFreeformFlag(context, helper))
        assertEquals(listOf(1), written)
    }

    @Test
    fun `align writes 0 when VD transport and split is disabled - byte-identical to pre-split`() = runTest {
        setDirectPref(false)
        // splitPreferences is already DisabledSplitPreferences (from setUp).
        assertTrue(ClusterProjectionManager.alignFreeformFlag(context, helper))
        assertEquals(listOf(0), written)
    }

    // --- split settings toggle: reboot-hint arming ---

    @Test
    fun `armSplitRebootHintIfNeeded arms pending when split enabled and direct projection off`() {
        // VD transport (direct off) means freeform flag was 0; enabling split writes it to 1
        // but a reboot is needed for the flag to take effect.
        setDirectPref(false)
        ClusterProjectionManager.armSplitRebootHintIfNeeded(context, splitEnabled = true)
        assertTrue(
            "KEY_SPLIT_FREEFORM_REBOOT_PENDING must be set when direct is off",
            prefs().getBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false)
        )
    }

    @Test
    fun `armSplitRebootHintIfNeeded does not arm pending when direct projection is on`() {
        // Direct projection on means freeform flag is already 1 and live; no reboot needed.
        setDirectPref(true)
        ClusterProjectionManager.armSplitRebootHintIfNeeded(context, splitEnabled = true)
        assertFalse(
            "KEY_SPLIT_FREEFORM_REBOOT_PENDING must stay clear when direct is on",
            prefs().getBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false)
        )
    }

    @Test
    fun `clearSplitRebootHint clears the pending flag`() {
        prefs().edit().putBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, true).commit()
        ClusterProjectionManager.clearSplitRebootHint(context)
        assertFalse(
            "KEY_SPLIT_FREEFORM_REBOOT_PENDING must be cleared after clearSplitRebootHint",
            prefs().getBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false)
        )
    }

    // --- Fix 2: explicit split-disable restores freeform flag ---

    @Test
    fun `realignFreeformFlag with force=true writes 0 when split disabled and no transport choice`() {
        // Simulate: user enabled split (flag was set to 1), then disabled split.
        // No projection transport chosen (KEY_DIRECT_PROJECTION absent = passive user).
        // After disable with force=true, flag must be written to 0.
        ClusterProjectionManager.splitPreferences = FakeSplitPrefsForAlign(featureEnabled = false)
        val bootstrap = mockk<HelperBootstrap>()
        coEvery { bootstrap.ensureRunning() } returns true

        ClusterProjectionManager.realignFreeformFlag(context, helper, bootstrap, force = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0), written)
    }
}
