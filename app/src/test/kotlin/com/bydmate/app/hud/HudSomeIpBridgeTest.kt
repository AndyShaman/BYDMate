package com.bydmate.app.hud

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HudSomeIpBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun installSomeIpPackage() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = PKG })
    }

    @Test fun `probe negative on clean device`() {
        assertFalse(HudSomeIpBridge.isServicePresent(context.packageManager))
    }

    @Test fun `probe positive when gateway package installed`() {
        installSomeIpPackage()
        assertTrue(HudSomeIpBridge.isServicePresent(context.packageManager))
    }

    @Test fun `calls fail soft when unbound`() {
        val bridge = HudSomeIpBridge(context)
        assertEquals(-1, bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, byteArrayOf(1)))
        assertEquals(-1, bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI))
        assertEquals(-1, bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI))
        bridge.unbind()   // must not throw
    }

    @Test fun `bind gives up when gateway never connects`() = runTest {
        val bridge = HudSomeIpBridge(context)
        assertFalse(bridge.bind())   // virtual time: 4 attempts x 15 s pass instantly
        bridge.unbind()
    }

    @Test fun `first bind carries the donor component and the type`() = runTest {
        val ctx = RecordingContext(context)
        HudSomeIpBridge(ctx).bind()
        val first = ctx.intents.first()
        assertEquals(PKG, first.component?.packageName)
        assertEquals(DONOR_CLASS, first.component?.className)
        assertEquals(context.packageName, first.type)
    }

    @Test fun `refused bind retries the component the package manager resolves`() = runTest {
        val ctx = RecordingContext(context, bindResult = { false }, resolvedClass = OTHER_CLASS)
        assertFalse(HudSomeIpBridge(ctx).bind())
        assertEquals(DONOR_CLASS, ctx.intents[0].component?.className)
        assertEquals(OTHER_CLASS, ctx.intents[1].component?.className)
        assertEquals(context.packageName, ctx.intents[1].type)
        assertEquals(8, ctx.intents.size)   // two candidates on each of the four attempts
        assertEquals(8, ctx.unbinds)        // and every one of them released again
    }

    @Test fun `connect timeout falls back within the same attempt`() = runTest {
        // bindService succeeds but the gateway never hands back a binder.
        val ctx = RecordingContext(context, resolvedClass = OTHER_CLASS)
        assertFalse(HudSomeIpBridge(ctx).bind())
        assertEquals(OTHER_CLASS, ctx.intents[1].component?.className)
        assertEquals(8, ctx.intents.size)
        assertEquals(8, ctx.unbinds)
    }

    @OptIn(ExperimentalCoroutinesApi::class)   // testScheduler.currentTime
    @Test fun `null binding aborts the wait instead of burning the timeout`() = runTest {
        val ctx = RecordingContext(context,
            onBind = { conn -> conn.onNullBinding(ComponentName(PKG, DONOR_CLASS)) })
        assertFalse(HudSomeIpBridge(ctx).bind())
        assertEquals(4, ctx.intents.size)
        // Retry backoff only (0 + 1 + 3 + 7 s): not one of the four 15 s connect waits ran.
        assertEquals(11_000L, testScheduler.currentTime)
    }

    @Test fun `resolved component equal to the donor class is not bound twice`() = runTest {
        val ctx = RecordingContext(context, bindResult = { false }, resolvedClass = DONOR_CLASS)
        assertFalse(HudSomeIpBridge(ctx).bind())
        assertEquals(4, ctx.intents.size)   // one candidate per attempt
    }

    @Test fun `every bind intent keeps the action and adds a component and a type`() = runTest {
        val ctx = RecordingContext(context, bindResult = { false }, resolvedClass = OTHER_CLASS)
        HudSomeIpBridge(ctx).bind()
        assertTrue(ctx.intents.isNotEmpty())
        ctx.intents.forEach {
            // The action is what the old, field-proven bind carried; a typeless bind is
            // what kills the gateway process on unbind.
            assertEquals(ACTION, it.action)
            assertNotNull(it.component)
            assertNotNull(it.type)
        }
    }

    @Test fun `a binder from a detached candidate is not accepted`() = runTest {
        val stale = mockk<IBinder>(relaxed = true)
        val conns = mutableListOf<ServiceConnection>()
        val ctx = RecordingContext(context, onBind = { conn ->
            conns.add(conn)
            // The candidate that already timed out wakes up during the next one's wait.
            if (conns.size >= 2) conns[0].onServiceConnected(COMPONENT, stale)
        })
        assertFalse(HudSomeIpBridge(ctx).bind())
        verify(exactly = 0) { stale.transact(any(), any(), any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)   // testScheduler.currentTime
    @Test fun `a null binding from a detached candidate does not cut the active wait`() = runTest {
        val conns = mutableListOf<ServiceConnection>()
        val ctx = RecordingContext(context, onBind = { conn ->
            conns.add(conn)
            if (conns.size >= 2) conns[0].onNullBinding(COMPONENT)
        })
        assertFalse(HudSomeIpBridge(ctx).bind())
        // 11 s of backoff plus four full 15 s waits: no wait was cut short by the noise.
        assertEquals(71_000L, testScheduler.currentTime)
    }

    @Test fun `a connect after the detach never publishes its binder`() = runTest {
        val stale = mockk<IBinder>(relaxed = true)
        val conns = mutableListOf<ServiceConnection>()
        val ctx = RecordingContext(context, onBind = { conns.add(it) })
        val bridge = HudSomeIpBridge(ctx)
        assertFalse(bridge.bind())   // every candidate times out and is unbound
        conns.first().onServiceConnected(COMPONENT, stale)
        assertEquals(-1, bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, byteArrayOf(1)))
        verify(exactly = 0) { stale.transact(any(), any(), any(), any()) }
    }

    @Test fun `a late connect cannot displace the candidate that is connected`() = runTest {
        val stale = mockk<IBinder>(relaxed = true)
        val live = mockk<IBinder>(relaxed = true)
        val conns = mutableListOf<ServiceConnection>()
        val ctx = RecordingContext(context, onBind = { conn ->
            conns.add(conn)
            if (conns.size == 2) conns[1].onServiceConnected(COMPONENT, live)
        })
        val bridge = HudSomeIpBridge(ctx)
        assertTrue(bridge.bind())
        conns[0].onServiceConnected(COMPONENT, stale)   // candidate 1 wakes up far too late
        bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, byteArrayOf(1))
        // registerCallback and the frame both went to the binder that won the slot.
        verify(atLeast = 2) { live.transact(any(), any(), any(), any()) }
        verify(exactly = 0) { stale.transact(any(), any(), any(), any()) }
    }

    @Test fun `a dead transact drops only the binder it died on`() = runTest {
        val dying = mockk<IBinder>(relaxed = true)
        val fresh = mockk<IBinder>(relaxed = true)
        val conns = mutableListOf<ServiceConnection>()
        val ctx = RecordingContext(context, onBind = { conn ->
            conns.add(conn)
            conn.onServiceConnected(COMPONENT, dying)
        })
        val bridge = HudSomeIpBridge(ctx)
        assertTrue(bridge.bind())
        // The gateway reconnects while a frame is still in flight: the reconnect publishes
        // a new binder, and only then does the call on the old one fail.
        every { dying.transact(any(), any(), any(), any()) } answers {
            conns.first().onServiceDisconnected(COMPONENT)
            conns.first().onServiceConnected(COMPONENT, fresh)
            throw DeadObjectException()
        }
        assertEquals(-2, bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, byteArrayOf(1)))
        bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, byteArrayOf(2))
        // registerCallback plus the second frame: the reconnected binder survived.
        verify(atLeast = 2) { fresh.transact(any(), any(), any(), any()) }
    }

    @Test fun `the active candidate still connects through that noise`() = runTest {
        val stale = mockk<IBinder>(relaxed = true)
        val live = mockk<IBinder>(relaxed = true)
        val conns = mutableListOf<ServiceConnection>()
        val ctx = RecordingContext(context, onBind = { conn ->
            conns.add(conn)
            if (conns.size == 2) {
                conns[0].onNullBinding(COMPONENT)
                conns[0].onServiceConnected(COMPONENT, stale)
                conns[1].onServiceConnected(COMPONENT, live)
            }
        })
        assertTrue(HudSomeIpBridge(ctx).bind())
        // registerCallback went to the live binder only.
        verify(atLeast = 1) { live.transact(any(), any(), any(), any()) }
        verify(exactly = 0) { stale.transact(any(), any(), any(), any()) }
    }

    /** Records what reaches bindService: [bindResult] answers it, [onBind] plays the
     *  gateway's callbacks, [resolvedClass] is what the package manager knows about the
     *  SOME/IP action (null = nothing resolves). */
    private class RecordingContext(
        base: Context,
        private val bindResult: (Intent) -> Boolean = { true },
        private val onBind: (ServiceConnection) -> Unit = {},
        resolvedClass: String? = null,
    ) : ContextWrapper(base) {
        val intents = mutableListOf<Intent>()
        var unbinds = 0

        private val pm = mockk<PackageManager>(relaxed = true) {
            every { resolveService(any(), any<Int>()) } returns resolvedClass?.let { cls ->
                ResolveInfo().apply {
                    serviceInfo = ServiceInfo().apply { packageName = PKG; name = cls }
                }
            }
        }

        override fun getPackageManager(): PackageManager = pm

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            intents.add(service)
            val rc = bindResult(service)
            if (rc) onBind(conn)
            return rc
        }

        override fun unbindService(conn: ServiceConnection) {
            unbinds++
        }
    }

    @Test fun `callback answers interface transaction with descriptor`() {
        val bridge = HudSomeIpBridge(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            assertTrue(bridge.callback.transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0))
            reply.setDataPosition(0)
            assertEquals("ts.car.someip.sdk.ISomeIpCallback", reply.readString())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test fun `callback event reply carries exception header and status int`() {
        val bridge = HudSomeIpBridge(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("ts.car.someip.sdk.ISomeIpCallback")
            data.writeInt(0)   // "no event" flag, mirrors the gateway ping shape
            assertTrue(bridge.callback.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
            reply.setDataPosition(0)
            reply.readException()
            assertEquals(4, reply.dataAvail())   // the AIDL int return - absent before the fix
            assertEquals(0, reply.readInt())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test fun `callback rejects unknown transaction codes`() {
        val bridge = HudSomeIpBridge(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            assertFalse(bridge.callback.transact(IBinder.FIRST_CALL_TRANSACTION + 41, data, reply, 0))
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private companion object {
        const val PKG = "com.ts.car.someip.service"
        const val ACTION = "com.ts.car.someip.SomeIpServerService"
        const val DONOR_CLASS = "com.ts.car.someip.service.manager.SomeIpServerService"
        const val OTHER_CLASS = "com.ts.car.someip.service.SomeIpServerService"
        val COMPONENT: ComponentName = ComponentName(PKG, DONOR_CLASS)
    }
}
