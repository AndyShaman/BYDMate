package com.bydmate.app.data.vehicle

import android.graphics.Rect
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wire tests of the six TX_SPLIT37_* verbs: request layout and reply parsing against a fake
 * IBinder. Every non-OK status and every transport failure must collapse into null/false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientSplit37Test {

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

    /** Fake binder replying with [replyInts]; the request args land in [captured] when given. */
    private fun replyFake(vararg replyInts: Int, captured: MutableList<Int>? = null): IBinder =
        object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                while (captured != null && data.dataAvail() >= 4) captured += data.readInt()
                replyInts.forEach { reply!!.writeInt(it) }
                reply!!.setDataPosition(0)
                return true
            }
        }

    /** Binder that rejects the transaction, as an old daemon does with an unknown verb. */
    private val rejectingFake: IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    /** Body of a full TX_SPLIT37_AREA_INFO reply: status, areaMode and three (id, l, t, r, b) blocks. */
    private fun areaInfoReply(status: Int = HelperBinderProtocol.SPLIT37_OK, areaMode: Int = 3): IntArray =
        intArrayOf(
            status, areaMode,
            11, 0, 84, 576, 990,        // narrow (area 1)
            12, 576, 84, 1920, 990,     // wide (area 2)
            13, 0, 0, 1920, 1080,       // full (area 4)
        )

    // --- split37Enter ---

    @Test
    fun `split37Enter returns the area mode read after the call`() = runBlocking {
        val mode = clientWith(replyFake(HelperBinderProtocol.SPLIT37_OK, 3)).split37Enter()
        assertEquals(3, mode)
    }

    @Test
    fun `split37Enter returns null on a non-OK status`() = runBlocking {
        assertNull(clientWith(replyFake(HelperBinderProtocol.SPLIT37_FAILED, 4)).split37Enter())
        assertNull(clientWith(replyFake(HelperBinderProtocol.SPLIT37_UNSUPPORTED, -1)).split37Enter())
    }

    @Test
    fun `split37Enter returns null on a truncated reply`() = runBlocking {
        assertNull(clientWith(replyFake(HelperBinderProtocol.SPLIT37_OK)).split37Enter())
    }

    @Test
    fun `split37Enter returns null when the daemon rejects the verb`() = runBlocking {
        assertNull(clientWith(rejectingFake).split37Enter())
    }

    // --- split37AreaInfo ---

    @Test
    fun `split37AreaInfo parses area mode and the three roots in order`() = runBlocking {
        val info = clientWith(replyFake(*areaInfoReply())).split37AreaInfo()
        assertEquals(3, info?.areaMode)
        assertEquals(Split37Root(11, 0, 84, 576, 990), info?.narrow)
        assertEquals(Split37Root(12, 576, 84, 1920, 990), info?.wide)
        assertEquals(Split37Root(13, 0, 0, 1920, 1080), info?.full)
    }

    @Test
    fun `split37AreaInfo reports a root with id -1 as an absent pane`() = runBlocking {
        val body = areaInfoReply()
        body[2] = -1                        // narrow rootTaskId
        val info = clientWith(replyFake(*body)).split37AreaInfo()
        assertNull("a root the firmware did not report must be null", info?.narrow)
        assertEquals("the following blocks must still line up", 12, info?.wide?.rootTaskId)
        assertEquals(13, info?.full?.rootTaskId)
    }

    @Test
    fun `split37AreaInfo returns null on a non-OK status even with a full body`() = runBlocking {
        assertNull(
            clientWith(replyFake(*areaInfoReply(status = HelperBinderProtocol.SPLIT37_FAILED, areaMode = -1)))
                .split37AreaInfo(),
        )
        assertNull(
            clientWith(replyFake(*areaInfoReply(status = HelperBinderProtocol.SPLIT37_UNSUPPORTED, areaMode = -1)))
                .split37AreaInfo(),
        )
    }

    @Test
    fun `split37AreaInfo returns null on a truncated reply`() = runBlocking {
        // OK status and area mode, then only two of the three root blocks.
        val short = areaInfoReply().copyOf(12)
        assertNull(clientWith(replyFake(*short)).split37AreaInfo())
        assertNull(clientWith(replyFake(HelperBinderProtocol.SPLIT37_OK)).split37AreaInfo())
    }

    @Test
    fun `split37AreaInfo returns null when the daemon rejects the verb`() = runBlocking {
        assertNull(clientWith(rejectingFake).split37AreaInfo())
    }

    // --- split37MoveTask ---

    @Test
    fun `split37MoveTask writes task, root, bounds and toTop in that order`() = runBlocking {
        val captured = mutableListOf<Int>()
        val ok = clientWith(replyFake(HelperBinderProtocol.SPLIT37_OK, 0, captured = captured))
            .split37MoveTask(42, 7, Rect(0, 84, 576, 990))
        assertTrue(ok)
        assertEquals(listOf(42, 7, 0, 84, 576, 990, 1), captured)
    }

    @Test
    fun `split37MoveTask writes a null rect as an empty one`() = runBlocking {
        val captured = mutableListOf<Int>()
        clientWith(replyFake(HelperBinderProtocol.SPLIT37_OK, 0, captured = captured))
            .split37MoveTask(42, 7, null)
        assertEquals(listOf(42, 7, 0, 0, 0, 0, 1), captured)
    }

    @Test
    fun `split37MoveTask writes toTop false as a trailing zero`() = runBlocking {
        // The bounce half of a re-placement: an old daemon never reads this int and keeps
        // moving tasks on top, which is the pre-bounce behavior.
        val captured = mutableListOf<Int>()
        clientWith(replyFake(HelperBinderProtocol.SPLIT37_OK, 0, captured = captured))
            .split37MoveTask(42, 7, null, toTop = false)
        assertEquals(listOf(42, 7, 0, 0, 0, 0, 0), captured)
    }

    @Test
    fun `split37MoveTask is false on a non-OK status and on a rejected transact`() = runBlocking {
        assertFalse(
            clientWith(replyFake(HelperBinderProtocol.SPLIT37_FAILED, 0)).split37MoveTask(42, 7, null),
        )
        assertFalse(
            clientWith(replyFake(HelperBinderProtocol.SPLIT37_UNSUPPORTED, 0)).split37MoveTask(42, 7, null),
        )
        assertFalse(clientWith(rejectingFake).split37MoveTask(42, 7, null))
    }

    // --- split37TaskArea ---

    @Test
    fun `split37TaskArea returns the area id and passes the task id`() = runBlocking {
        val captured = mutableListOf<Int>()
        val area = clientWith(
            replyFake(HelperBinderProtocol.SPLIT37_OK, HelperBinderProtocol.SPLIT37_AREA_WIDE, captured = captured),
        ).split37TaskArea(42)
        assertEquals(HelperBinderProtocol.SPLIT37_AREA_WIDE, area)
        assertEquals(listOf(42), captured)
    }

    @Test
    fun `split37TaskArea returns null on a non-OK status and on a rejected transact`() = runBlocking {
        assertNull(clientWith(replyFake(HelperBinderProtocol.SPLIT37_FAILED, -1)).split37TaskArea(42))
        assertNull(clientWith(replyFake(HelperBinderProtocol.SPLIT37_UNSUPPORTED, -1)).split37TaskArea(42))
        assertNull(clientWith(rejectingFake).split37TaskArea(42))
    }

    // --- split37Swap ---

    @Test
    fun `split37Swap is true only on an OK status`() = runBlocking {
        assertTrue(clientWith(replyFake(HelperBinderProtocol.SPLIT37_OK, 0)).split37Swap())
        assertFalse(clientWith(replyFake(HelperBinderProtocol.SPLIT37_FAILED, 0)).split37Swap())
        assertFalse(clientWith(replyFake(HelperBinderProtocol.SPLIT37_UNSUPPORTED, 0)).split37Swap())
        assertFalse(clientWith(rejectingFake).split37Swap())
    }

    // --- split37ChangeMode ---

    @Test
    fun `split37ChangeMode passes the mode and returns the area mode read after the call`() =
        runBlocking {
            val captured = mutableListOf<Int>()
            val area = clientWith(replyFake(HelperBinderProtocol.SPLIT37_OK, 2, captured = captured))
                .split37ChangeMode(102)
            assertEquals(2, area)
            assertEquals(listOf(102), captured)
        }

    @Test
    fun `split37ChangeMode returns null on a non-OK status, a truncated reply and a rejected transact`() =
        runBlocking {
            assertNull(clientWith(replyFake(HelperBinderProtocol.SPLIT37_FAILED, -1)).split37ChangeMode(102))
            assertNull(
                clientWith(replyFake(HelperBinderProtocol.SPLIT37_UNSUPPORTED, -1)).split37ChangeMode(102),
            )
            assertNull(clientWith(replyFake(HelperBinderProtocol.SPLIT37_OK)).split37ChangeMode(102))
            // An old daemon does not know the verb at all.
            assertNull(clientWith(rejectingFake).split37ChangeMode(102))
        }
}
