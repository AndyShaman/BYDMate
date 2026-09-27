package com.bydmate.app.data.vehicle

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * writeStatus's [beforeSend] guard runs INSIDE HelperClientImpl's own transport mutex, right
 * before EACH transact attempt — the fix for the race the reviewer found: a drive-mode write
 * queued behind another slow helper call had its terrain speed limit checked BEFORE the queue
 * wait, not after it, so the car could speed past the limit while the write was waiting for the
 * lock. Review round 2 (2026-09-27) tightened this further: the guard is re-checked before a
 * dead-binder retry too, and it gets its own bounded budget so a slow guard cannot hold the
 * mutex, or be reported as a plain "helper unreachable", for the whole shared write timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientWriteGuardTest {

    private abstract class FakeIBinder : IBinder {
        override fun isBinderAlive(): Boolean = true
        override fun pingBinder(): Boolean = true
        override fun getInterfaceDescriptor(): String = HelperBinderProtocol.DESCRIPTOR
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        @Suppress("OVERRIDE_DEPRECATION")
        override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    /** Blocks inside transact() until [release] opens, simulating another helper call holding
     *  the mutex for a while; counts how many times the transact actually ran. [release]'s own
     *  await is bounded too: withTimeout on the test coroutine cannot interrupt a raw blocked
     *  thread, so a latch that is never opened must fail the test instead of hanging it. */
    private class BlockingFake(private val release: CountDownLatch, private val status: Int = 1) : FakeIBinder() {
        val transactCount = AtomicInteger(0)
        val started = CountDownLatch(1)
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            transactCount.incrementAndGet()
            started.countDown()
            assertTrue("release latch never opened", release.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            reply!!.writeInt(status); reply.writeInt(0)
            reply.setDataPosition(0)
            return true
        }
    }

    /** transact() throws DeadObjectException on its first call, then succeeds — simulates the
     *  cached binder dying between the two attempts transactBodyUnlocked makes. */
    private class DeadOnceFake(private val status: Int = 1) : FakeIBinder() {
        val transactCount = AtomicInteger(0)
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (transactCount.incrementAndGet() == 1) throw DeadObjectException("stale binder")
            reply!!.writeInt(status); reply.writeInt(0)
            reply.setDataPosition(0)
            return true
        }
    }

    private fun clientWith(binder: IBinder): HelperClientImpl =
        object : HelperClientImpl() { override fun resolveBinder(): IBinder = binder }

    /** Live fake replying with [status] immediately, no blocking. */
    private fun liveFake(status: Int): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply!!.writeInt(status); reply.writeInt(0)
            reply.setDataPosition(0)
            return true
        }
    }

    /** Replies to a single-item TX_READ_BATCH with [floatBits] (raw IEEE-754 bits, status 0) and
     *  to TX_WRITE with [writeStatus]; records every transact code it sees, in order, so a test
     *  can prove the guard's batch-read runs before the write, on this SAME binder. */
    private class RecordingFake(private val floatBits: Int, private val writeStatus: Int = 1) : FakeIBinder() {
        val codes = mutableListOf<Int>()
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            codes += code
            when (code) {
                HelperBinderProtocol.TX_READ_BATCH -> {
                    reply!!.writeInt(1)         // one item
                    reply.writeInt(0)           // status ok
                    reply.writeInt(floatBits)   // raw tx=7 word
                }
                HelperBinderProtocol.TX_WRITE -> {
                    reply!!.writeInt(writeStatus)
                    reply.writeInt(0)
                }
                else -> return false
            }
            reply.setDataPosition(0)
            return true
        }
    }

    private fun CountDownLatch.awaitOrFail(label: String) {
        assertTrue(label, await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a queued write's guard sees the speed that rose while it waited for the lock, and never reaches the binder`() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                val holdLatch = CountDownLatch(1)
                val fake = BlockingFake(holdLatch)
                val client = clientWith(fake)
                val speedNowHigh = AtomicBoolean(false)
                val guardCalls = AtomicInteger(0)
                val secondCallEntered = CountDownLatch(1)

                // Dispatchers.IO, not the bare default: runBlocking's own dispatcher is a single
                // cooperative event loop on THIS thread, and BlockingFake.transact blocks that
                // thread with a raw (non-suspending) CountDownLatch.await() — without a real
                // dispatcher here, the loop would never get a turn to start this coroutine's
                // body, and fake.started.await() would hang forever waiting for a transact()
                // that never runs (this is exactly the deadlock a first version of this test hit).
                //
                // First call takes the mutex and blocks inside transact() until holdLatch opens —
                // stands in for another slow helper op (e.g. a forcing window/split call) holding
                // the channel for seconds.
                val firstCall = async(Dispatchers.IO) { client.writeStatus(dev = 1000, fid = 1, value = 1) }
                try {
                    fake.started.awaitOrFail("the first call never reached transact")

                    // Test seam, not a countdown placed before the writeStatus call below: that
                    // race let a scheduler pause pass a guard-before-the-mutex regression, since
                    // "about to call writeStatus" and "about to acquire the mutex" are not the
                    // same instant. onBeforeLock fires exactly once per transact, right before the
                    // mutex.withLock in transactParsed — set only now, after the first call's own
                    // (no-op, onBeforeLock was still null) firing has already happened, so only
                    // the second call's firing reaches this latch.
                    client.onBeforeLock = { secondCallEntered.countDown() }

                    val secondCall = async(Dispatchers.IO) {
                        runCatching {
                            client.writeStatus(dev = 1023, fid = 1276260400, value = 4) {
                                guardCalls.incrementAndGet()
                                !speedNowHigh.get()
                            }
                        }
                    }
                    secondCallEntered.awaitOrFail("the second call never reached the lock")

                    // The mutex is still held by the (blocked) first call: a short bounded wait
                    // gives a regression — the guard running BEFORE the mutex is acquired — a
                    // real chance to show up as a non-zero guardCalls count here.
                    delay(GUARD_RACE_WINDOW_MS)
                    assertEquals("the guard must not run before the mutex is acquired", 0, guardCalls.get())

                    // The car speeds past the limit while the drive-mode write is still queued
                    // behind the first call; only then do we let the first call finish and free
                    // the mutex.
                    speedNowHigh.set(true)
                    holdLatch.countDown()

                    val result = secondCall.await()
                    firstCall.await()

                    assertEquals("guard must run exactly once, after the queue wait", 1, guardCalls.get())
                    assertTrue("a refused guard must surface as WriteGuardRefused",
                        result.exceptionOrNull() is WriteGuardRefused)
                    assertEquals("the refused write must never reach the binder",
                        1, fake.transactCount.get())
                } finally {
                    holdLatch.countDown() // idempotent: unblocks the first call if an assertion above failed
                }
            }
        }

    @Test
    fun `the guard runs again before the retry after a dead binder`() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val fake = DeadOnceFake()
            val client = clientWith(fake)
            val guardCalls = AtomicInteger(0)
            val status = client.writeStatus(dev = 1023, fid = 1276260400, value = 4) {
                guardCalls.incrementAndGet(); true
            }
            assertEquals(1, status)
            assertEquals("the guard must run before every transact attempt, retry included", 2, guardCalls.get())
            assertEquals(2, fake.transactCount.get())
        }
    }

    @Test
    fun `a guard slower than its own budget is refused, not silently reported as an unreachable helper`() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                val fake = liveFake(status = 1)
                val client = clientWith(fake)
                // Deterministic in place of a wall-clock "< 1800ms" assertion (flaky under load,
                // 2026-09-27 review round 3): the guard observes ITS OWN cancellation when
                // GUARD_TIMEOUT_MS (1000 ms) cuts it off mid-delay, well before its own
                // SLOW_GUARD_DELAY_MS (1500 ms) — which proves the cutoff regardless of how slow
                // or loaded the test machine is.
                val guardCancelled = CountDownLatch(1)
                val result = runCatching {
                    client.writeStatus(dev = 1023, fid = 1276260400, value = 4) {
                        try {
                            delay(SLOW_GUARD_DELAY_MS)
                            true
                        } catch (e: CancellationException) {
                            guardCancelled.countDown()
                            throw e
                        }
                    }
                }
                assertTrue("a slow guard must be refused, not returned as a plain null status",
                    result.exceptionOrNull() is WriteGuardRefused)
                guardCancelled.awaitOrFail(
                    "a slow guard must be cut off by its own GUARD_TIMEOUT_MS budget, not merely outrun")
            }
        }

    @Test
    fun `a guard returning true sends normally`() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val client = clientWith(liveFake(status = 1))
            val status = client.writeStatus(dev = 1023, fid = 1276260400, value = 4) { true }
            assertEquals(1, status)
        }
    }

    @Test
    fun `no guard is unchanged`() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val client = clientWith(liveFake(status = 1))
            assertEquals(1, client.writeStatus(dev = 1000, fid = 501219357, value = 1))
        }
    }

    @Test
    fun `the guard's reader runs a batch-read transaction right before the write, on the same binder, with no second lock`() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                val fake = RecordingFake(floatBits = java.lang.Float.floatToRawIntBits(5.0f))
                val client = clientWith(fake)
                var readerValue: Float? = null
                val status = client.writeStatus(dev = 1023, fid = 1276260400, value = 4) { reader ->
                    readerValue = reader.readFloat(1013, -1807745016)
                    true
                }
                assertEquals(1, status)
                assertEquals(5.0f, readerValue)
                // A second mutex.withLock from inside readFloat would deadlock this same coroutine
                // and time the test out instead of completing; reaching this line already disproves
                // it. The code order below additionally pins down "right before the write".
                assertEquals(
                    "the batch-read must run before the write transact, on the same binder",
                    listOf(HelperBinderProtocol.TX_READ_BATCH, HelperBinderProtocol.TX_WRITE),
                    fake.codes,
                )
            }
        }

    @Test
    fun `a sentinel speed from the batch read is refused, not treated as a real value`() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val sentinelBits = java.lang.Float.floatToRawIntBits(-1.0f) // "not initialized" sentinel
            val fake = RecordingFake(sentinelBits)
            val client = clientWith(fake)
            val result = runCatching {
                client.writeStatus(dev = 1023, fid = 1276260400, value = 4) { reader ->
                    reader.readFloat(1013, -1807745016) != null
                }
            }
            assertTrue("a sentinel speed must refuse the send, not be read as a real value",
                result.exceptionOrNull() is WriteGuardRefused)
            assertEquals("the refused write must never reach the binder",
                listOf(HelperBinderProtocol.TX_READ_BATCH), fake.codes)
        }
    }

    private companion object {
        /** Hang-guard: no test in this class may block the suite forever. */
        const val TEST_TIMEOUT_MS = 10_000L
        /** Bound for every CountDownLatch.await() in this file: withTimeout on the surrounding
         *  coroutine cannot interrupt a raw blocked thread, so a latch that is never opened must
         *  fail fast with a clear assertion instead of hanging the suite. */
        const val LATCH_TIMEOUT_MS = 5_000L
        /** Short bounded window given to a (correctly implemented) queued second call to prove
         *  it has NOT run its guard yet while the mutex is still held by the first call. */
        const val GUARD_RACE_WINDOW_MS = 200L
        /** Longer than HelperClientImpl's own GUARD_TIMEOUT_MS (1000 ms). */
        const val SLOW_GUARD_DELAY_MS = 1_500L
    }
}
