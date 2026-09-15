package com.bydmate.app.helper.push

import android.hardware.IBYDAutoListener
import android.hardware.bydauto.BYDAutoEventValue
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recorder's load-bearing rules: it registers only fids the device's own feature map knows (a
 * single unknown id makes the firmware refuse the whole array), it writes a fid again only when
 * the value changed, and it never writes the same fid more than twice a second — an hour of a
 * moving car would otherwise fill the file with repeats.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FidRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dump = """
        BYDAutoConstants.BYDAUTO_DEVICE_BODYWORK=1001
        BYDAutoConstants.BYDAUTO_DEVICE_CPUTEMPRATURE=1030
        BYDAutoConstants.BYDAUTO_DEVICE_LIGHT=1004
        Bodywork.BODYWORK_WINDOW_FL=2000001
        Bodywork.BODYWORK_WINDOW_FR=2000002
        Bodywork.BODYWORK_FRUNK=2000003
        Cpu.CPU_TEMPRATURE=3000001
        Light.LIGHT_POSITION=4000001
    """.trimIndent()

    private val realFactory = FidRecorder.deviceFactory
    private val realCatalog = FidRecorder.catalogSource
    private val realFeatures = FidRecorder.featureIdsSource
    private val realDir = FidRecorder.logDir
    private val realClock = FidRecorder.nowMs
    private val realListeners = FidRecorder.listenerFactory

    private var clock = 0L

    @Before fun setUp() {
        FidRecorder.catalogSource = { dump }
        FidRecorder.logDir = tmp.root.path
        FidRecorder.nowMs = { clock }
        // The ticker is driven by the test, so the deferred lines land where the assertions expect.
        FidRecorder.autoTick = false
    }

    @After fun tearDown() {
        FidRecorder.stop()
        FidRecorder.deviceFactory = realFactory
        FidRecorder.catalogSource = realCatalog
        FidRecorder.featureIdsSource = realFeatures
        FidRecorder.logDir = realDir
        FidRecorder.nowMs = realClock
        FidRecorder.listenerFactory = realListeners
        FidRecorder.autoTick = true
    }

    @Test fun `only fids the device feature map knows are registered`() {
        val device = start(setOf(2000001, 2000002))

        assertEquals(listOf(2000001, 2000002), device.registered)
        val row = FidRecorder.status().devices.single()
        assertEquals(2, row.registered)
        assertEquals(3, row.total)
        assertEquals(1, row.filtered)
        assertEquals(FID_REC_NO_ERROR, row.error)
    }

    @Test fun `the run writes a file with a header and one line per change`() {
        val device = start(setOf(2000001))
        val listener = device.listener!!

        listener.onDataEventChanged(2000001, value(43))
        clock = 600
        listener.onDataEventChanged(2000001, value(44))
        FidRecorder.stop()

        val lines = recordedFile().readLines()
        assertTrue(lines[0].startsWith("bydmate fid recorder versionCode="))
        assertTrue(lines[1].startsWith("devices=1001"))
        assertTrue(lines.any { it.contains("registered=1/3 filtered=2 err=-") })
        val events = lines.filter { it.contains("fid=2000001") && it.contains("int=") }
        assertEquals(2, events.size)
        assertTrue(events[0].endsWith("dev=1001 fid=2000001 Bodywork.BODYWORK_WINDOW_FL int=43 dbl=0.0"))
        assertTrue(lines.last().startsWith("stopped: manual events="))
    }

    @Test fun `a repeated value is dropped and a burst is throttled to the last value`() {
        val device = start(setOf(2000001))
        val listener = device.listener!!

        listener.onDataEventChanged(2000001, value(43))   // written at once
        clock = 100
        listener.onDataEventChanged(2000001, value(43))   // same value: nothing
        clock = 200
        listener.onDataEventChanged(2000001, value(44))   // too soon: deferred
        clock = 300
        listener.onDataEventChanged(2000001, value(45))   // still too soon: deferred
        clock = 400
        FidRecorder.tick()                                // gap not over yet
        clock = 600
        FidRecorder.tick()                                // now the last value lands
        FidRecorder.stop()

        val events = recordedFile().readLines().filter { it.contains("fid=2000001") && it.contains("int=") }
        assertEquals(listOf(43, 45), events.map { it.substringAfter("int=").substringBefore(" dbl").toInt() })
        assertEquals(4, FidRecorder.status().totalEvents)
    }

    /**
     * The first device is live while the later ones are still registering, so its events arrive
     * mid-round: they must already carry the catalog symbol, not the "?" of a counter the event
     * itself created (on-car log 2026-09-15: `dev=1005 fid=315621437 ?`).
     */
    @Test fun `an event during a later device registration carries its symbol`() {
        val bodywork = FakeDevice(setOf(2000001))
        val light = FakeDevice(setOf(4000001))
        light.onRegister = { bodywork.listener!!.onDataEventChanged(2000001, value(7)) }
        FidRecorder.deviceFactory = { className ->
            if (className == FID_PUSH_DEVICE_CLASSES[1001]) bodywork else light
        }
        FidRecorder.featureIdsSource = { dev -> if (dev == 1001) setOf(2000001) else setOf(4000001) }

        FidRecorder.start(intArrayOf(1001, 1004))
        FidRecorder.stop()

        val line = recordedFile().readLines().single { it.contains("fid=2000001") && it.contains("int=7") }
        assertTrue(line, line.contains("Bodywork.BODYWORK_WINDOW_FL"))
    }

    /** A firmware without one listener class throws an Error, not an Exception: the round must
     *  go on, and what it did register must still be undone by stop(). */
    @Test fun `a listener class missing from the firmware costs only its own device`() {
        val bodywork = FakeDevice(setOf(2000001))
        FidRecorder.deviceFactory = { bodywork }
        FidRecorder.featureIdsSource = { dev -> if (dev == 1001) setOf(2000001) else emptySet() }
        FidRecorder.listenerFactory = { dev, sink ->
            if (dev == 1004) throw NoClassDefFoundError("AbsBYDAutoLightListener")
            else recorderListenerFor(dev, sink)
        }

        val started = FidRecorder.start(intArrayOf(1001, 1004))

        assertEquals(1, started.registered)
        val rows = FidRecorder.status().devices.associateBy { it.device }
        assertEquals(1, rows[1001]!!.registered)
        assertTrue(rows[1004]!!.error, rows[1004]!!.error.contains("NoClassDefFoundError"))
        assertEquals(1, FidRecorder.stop())
    }

    /** No file, no run: a recording whose lines go nowhere could not be capped either. */
    @Test fun `a run that cannot open its file registers nothing`() {
        val device = FakeDevice(setOf(2000001))
        FidRecorder.deviceFactory = { device }
        FidRecorder.featureIdsSource = { setOf(2000001) }
        FidRecorder.logDir = File(tmp.root, "missing/deeper").path

        val started = FidRecorder.start(intArrayOf(1001))

        assertEquals(0, started.devices)
        assertEquals(0, started.registered)
        assertTrue(started.error, started.error.startsWith("file unavailable:"))
        assertTrue(device.registered.isEmpty())
        assertTrue(!FidRecorder.status().running)
    }

    @Test fun `a device without a listener class is reported instead of registered`() {
        FidRecorder.deviceFactory = { error("no device may be built for 1030") }
        FidRecorder.featureIdsSource = { null }

        val started = FidRecorder.start(intArrayOf(1030))

        assertEquals(FidRecStart(1, 0), started)
        val row = FidRecorder.status().devices.single()
        assertEquals(0, row.registered)
        assertEquals("no listener class", row.error)
    }

    @Test fun `stop unregisters every listener and keeps the file in the status`() {
        val device = start(setOf(2000001))
        val path = FidRecorder.status().filePath

        assertEquals(1, FidRecorder.stop())
        assertEquals(1, device.unregisterCalls)
        val status = FidRecorder.status()
        assertTrue(!status.running)
        assertEquals(path, status.filePath)
        assertTrue(status.fileBytes > 0)
    }

    /** Starts a run over device 1001 with [known] as both the feature map and the device's map. */
    private fun start(known: Set<Int>): FakeDevice {
        val device = FakeDevice(known)
        FidRecorder.deviceFactory = { device }
        FidRecorder.featureIdsSource = { known }
        FidRecorder.start(intArrayOf(1001))
        return device
    }

    private fun recordedFile(): File = File(FidRecorder.status().filePath)

    private fun value(intValue: Int) = BYDAutoEventValue().apply { this.intValue = intValue }

    /** Stands in for AbsBYDAutoDevice: keeps a listener map the recorder can read back. */
    private class FakeDevice(private val known: Set<Int>) {
        private val mIBYDAutoListenerMap = FakeListenerMap()
        val registered = mutableListOf<Int>()
        var listener: IBYDAutoListener? = null
        var unregisterCalls = 0

        /** Fires while THIS device registers — the seam for an event arriving mid-round. */
        var onRegister: () -> Unit = {}

        fun registerListener(listener: IBYDAutoListener, featureIds: IntArray) {
            this.listener = listener
            featureIds.filter { it in known }.forEach {
                if (mIBYDAutoListenerMap.ids.add(it)) registered += it
            }
            onRegister()
        }

        fun unregisterListener(listener: IBYDAutoListener) {
            if (this.listener === listener) this.listener = null
            unregisterCalls++
        }
    }

    private class FakeListenerMap {
        val ids = mutableSetOf<Int>()
        fun containsId(id: Int): Boolean = id in ids
    }
}
