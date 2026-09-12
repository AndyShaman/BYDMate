package com.bydmate.app.data.nativestack

import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.DumpFidsResult
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The once-guard means "a catalog was loaded and installed", not "someone called me": a
 * daemon that was unreachable at startup can be respawned by the watchdog mid-session, and
 * that second call must still get the catalog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FidCatalogManagerTest {

    /** Answers the dump from a script, and every probe read with a plausible value. */
    private class FakeHelper(
        private val answers: List<DumpFidsResult>,
        private val probeAnswers: Boolean = true,
    ) : HelperClient by mockk(relaxed = true) {
        var dumps = 0
        var batches = 0
        override suspend fun dumpFids(): DumpFidsResult {
            val answer = answers[minOf(dumps, answers.size - 1)]
            dumps++
            return answer
        }
        override suspend fun readBatch(items: List<BatchReadItem>): List<Pair<Int, Int>>? {
            batches++
            return if (probeAnswers) items.map { 0 to 50 } else null
        }
    }

    private val dump = javaClass.classLoader!!.getResource("fid-catalog-songplus.txt")!!.readText()

    /** Single reads are the probe's fallback; a silent car answers null to every one. */
    private fun silentAutoservice() = mockk<AutoserviceClient>(relaxed = true).also {
        coEvery { it.getIntRaw(any(), any()) } returns null
        coEvery { it.getFloat(any(), any()) } returns null
    }

    private fun manager(helper: HelperClient) = FidCatalogManager(
        ApplicationProvider.getApplicationContext(),
        helper,
        silentAutoservice(),
    )

    @After fun restoreConstants() {
        FidAddresses.resetToConstants()
        java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "fid-catalog.txt",
        ).delete()
    }

    @Test fun `an unreachable daemon leaves the constants and allows a later retry`() = runTest {
        val helper = FakeHelper(listOf(DumpFidsResult.BinderAbsent, DumpFidsResult.Success(dump)))
        val subject = manager(helper)

        subject.ensureResolved()
        assertEquals("constants", FidAddresses.table.source)
        assertEquals(null, subject.catalog)

        // The watchdog respawned the daemon: the second call must do the work.
        subject.ensureResolved()
        assertTrue(FidAddresses.table.source.startsWith("daemon"))
        assertTrue(FidAddresses.table.catalogCount > 0)

        // A third call is a no-op: the catalog is in force.
        subject.ensureResolved()
        assertEquals(2, helper.dumps)
    }

    @Test fun `a failing dump also allows a later retry`() = runTest {
        val helper = FakeHelper(listOf(DumpFidsResult.ReadError("status=2"), DumpFidsResult.Success(dump)))
        val subject = manager(helper)

        subject.ensureResolved()
        assertEquals("constants", FidAddresses.table.source)

        subject.ensureResolved()
        assertTrue(FidAddresses.table.source.startsWith("daemon"))
    }

    /**
     * A silent autoservice under a healthy daemon leaves the resolution PENDING, which is the
     * signal the service's poll tick retries on — the watchdog respawn would never fire here.
     * The retry that finally gets an answer installs the table and closes the pending state.
     */
    @Test fun `a pending resolution is retried until it installs the table`() = runTest {
        var probeSilent = true
        val helper = object : HelperClient by mockk(relaxed = true) {
            var batches = 0
            override suspend fun dumpFids(): DumpFidsResult = DumpFidsResult.Success(dump)
            override suspend fun readBatch(items: List<BatchReadItem>): List<Pair<Int, Int>>? {
                batches++
                return if (probeSilent) null else items.map { 0 to 50 }
            }
        }
        val subject = manager(helper)

        subject.ensureResolved()
        assertEquals("constants", FidAddresses.table.source)
        assertTrue("a silent car must leave the resolution pending", subject.resolvePending)
        assertTrue(subject.resolveStatus.contains("pending"))

        probeSilent = false
        subject.ensureResolved()
        // Source is "file" here, not "daemon": the first attempt already cached the catalog.
        assertTrue(FidAddresses.table.source != "constants")
        assertTrue(FidAddresses.table.catalogCount > 0)
        assertFalse("an installed table ends the pending state", subject.resolvePending)
        assertTrue(subject.resolveStatus.contains("installed"))
    }

    /**
     * The retry budget is bounded: a car that never answers must not have its poll tick probing
     * for the rest of the session. Once the budget is spent the pending flag drops and the dump
     * says so; only a daemon respawn asks again.
     */
    @Test fun `the pending retries stop once the budget is spent`() = runTest {
        val helper = FakeHelper(listOf(DumpFidsResult.Success(dump)), probeAnswers = false)
        val subject = manager(helper)

        var rounds = 0
        do {
            subject.ensureResolved()
            rounds++
        } while (subject.resolvePending && rounds < 20)

        assertFalse("the budget must stop the retries", subject.resolvePending)
        assertFalse("a spent budget closes the retry timer too", subject.resolveOpen)
        assertTrue("the budget must be a small number of attempts, was $rounds", rounds <= 10)
        assertTrue(subject.resolveStatus, subject.resolveStatus.contains("gave up"))
        assertEquals("constants", FidAddresses.table.source)
    }

    /**
     * The retry timer runs off [FidCatalogManager.resolveOpen], which — unlike `resolvePending` —
     * is already true before the startup attempt, so the timer can start with the service and
     * simply wait. It closes only when a table is installed or the budget is spent.
     */
    @Test fun `resolveOpen is true before the first attempt, while pending, and false once installed`() = runTest {
        var probeSilent = true
        val helper = object : HelperClient by mockk(relaxed = true) {
            override suspend fun dumpFids(): DumpFidsResult = DumpFidsResult.Success(dump)
            override suspend fun readBatch(items: List<BatchReadItem>): List<Pair<Int, Int>>? =
                if (probeSilent) null else items.map { 0 to 50 }
        }
        val subject = manager(helper)

        assertTrue("open before any attempt ran", subject.resolveOpen)
        assertFalse("nothing is pending before the first attempt", subject.resolvePending)

        subject.ensureResolved()
        assertTrue("still open while the resolution is pending", subject.resolveOpen)
        assertTrue(subject.resolvePending)

        probeSilent = false
        subject.ensureResolved()
        assertFalse("an installed table closes the retry timer", subject.resolveOpen)
    }

    /** A silent probe says nothing about the candidates, so nothing is installed and the
     *  next call probes again instead of freezing this car on constants for the session. */
    @Test fun `a silent probe leaves the constants and is retried`() = runTest {
        val helper = FakeHelper(listOf(DumpFidsResult.Success(dump)), probeAnswers = false)
        val subject = manager(helper)

        subject.ensureResolved()
        assertEquals("constants", FidAddresses.table.source)
        // The catalog itself was read, so the diagnostic dump can still show it.
        assertTrue(subject.catalog!!.symbols.isNotEmpty())
        val firstRound = helper.batches
        assertTrue(firstRound > 0)

        subject.ensureResolved()
        assertTrue(helper.batches > firstRound)
        assertEquals("constants", FidAddresses.table.source)
    }
}
