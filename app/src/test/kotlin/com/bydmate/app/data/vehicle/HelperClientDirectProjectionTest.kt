package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientDirectProjectionTest {

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
    private fun fakeWithStatus(
        status: Int,
        capture: (Parcel) -> Unit = {},
        seenCode: (Int) -> Unit = {},
    ): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            seenCode(code)
            data.setDataPosition(0)
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
            capture(data)
            reply!!.writeInt(status); reply.writeInt(0)
            reply.setDataPosition(0)
            return true
        }
    }

    @Test
    fun `launchFreeform marshals args in order and maps status 0 to OK`() = runBlocking {
        var code = -1; var pkg: String? = null; val nums = IntArray(5)
        val result = clientWith(fakeWithStatus(0, capture = {
            pkg = it.readString(); for (i in 0..4) nums[i] = it.readInt()
        }, seenCode = { code = it })).launchFreeform("ru.yandex.yandexnavi", 4, 0, 38, 1280, 441)
        assertEquals(FreeformLaunchResult.OK, result)
        assertEquals(HelperBinderProtocol.TX_LAUNCH_FREEFORM, code)
        assertEquals("ru.yandex.yandexnavi", pkg)
        assertArrayEquals(intArrayOf(4, 0, 38, 1280, 441), nums)
    }

    @Test
    fun `launchFreeform maps -2 to UNAVAILABLE and other failures to FAILED`() = runBlocking {
        assertEquals(FreeformLaunchResult.UNAVAILABLE,
            clientWith(fakeWithStatus(-2)).launchFreeform("ru.yandex.yandexnavi", 4, 0, 0, 10, 10))
        assertEquals(FreeformLaunchResult.FAILED,
            clientWith(fakeWithStatus(-1)).launchFreeform("ru.yandex.yandexnavi", 4, 0, 0, 10, 10))
        assertEquals(FreeformLaunchResult.FAILED,
            clientWith(null).launchFreeform("ru.yandex.yandexnavi", 4, 0, 0, 10, 10))
    }

    @Test
    fun `setDisplayDensity marshals displayId and density and maps status`() = runBlocking {
        var code = -1; var disp = -1; var dens = -1
        val ok = clientWith(fakeWithStatus(0, capture = {
            disp = it.readInt(); dens = it.readInt()
        }, seenCode = { code = it })).setDisplayDensity(4, 230)
        assertTrue(ok)
        assertEquals(HelperBinderProtocol.TX_SET_DISPLAY_DENSITY, code)
        assertEquals(4, disp); assertEquals(230, dens)
        assertFalse(clientWith(fakeWithStatus(-1)).setDisplayDensity(4, 0))
    }
}
