package com.bydmate.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EnergyDataLivenessStoreTest {

    private fun store() = EnergyDataLivenessStorePrefs(
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("energydata_liveness_test", Context.MODE_PRIVATE)
    )

    @Test
    fun `fresh store is alive with zero streak and no fingerprint`() {
        val s = store()
        assertFalse(s.isDead())
        assertEquals(0, s.streak())
        assertFalse(s.pendingDriving())
        assertNull(s.fingerprint())
        assertEquals(0L, s.lastIncrementTs())
    }

    @Test
    fun `round-trips all fields`() {
        val s = store()
        s.setDead()
        s.setStreak(2)
        s.setPendingDriving(true)
        s.setFingerprint(111L, 222L)
        s.setLastIncrementTs(333L)
        assertTrue(s.isDead())
        assertEquals(2, s.streak())
        assertTrue(s.pendingDriving())
        assertEquals(111L to 222L, s.fingerprint())
        assertEquals(333L, s.lastIncrementTs())
    }

    @Test
    fun `schema version mismatch discards everything`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("energydata_liveness_test2", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("energydata_liveness_schema_version", EnergyDataLivenessStorePrefs.SCHEMA_VERSION + 1)
            .putBoolean("energydata_dead", true)
            .putInt("energydata_dead_streak", 3)
            .putBoolean("energydata_pending_driving", true)
            .putLong("energydata_fp_mtime", 1L)
            .putLong("energydata_fp_size", 2L)
            .putLong("energydata_last_increment_ts", 3L)
            .commit()
        val s = EnergyDataLivenessStorePrefs(prefs)
        assertFalse(s.isDead())
        assertEquals(0, s.streak())
        assertFalse(s.pendingDriving())
        assertNull(s.fingerprint())
        assertEquals(0L, s.lastIncrementTs())
    }

    @Test
    fun `reset clears everything`() {
        val s = store()
        s.setDead()
        s.setStreak(3)
        s.setPendingDriving(true)
        s.setFingerprint(1L, 2L)
        s.setLastIncrementTs(3L)
        s.reset()
        assertFalse(s.isDead())
        assertEquals(0, s.streak())
        assertFalse(s.pendingDriving())
        assertNull(s.fingerprint())
        assertEquals(0L, s.lastIncrementTs())
    }

    @Test
    fun `setter after schema bump does not resurrect stale keys`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("energydata_liveness_test3", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("energydata_liveness_schema_version", EnergyDataLivenessStorePrefs.SCHEMA_VERSION - 1)
            .putBoolean("energydata_dead", true)
            .putInt("energydata_dead_streak", 3)
            .commit()
        val s = EnergyDataLivenessStorePrefs(prefs)
        s.setFingerprint(10L, 20L)   // first write under the new schema
        assertFalse(s.isDead())      // stale verdict must NOT come back
        assertEquals(0, s.streak())
        assertEquals(10L to 20L, s.fingerprint())
    }
}
