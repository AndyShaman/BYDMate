package com.bydmate.app.helper.push

import android.hardware.IBYDAutoListener
import android.hardware.bydauto.BYDAutoEventValue
import android.os.IBinder
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the two properties the firmware forces on us: vendor calls never run under the registry's
 * own lock (AbsBYDAutoDevice dispatches events under its listener-map monitor and calls back into
 * us), and registerListener is believed only after the device's listener map confirms the fid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FidPushRegistryTest {

    private val turnSignal = FidPushSub(950009900, 1004)
    private val gear = FidPushSub(555745336, 1011)

    private val realFactory = FidPushRegistry.deviceFactory
    private val realListeners = FidPushRegistry.listenerFactory
    private val realClock = FidPushRegistry.clock

    /** Elapsed time the registry reads; the tests move it by hand. */
    private var now = 0L

    @Before fun freezeClock() {
        FidPushRegistry.autoFlush = false
        FidPushRegistry.clock = { now }
    }

    @After fun tearDown() {
        FidPushRegistry.unsubscribe()
        FidPushRegistry.deviceFactory = realFactory
        FidPushRegistry.listenerFactory = realListeners
        FidPushRegistry.clock = realClock
        FidPushRegistry.autoFlush = true
    }

    @Test fun `vendor register and unregister run with the registry lock free`() {
        val device = MappedDevice(setOf(turnSignal.fid, gear.fid))
        FidPushRegistry.deviceFactory = { device }

        FidPushRegistry.subscribe(listOf(turnSignal, gear), mockk<IBinder>(relaxed = true), 2000)
        FidPushRegistry.unsubscribe()

        assertTrue("a vendor call was made while holding the lock", device.lockFree.isNotEmpty())
        assertEquals(List(device.lockFree.size) { true }, device.lockFree)
        assertEquals(2, device.unregisterCalls)
    }

    @Test fun `a fid outside the device feature map is reported instead of a false OK`() {
        val device = MappedDevice(setOf(turnSignal.fid))
        FidPushRegistry.deviceFactory = { device }

        val table = FidPushRegistry.subscribe(
            listOf(turnSignal, gear),
            mockk<IBinder>(relaxed = true),
            2000,
        ).associateBy { it.fid }

        assertEquals(FID_PUSH_OK, table.getValue(turnSignal.fid).outcome)
        assertEquals(FID_PUSH_NOT_IN_FEATURE_MAP, table.getValue(gear.fid).outcome)
    }

    @Test fun `without a readback the fid is sent and its first event confirms it`() {
        val device = OpaqueDevice()
        FidPushRegistry.deviceFactory = { device }

        val table = FidPushRegistry.subscribe(listOf(turnSignal), mockk<IBinder>(relaxed = true), 2000)
        assertEquals(FID_PUSH_SENT, table.single().outcome)
        assertEquals(FID_PUSH_SENT, FidPushRegistry.status().rows.single().outcome)

        device.listener!!.onDataEventChanged(turnSignal.fid, BYDAutoEventValue().apply { intValue = 1 })

        val row = FidPushRegistry.status().rows.single()
        assertEquals(FID_PUSH_OK, row.outcome)
        assertEquals(1, row.events)
    }

    @Test fun `a flush carries the last value of every fid that moved`() {
        val device = OpaqueDevice()
        FidPushRegistry.deviceFactory = { device }
        val sent = mutableListOf<List<FidPushEvent>>()
        FidPushRegistry.subscribe(listOf(turnSignal, gear), recordingBinder(sent), 2000)

        repeat(5) { device.fire(turnSignal.fid, it + 1) }
        device.fire(gear.fid, 4)
        assertEquals("nothing may go out before the flush", 0, sent.size)

        FidPushRegistry.flush()

        val packet = sent.single()
        assertEquals(listOf(turnSignal.fid, gear.fid), packet.map { it.fid })
        assertEquals(5, packet.first().intValue)
        // Every event is counted, coalesced or not.
        val status = FidPushRegistry.status()
        assertEquals(5, status.rows.first { it.fid == turnSignal.fid }.events)
        assertEquals(1, status.packets)
        assertEquals(2, status.events)
        assertEquals(4, status.coalesced)
    }

    @Test fun `an empty buffer produces no packet`() {
        val device = OpaqueDevice()
        FidPushRegistry.deviceFactory = { device }
        val sent = mutableListOf<List<FidPushEvent>>()
        FidPushRegistry.subscribe(listOf(turnSignal), recordingBinder(sent), 2000)

        FidPushRegistry.flush()

        assertEquals(0, sent.size)
        assertEquals(0, FidPushRegistry.status().packets)
    }

    @Test fun `an event after a quiet window goes out at once`() {
        val device = OpaqueDevice()
        FidPushRegistry.deviceFactory = { device }
        val sent = mutableListOf<List<FidPushEvent>>()
        FidPushRegistry.subscribe(listOf(turnSignal), recordingBinder(sent), 2000)

        now = 1_000L
        device.fire(turnSignal.fid, 2)

        assertEquals(listOf(2), sent.single().map { it.intValue })
        // The window restarts with that send: the next event waits for the flush.
        device.fire(turnSignal.fid, 4)
        assertEquals(1, sent.size)
        FidPushRegistry.flush()
        assertEquals(listOf(4), sent.last().map { it.intValue })
    }

    @Test fun `a device whose listener class is missing costs only that device`() {
        val device = OpaqueDevice()
        FidPushRegistry.deviceFactory = { device }
        // The gearbox listener is absent on this firmware: instantiating it throws an Error.
        FidPushRegistry.listenerFactory = { dev, sink ->
            if (dev == gear.device) throw NoClassDefFoundError("AbsBYDAutoGearboxListener")
            else LightPushListener(sink)
        }

        val table = FidPushRegistry.subscribe(
            listOf(turnSignal, gear),
            mockk<IBinder>(relaxed = true),
            2000,
        ).associateBy { it.fid }

        // The light device went through, and the gearbox reports the error instead of aborting.
        assertEquals(FID_PUSH_SENT, table.getValue(turnSignal.fid).outcome)
        assertTrue(
            "the failing device must report its error: ${table.getValue(gear.fid).outcome}",
            table.getValue(gear.fid).outcome.contains("NoClassDefFoundError"),
        )
        // Its listener is in the registry, so unsubscribe can still take it back off.
        assertEquals(1, FidPushRegistry.unsubscribe())
    }

    @Test fun `the flusher thread ends with the subscription and a new one starts with the next`() {
        val device = OpaqueDevice()
        FidPushRegistry.deviceFactory = { device }
        FidPushRegistry.autoFlush = true

        FidPushRegistry.subscribe(listOf(turnSignal), mockk<IBinder>(relaxed = true), 2000)
        val first = checkNotNull(FidPushRegistry.flusher) { "no flusher thread was started" }
        assertTrue(first.isAlive)

        FidPushRegistry.unsubscribe()
        first.join(FLUSHER_EXIT_MS)
        assertFalse("the flusher outlived the subscription", first.isAlive)

        FidPushRegistry.subscribe(listOf(turnSignal), mockk<IBinder>(relaxed = true), 2000)
        val second = checkNotNull(FidPushRegistry.flusher)
        assertTrue(second !== first && second.isAlive)
    }

    /** A callback binder that parses every TX_PUSH_EVENT packet into [out]. */
    private fun recordingBinder(out: MutableList<List<FidPushEvent>>): IBinder {
        val binder = mockk<IBinder>(relaxed = true)
        every {
            binder.transact(eq(HelperBinderProtocol.TX_PUSH_EVENT), any(), any(), any())
        } answers {
            val parcel = secondArg<Parcel>()
            parcel.setDataPosition(0)
            parcel.enforceInterface(HelperBinderProtocol.PUSH_CALLBACK_DESCRIPTOR)
            out += readPushEvents(parcel, receivedAtMs = 0L)
            true
        }
        return binder
    }

    /** True only when a second thread can take the registry lock right now. */
    private fun lockIsFree(): Boolean {
        var acquired = false
        val probe = Thread { synchronized(FidPushRegistry.lock) { acquired = true } }
        probe.start()
        probe.join(LOCK_PROBE_MS)
        return acquired
    }

    /** Stands in for AbsBYDAutoDevice: keeps a listener map the registry can read back. */
    private inner class MappedDevice(private val known: Set<Int>) {
        private val mIBYDAutoListenerMap = FakeListenerMap()
        val lockFree = mutableListOf<Boolean>()
        val seen = mutableSetOf<IBYDAutoListener>()
        var unregisterCalls = 0

        fun registerListener(listener: IBYDAutoListener, featureIds: IntArray) {
            lockFree += lockIsFree()
            seen += listener
            featureIds.filterTo(mIBYDAutoListenerMap.ids) { it in known }
        }

        fun unregisterListener(listener: IBYDAutoListener) {
            lockFree += lockIsFree()
            seen -= listener
            unregisterCalls++
        }
    }

    /** A firmware whose listener map we cannot read: registration can only be reported as sent. */
    private inner class OpaqueDevice {
        var listener: IBYDAutoListener? = null
        var fids: List<Int> = emptyList()

        /** One vendor event, as AbsBYDAutoDevice would dispatch it. */
        fun fire(fid: Int, value: Int) {
            listener!!.onDataEventChanged(fid, BYDAutoEventValue().apply { intValue = value })
        }

        fun registerListener(listener: IBYDAutoListener, featureIds: IntArray) {
            this.listener = listener
            fids = fids + featureIds.toList()
        }

        fun unregisterListener(listener: IBYDAutoListener) {
            if (this.listener === listener) this.listener = null
        }
    }

    private class FakeListenerMap {
        val ids = mutableSetOf<Int>()
        fun containsId(id: Int): Boolean = id in ids
    }

    private companion object {
        const val LOCK_PROBE_MS = 1000L

        /** Generous room for the flusher to notice the subscription is gone (it ticks at 200 ms). */
        const val FLUSHER_EXIT_MS = 5_000L
    }
}
