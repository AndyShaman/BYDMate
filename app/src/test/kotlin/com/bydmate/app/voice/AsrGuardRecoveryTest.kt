package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.voice.AsrGuardRecovery.Outcome
import com.bydmate.app.voice.GigaAmModelManager.Integrity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrGuardRecoveryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val journal get() = context.getSharedPreferences("asr_guard_recovery_test", Context.MODE_PRIVATE)

    private class FakeFiles(var integrity: Integrity, var unverifiedKept: Boolean = false) : AsrGuardRecovery.Files {
        var deleted = 0
        override suspend fun verifyIntegrity() = integrity
        override fun unverifiedKeptBefore() = unverifiedKept
        override suspend fun markUnverifiedKept() { unverifiedKept = true }
        override suspend fun delete() { deleted++ }
    }

    @Before fun setUp() {
        AsrLoadGuard(context).reset()
        journal.edit().clear().commit()
    }

    /** Two earlier processes died mid-load; this one finds the guard tripped. */
    private fun trippedGuard(): AsrLoadGuard {
        AsrLoadGuard(context).noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        AsrLoadGuard(context).noteLoadBegin(AsrLoadGuard.ARTIFACT_RECOGNIZER)
        return AsrLoadGuard(context).also { assertTrue(it.isTripped()) }
    }

    @Test fun `each integrity verdict maps to one outcome`() {
        val cases = listOf(
            Triple(Integrity.INTACT, false, Outcome.KEPT_INTACT),
            Triple(Integrity.INTACT, true, Outcome.KEPT_INTACT),
            Triple(Integrity.CORRUPT, false, Outcome.DELETED),
            Triple(Integrity.CORRUPT, true, Outcome.DELETED),
            Triple(Integrity.UNVERIFIED, false, Outcome.KEPT_UNVERIFIED),
            Triple(Integrity.UNVERIFIED, true, Outcome.DELETED),
        )
        for ((integrity, keptBefore, expected) in cases) {
            assertEquals("$integrity keptBefore=$keptBefore", expected, AsrGuardRecovery.decide(integrity, keptBefore))
        }
    }

    // The DiLink 3 field case: kills during the start-up load, files fine. The model stays.
    @Test fun `intact files are kept and the guard is reset`() = runBlocking {
        val guard = trippedGuard()
        val files = FakeFiles(Integrity.INTACT)
        assertEquals(Outcome.KEPT_INTACT, AsrGuardRecovery(guard, files, journal).run())
        assertEquals(0, files.deleted)
        assertFalse(guard.isTripped())
        assertFalse(AsrLoadGuard(context).isTripped())
    }

    @Test fun `corrupt files are deleted and the guard is reset`() = runBlocking {
        val guard = trippedGuard()
        val files = FakeFiles(Integrity.CORRUPT)
        assertEquals(Outcome.DELETED, AsrGuardRecovery(guard, files, journal).run())
        assertEquals(1, files.deleted)
        assertFalse(guard.isTripped())
    }

    @Test fun `unverifiable files get one more chance, the second trip deletes them`() = runBlocking {
        val files = FakeFiles(Integrity.UNVERIFIED)
        assertEquals(Outcome.KEPT_UNVERIFIED, AsrGuardRecovery(trippedGuard(), files, journal).run())
        assertEquals(0, files.deleted)
        assertTrue(files.unverifiedKept)
        assertEquals(Outcome.DELETED, AsrGuardRecovery(trippedGuard(), files, journal).run())
        assertEquals(1, files.deleted)
    }

    @Test fun `a verification that throws counts as unverified, never as intact`() = runBlocking {
        val files = object : AsrGuardRecovery.Files {
            var deleted = false
            override suspend fun verifyIntegrity(): Integrity = error("io")
            override fun unverifiedKeptBefore() = true
            override suspend fun markUnverifiedKept() = Unit
            override suspend fun delete() { deleted = true }
        }
        assertEquals(Outcome.DELETED, AsrGuardRecovery(trippedGuard(), files, journal).run())
        assertTrue(files.deleted)
    }

    @Test fun `an untripped guard touches nothing`() = runBlocking {
        val files = FakeFiles(Integrity.CORRUPT)
        assertEquals(Outcome.NOT_TRIPPED, AsrGuardRecovery(AsrLoadGuard(context), files, journal).run())
        assertEquals(0, files.deleted)
        assertEquals("guard_recovery: kept_intact=0 kept_unverified=0 deleted=0 last=-", AsrGuardRecovery.dumpLine(journal))
    }

    @Test fun `the dump line says what the last trip did`() = runBlocking {
        AsrGuardRecovery(trippedGuard(), FakeFiles(Integrity.INTACT), journal, now = { 0L }).run()
        AsrGuardRecovery(trippedGuard(), FakeFiles(Integrity.INTACT), journal, now = { 0L }).run()
        assertEquals(
            "guard_recovery: kept_intact=2 kept_unverified=0 deleted=0 last=kept_intact integrity=intact recognizer=2 vad=0",
            AsrGuardRecovery.dumpLine(journal),
        )
    }
}
