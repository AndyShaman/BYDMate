package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * writeStatus's [beforeSend] guard runs INSIDE HelperClientImpl's own transport mutex, right
 * before the transact — the fix for the race the reviewer found: a drive-mode write queued
 * behind another slow helper call had its terrain speed limit checked BEFORE the queue wait,
 * not after it, so the car could speed past the limit while the write was waiting for the lock.
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
     *  the mutex for a while; counts how many times the transact actually ran. */
    private class BlockingFake(private val release: CountDownLatch, private val status: Int = 1) : FakeIBinder() {
        val transactCount = AtomicInteger(0)
        val started = CountDownLatch(1)
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            transactCount.incrementAndGet()
            started.countDown()
            release.await()
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

    @Test
    fun `a queued write's guard sees the speed that rose while it waited for the lock, and never reaches the binder`() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                val holdLatch = CountDownLatch(1)
                val fake = BlockingFake(holdLatch)
                val client = clientWith(fake)

                // Dispatchers.IO, not the bare default: runBlocking's own dispatcher is a single
                // cooperative event loop on THIS thread, and the very next line blocks that
                // thread with a raw (non-suspending) CountDownLatch.await() — without a real
                // dispatcher here, the loop would never get a turn to start this coroutine's
                // body, and fake.started.await() would hang forever waiting for a transact()
                // that never runs (this is exactly the deadlock a first version of this test hit).
                //
                // First call takes the mutex and blocks inside transact() until holdLatch opens —
                // stands in for another slow helper op (e.g. a forcing window/split call) holding
                // the channel for seconds.
                val firstCall = async(Dispatchers.IO) { client.writeStatus(dev = 1000, fid = 1, value = 1) }
                fake.started.await() // the first call now holds the mutex

                val speedNowHigh = AtomicBoolean(false)
                val guardCalls = AtomicInteger(0)
                val secondCall = async(Dispatchers.IO) {
                    runCatching {
                        client.writeStatus(dev = 1023, fid = 1276260400, value = 4) {
                            guardCalls.incrementAndGet()
                            !speedNowHigh.get()
                        }
                    }
                }

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
                assertEquals("the refused write must never reach the binder a second time",
                    1, fake.transactCount.get())
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

    private companion object {
        /** Hang-guard: no test in this class may block the suite forever. */
        const val TEST_TIMEOUT_MS = 10_000L
    }
}
