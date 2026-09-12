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
        var code = -1; var pkg: String? = null; val nums = IntArray(6)
        val result = clientWith(fakeWithStatus(0, capture = {
            pkg = it.readString(); for (i in 0..5) nums[i] = it.readInt()
        }, seenCode = { code = it })).launchFreeform(
            "ru.yandex.yandexnavi", 4, 0, 38, 1280, 441, HelperBinderProtocol.PANE_TYPE_RECENTS,
        )
        assertEquals(FreeformLaunchResult.OK, result)
        assertEquals(HelperBinderProtocol.TX_LAUNCH_FREEFORM, code)
        assertEquals("ru.yandex.yandexnavi", pkg)
        assertArrayEquals(intArrayOf(4, 0, 38, 1280, 441, 3), nums)
    }

    /** The pane activityType is the trailing int — split panes ask for STANDARD (touch fix, 392). */
    @Test
    fun `launchFreeform writes the requested activityType after bottom`() = runBlocking {
        var pkg: String? = null; val nums = IntArray(6)
        clientWith(fakeWithStatus(0, capture = {
            pkg = it.readString(); for (i in 0..5) nums[i] = it.readInt()
        })).launchFreeform("pkg.narrow", 0, 0, 0, 640, 1200, HelperBinderProtocol.PANE_TYPE_STANDARD)
        assertEquals("pkg.narrow", pkg)
        assertArrayEquals(intArrayOf(0, 0, 0, 640, 1200, 1), nums)
    }

    @Test
    fun `launchFreeform maps -2 to UNAVAILABLE and other failures to FAILED`() = runBlocking {
        val recents = HelperBinderProtocol.PANE_TYPE_RECENTS
        assertEquals(FreeformLaunchResult.UNAVAILABLE,
            clientWith(fakeWithStatus(-2)).launchFreeform("ru.yandex.yandexnavi", 4, 0, 0, 10, 10, recents))
        assertEquals(FreeformLaunchResult.FAILED,
            clientWith(fakeWithStatus(-1)).launchFreeform("ru.yandex.yandexnavi", 4, 0, 0, 10, 10, recents))
        assertEquals(FreeformLaunchResult.FAILED,
            clientWith(null).launchFreeform("ru.yandex.yandexnavi", 4, 0, 0, 10, 10, recents))
    }

    @Test
    fun `setDisplayDensity marshals displayId and density and maps status`() = runBlocking {
        var code = -1; var disp = -1; var dens = -1
        val result = clientWith(fakeWithStatus(0, capture = {
            disp = it.readInt(); dens = it.readInt()
        }, seenCode = { code = it })).setDisplayDensity(4, 230)
        assertTrue(result.ok)
        assertEquals(HelperBinderProtocol.TX_SET_DISPLAY_DENSITY, code)
        assertEquals(4, disp); assertEquals(230, dens)
        assertFalse(clientWith(fakeWithStatus(-1)).setDisplayDensity(4, 0).ok)
    }

    /** The `wm density -d <id>` readback is the trailing string of the reply. */
    @Test
    fun `setDisplayDensity returns the trailing readback string`() = runBlocking {
        val binder = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeInt(0); reply.writeInt(0)
                reply.writeString("Physical density: 320; Override density: 160")
                reply.setDataPosition(0)
                return true
            }
        }
        assertEquals(
            "Physical density: 320; Override density: 160",
            clientWith(binder).setDisplayDensity(1, 160).readback,
        )
    }

    /** An old daemon sends the two ints only — the missing trailing field must read as empty. */
    @Test
    fun `setDisplayDensity against an old daemon reads an empty readback`() = runBlocking {
        val result = clientWith(fakeWithStatus(0)).setDisplayDensity(1, 160)
        assertTrue(result.ok)
        assertEquals("", result.readback)
    }

    @Test
    fun `clusterWmDiag splits the two reply blocks into lines`() = runBlocking {
        var code = -1; var pkg: String? = null
        val binder = object : FakeIBinder() {
            override fun transact(c: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                code = c
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                pkg = data.readString()
                reply!!.writeInt(0)
                reply.writeString("0: init=1920x1080 320dpi\n1: init=1920x720 320dpi base=1920x720 160dpi")
                reply.writeString("ru.yandex.yandexnavi/.MainActivity dpi=160 raw=\"x\"")
                reply.setDataPosition(0)
                return true
            }
        }
        val diag = clientWith(binder).clusterWmDiag("ru.yandex.yandexnavi")
        assertEquals(HelperBinderProtocol.TX_CLUSTER_WM_DIAG, code)
        assertEquals("ru.yandex.yandexnavi", pkg)
        assertEquals(2, diag!!.displays.size)
        assertEquals("1: init=1920x720 320dpi base=1920x720 160dpi", diag.displays[1])
        assertEquals(1, diag.taskConfig.size)
    }

    @Test
    fun `clusterWmDiag returns null on a failure status and on a missing daemon`() = runBlocking {
        assertEquals(null, clientWith(fakeWithStatus(-1)).clusterWmDiag("pkg"))
        assertEquals(null, clientWith(null).clusterWmDiag("pkg"))
    }
}
