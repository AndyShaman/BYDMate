package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientBatchTest {

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

    private fun clientWith(binder: IBinder?): HelperClientImpl = object : HelperClientImpl() {
        override fun resolveBinder(): IBinder? = binder
    }

    /** Fake daemon replying `pairs` (prefixed with the count) for TX_READ_BATCH. */
    private fun batchFake(vararg pairs: Pair<Int, Int>): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply!!.writeInt(pairs.size)
            pairs.forEach { (s, v) -> reply.writeInt(s); reply.writeInt(v) }
            reply.setDataPosition(0)
            return true
        }
    }

    private val oldDaemonFake: IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    @Test
    fun `readBatch returns pairs in order including sentinel statuses`() = runBlocking {
        val client = clientWith(batchFake(0 to 10, 0 to 20, -10011 to 0))
        val items = listOf(BatchReadItem(5, 1, 11), BatchReadItem(7, 2, 22), BatchReadItem(5, 3, 33))
        assertEquals(listOf(0 to 10, 0 to 20, -10011 to 0), client.readBatch(items))
    }

    @Test
    fun `readBatch returns null when old daemon rejects the code`() = runBlocking {
        val client = clientWith(oldDaemonFake)
        assertNull(client.readBatch(listOf(BatchReadItem(5, 1, 11))))
    }

    @Test
    fun `readBatch returns null on reply count mismatch`() = runBlocking {
        val client = clientWith(batchFake(0 to 10))
        assertNull(client.readBatch(listOf(BatchReadItem(5, 1, 11), BatchReadItem(5, 2, 22))))
    }

    @Test
    fun `readBatch returns null on empty list without touching the binder`() = runBlocking {
        val client = clientWith(oldDaemonFake)
        assertNull(client.readBatch(emptyList()))
    }

    @Test
    fun `readBatch returns null when list exceeds cap`() = runBlocking {
        val client = clientWith(batchFake())
        val items = List(HelperBinderProtocol.MAX_BATCH_ITEMS + 1) { BatchReadItem(5, it, it) }
        assertNull(client.readBatch(items))
    }
}
