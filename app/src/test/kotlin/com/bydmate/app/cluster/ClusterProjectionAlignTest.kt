package com.bydmate.app.cluster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
        coEvery { helper.putGlobalSetting("enable_freeform_support", any()) } answers {
            written += secondArg<Int>(); true
        }
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
}
