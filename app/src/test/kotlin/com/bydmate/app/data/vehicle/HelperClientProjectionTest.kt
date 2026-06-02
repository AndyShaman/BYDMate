package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.view.Surface
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
class HelperClientProjectionTest {

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

    /** Captures the request args (after the interface token) and writes a (status,value) reply. */
    private fun capturingFake(
        status: Int,
        value: Int,
        capture: (Parcel) -> Unit = {},
    ): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.setDataPosition(0)
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
            capture(data)
            reply!!.writeInt(status); reply.writeInt(value)
            reply.setDataPosition(0)
            return true
        }
    }

    private fun makeSurface(): Surface =
        Surface::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    @Test
    fun `createVirtualDisplay sends args and returns displayId`() = runBlocking {
        var name: String? = null; var w = 0; var h = 0; var d = 0; var fl = 0
        val fake = capturingFake(status = 0, value = 2) { p ->
            name = p.readString(); w = p.readInt(); h = p.readInt(); d = p.readInt(); fl = p.readInt()
        }
        val surface = makeSurface()
        val id = clientWith(fake).createVirtualDisplay("BYDMate_Cluster_VD", 1280, 480, 320, 322, surface)
        assertEquals(2, id)
        assertEquals("BYDMate_Cluster_VD", name)
        assertEquals(1280, w); assertEquals(480, h); assertEquals(320, d); assertEquals(322, fl)
        surface.release()
    }

    @Test
    fun `createVirtualDisplay returns null on error status`() = runBlocking {
        val surface = makeSurface()
        assertNull(clientWith(capturingFake(status = -1, value = 0))
            .createVirtualDisplay("x", 1, 1, 1, 0, surface))
        surface.release()
    }

    @Test
    fun `getTaskId returns id, or null when not found`() = runBlocking {
        assertEquals(57, clientWith(capturingFake(status = 0, value = 57)).getTaskId("ru.yandex.yandexnavi"))
        assertNull(clientWith(capturingFake(status = 0, value = -1)).getTaskId("ru.yandex.yandexnavi"))
        assertNull(clientWith(capturingFake(status = -1, value = 0)).getTaskId("ru.yandex.yandexnavi"))
    }

    @Test
    fun `moveTaskToDisplay sends taskId and displayId`() = runBlocking {
        var t = 0; var disp = 0
        val ok = clientWith(capturingFake(status = 0, value = 0) { t = it.readInt(); disp = it.readInt() })
            .moveTaskToDisplay(57, 2)
        assertTrue(ok); assertEquals(57, t); assertEquals(2, disp)
    }

    @Test
    fun `setTaskBounds sends taskId then four edges in order`() = runBlocking {
        val got = IntArray(5)
        val ok = clientWith(capturingFake(status = 0, value = 0) {
            for (i in 0..4) got[i] = it.readInt()
        }).setTaskBounds(57, 1, 2, 1280, 480)
        assertTrue(ok)
        assertArrayEquals(intArrayOf(57, 1, 2, 1280, 480), got)
    }

    @Test
    fun `releaseVirtualDisplay and setFocusedTask and grantOverlay map status to boolean`() = runBlocking {
        assertTrue(clientWith(capturingFake(status = 0, value = 0)).releaseVirtualDisplay(2))
        assertFalse(clientWith(capturingFake(status = -1, value = 0)).setFocusedTask(57))
        assertTrue(clientWith(capturingFake(status = 0, value = 0)).grantOverlayPermission())
    }

    @Test
    fun `launchAndForce sends args and maps status to boolean`() = runBlocking {
        var pkg: String? = null; val nums = IntArray(3)
        val ok = clientWith(capturingFake(status = 0, value = 0) {
            pkg = it.readString(); nums[0] = it.readInt(); nums[1] = it.readInt(); nums[2] = it.readInt()
        }).launchAndForce("ru.yandex.yandexnavi", 2, 1280, 480)
        assertTrue(ok)
        assertEquals("ru.yandex.yandexnavi", pkg)
        assertArrayEquals(intArrayOf(2, 1280, 480), nums)
        assertFalse(clientWith(capturingFake(status = -1, value = 0))
            .launchAndForce("ru.yandex.yandexnavi", 2, 1280, 480))
    }
}
