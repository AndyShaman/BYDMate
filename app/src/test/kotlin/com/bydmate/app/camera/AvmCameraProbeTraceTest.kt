package com.bydmate.app.camera

import android.hardware.AVMCamera
import android.view.Surface
import com.bydmate.app.diagnostics.TraceRecorder
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The camera part of the trace: when our process held the AVM camera (open, close and why) and
 * what the vendor stack told us while it did, so a drive where the car said "lane change
 * unavailable" can be laid against our own camera use on one time line.
 */
class AvmCameraProbeTraceTest {

    @get:Rule val trace = TraceRecorder()

    private val left: Surface = mockk(relaxed = true)
    private val right: Surface = mockk(relaxed = true)
    private lateinit var probe: AvmCameraProbe

    @Before fun setUp() {
        AVMCamera.reset()
        probe = AvmCameraProbe()
        assertTrue("the fake camera lookup must answer", probe.discover())
    }

    @After fun tearDown() {
        AVMCamera.reset()
    }

    /** The event lines without ids, one space between the columns. */
    private fun events() = trace.events().map { it.replace(ID, "").replace(SPACES, " ") }

    private fun openBoth() = assertTrue(probe.openWarm(mapOf(2 to left, 3 to right)))

    // --- open ---

    @Test fun `an open that works is traced with the camera id and the preview indexes`() {
        openBoth()

        assertEquals(listOf("camera open ok=true id=7 indexes=2,3"), events())
    }

    @Test fun `an open the vendor stack throws on is traced with the failure text`() {
        AVMCamera.openError = IllegalStateException("camera busy")

        assertEquals(false, probe.openWarm(mapOf(2 to left)))

        assertEquals(listOf("camera open ok=false id=7 indexes=2 error=IllegalStateException:_camera_busy"), events())
    }

    @Test fun `an open the vendor refuses to start is traced as failed, then the camera it got back is closed`() {
        AVMCamera.startResult = false

        assertEquals(false, probe.openWarm(mapOf(2 to left, 3 to right)))

        assertEquals(
            listOf(
                "camera open ok=false id=7 indexes=2,3 error=rejected_by_the_vendor_stack",
                "camera close reason=open_rejected result=clean",
            ),
            events(),
        )
    }

    @Test fun `an open without a camera id is traced as failed`() {
        val bare = AvmCameraProbe()

        assertEquals(false, bare.open(2, left))

        assertEquals(listOf("camera open ok=false id=-1 indexes=2 error=no_camera_id"), events())
    }

    // --- close ---

    @Test fun `a clean close carries the teardown reason`() {
        openBoth()
        probe.close("telemetry lost")

        assertEquals("camera close reason=telemetry_lost result=clean", events().last())
    }

    @Test fun `a close that needed the retry says so, with the first error`() {
        openBoth()
        AVMCamera.stopFailures = 1
        probe.close("reverse")

        assertEquals("camera close reason=reverse result=retry error=IllegalStateException:_stop_refused", events().last())
    }

    @Test fun `a close that failed twice is traced as an error`() {
        openBoth()
        AVMCamera.stopFailures = 2
        probe.close("reverse")

        assertEquals("camera close reason=reverse result=error error=IllegalStateException:_stop_refused", events().last())
    }

    @Test fun `closing a camera that is not open traces nothing`() {
        probe.close("disarmed")

        assertEquals(emptyList<String>(), events())
    }

    @Test fun `re-opening an open camera closes the first one with its own reason`() {
        openBoth()
        openBoth()

        assertEquals(
            listOf(
                "camera open ok=true id=7 indexes=2,3",
                "camera close reason=reopen result=clean",
                "camera open ok=true id=7 indexes=2,3",
            ),
            events(),
        )
    }

    // --- vendor events ---

    @Test fun `every known vendor event is traced by name with its arguments`() {
        openBoth()
        val camera = AVMCamera.last!!
        KNOWN.keys.forEachIndexed { i, type -> camera.fire(type, arg1 = i, arg2 = -i) }

        assertEquals(
            KNOWN.entries.mapIndexed { i, (type, name) -> "camera avm-event type=$type name=$name arg1=$i arg2=${-i}" },
            events().drop(1),
        )
    }

    @Test fun `an unknown vendor event is traced by its number only`() {
        openBoth()
        AVMCamera.last!!.fire(1004, arg1 = 3, arg2 = 4)

        assertEquals("camera avm-event type=1004 arg1=3 arg2=4", events().last())
    }

    @Test fun `the per-frame event is never traced`() {
        openBoth()
        repeat(50) { AVMCamera.last!!.fire(FRAME) }

        assertEquals(listOf("camera open ok=true id=7 indexes=2,3"), events())
    }

    @Test fun `a burst of one event is one line and a count, whatever its arguments`() {
        openBoth()
        val camera = AVMCamera.last!!
        repeat(5_000) {
            camera.fire(1008, arg1 = it)
            camera.fire(FRAME)
        }
        probe.close("frame stall")

        assertEquals(
            listOf(
                "camera open ok=true id=7 indexes=2,3",
                "camera avm-event type=1008 name=preempted arg1=0 arg2=0",
                "camera avm-repeat type=1008 name=preempted n=4999",
                "camera close reason=frame_stall result=clean",
            ),
            events(),
        )
    }

    @Test fun `another event ends the run and is traced in full`() {
        openBoth()
        val camera = AVMCamera.last!!
        camera.fire(1000)
        camera.fire(1000)
        camera.fire(1009)
        camera.fire(1000)

        assertEquals(
            listOf(
                "camera avm-event type=1000 name=error arg1=0 arg2=0",
                "camera avm-repeat type=1000 name=error n=1",
                "camera avm-event type=1009 name=device-free arg1=0 arg2=0",
                "camera avm-event type=1000 name=error arg1=0 arg2=0",
            ),
            events().drop(1),
        )
    }

    @Test fun `a new open session traces its first event again`() {
        openBoth()
        AVMCamera.last!!.fire(1003)
        probe.close("cold for 10 s")
        openBoth()
        AVMCamera.last!!.fire(1003)

        assertEquals(
            listOf(
                "camera open ok=true id=7 indexes=2,3",
                "camera avm-event type=1003 name=first-frame arg1=0 arg2=0",
                "camera close reason=cold_for_10_s result=clean",
                "camera open ok=true id=7 indexes=2,3",
                "camera avm-event type=1003 name=first-frame arg1=0 arg2=0",
            ),
            events(),
        )
    }

    // --- dump ---

    @Test fun `camera log lines carry the wall-clock time of day`() {
        val at = 1_790_000_123_456L
        val stamped = AvmCameraProbe(clock = { at })

        stamped.append("closed cleanly")

        val expected = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(at))
        assertEquals("$expected closed cleanly", stamped.log.value.single())
    }

    private companion object {
        val ID = Regex(" #\\d+")
        val SPACES = Regex(" +")
        const val FRAME = 1001
        val KNOWN = linkedMapOf(
            1000 to "error",
            1002 to "server-died",
            1003 to "first-frame",
            1005 to "gralloc-bad-buffer",
            1006 to "no-camera-permission",
            1007 to "dequeue-input-fail",
            1008 to "preempted",
            1009 to "device-free",
            1010 to "hal-died",
        )
    }
}
