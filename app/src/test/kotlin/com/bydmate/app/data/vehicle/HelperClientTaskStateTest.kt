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

/**
 * Parses TX_GET_TASK_STATE replies: backward-compat (6-int body from an old daemon) and
 * new (7-int body with displayId) paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientTaskStateTest {

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

    /** Builds a fake binder that writes the given ints as its TX_GET_TASK_STATE reply body. */
    private fun taskStateFake(vararg replyInts: Int): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.setDataPosition(0)
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
            replyInts.forEach { reply!!.writeInt(it) }
            reply!!.setDataPosition(0)
            return true
        }
    }

    @Test
    fun `getTaskState with 6-int body (old daemon) parses correctly and defaults displayId to 0`() = runBlocking {
        // status=0, taskId=42, windowingMode=5, l=1280, t=84, r=1920, b=990  (6 ints)
        val state = clientWith(taskStateFake(0, 42, 5, 1280, 84, 1920, 990))
            .getTaskState("pkg.nav")
        assertEquals(42, state?.taskId)
        assertEquals(5, state?.windowingMode)
        assertEquals(1280, state?.left)
        assertEquals(84, state?.top)
        assertEquals(1920, state?.right)
        assertEquals(990, state?.bottom)
        // Old daemon sends no 7th int → displayId must default to 0.
        assertEquals(0, state?.displayId)
    }

    @Test
    fun `getTaskState with 7-int body (new daemon) parses displayId correctly`() = runBlocking {
        // status=0, taskId=42, windowingMode=5, l=0, t=0, r=1280, b=480, displayId=2
        val state = clientWith(taskStateFake(0, 42, 5, 0, 0, 1280, 480, 2))
            .getTaskState("pkg.nav")
        assertEquals(42, state?.taskId)
        assertEquals(5, state?.windowingMode)
        assertEquals(2, state?.displayId)
    }

    @Test
    fun `getTaskState with displayId=0 in 7-int body parses as main display`() = runBlocking {
        val state = clientWith(taskStateFake(0, 7, 5, 0, 84, 1280, 990, 0))
            .getTaskState("pkg.music")
        assertEquals(0, state?.displayId)
        assertEquals(7, state?.taskId)
    }

    @Test
    fun `getTaskState returns null on non-zero status`() = runBlocking {
        assertNull(clientWith(taskStateFake(-1, 0)).getTaskState("pkg.nav"))
    }

    @Test
    fun `getTaskState returns null when binder is unreachable`() = runBlocking {
        assertNull(clientWith(null).getTaskState("pkg.nav"))
    }
}
