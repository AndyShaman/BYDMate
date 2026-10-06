package com.bydmate.app.camera

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterJournal
import com.bydmate.app.data.camera.CameraStateMonitor
import com.bydmate.app.data.push.FidPushChannel
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.helper.push.FidPushEvent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The controller's part of the camera trace: the main switch, the arming gate and the watchdog
 * that gives up on the camera. Run against a fake car on the Robolectric main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BlindSpotControllerTraceTest {

    @get:Rule val trace = TraceRecorder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(BlindSpotPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    private val helper: HelperClient = mockk(relaxed = true)
    private val nativeCamera = MutableStateFlow(false)
    private val monitor: CameraStateMonitor = mockk(relaxed = true) { every { active } returns nativeCamera }
    private val pushEvents = MutableSharedFlow<FidPushEvent>(extraBufferCapacity = 16)
    private val fidPush: FidPushChannel = mockk(relaxed = true) { every { events } returns pushEvents }
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var controller: BlindSpotController

    @Before fun setUp() {
        prefs.edit().clear().commit()
        android.hardware.AVMCamera.reset()
        controller = BlindSpotController(
            context, BlindSpotPreferences(context), helper,
            ClusterJournal(context.getSharedPreferences("cluster_journal_test", Context.MODE_PRIVATE)),
            monitor, fidPush,
        )
    }

    @After fun tearDown() {
        controller.stop()
        idle(Duration.ofMillis(10))
        scope.cancel()
    }

    private fun events() = trace.events().map { it.replace(ID, "").replace(SPACES, " ") }

    private fun poll(speed: Int?, gear: Int?) = controller.onPollSnapshot(
        mockk<DiParsData> {
            every { this@mockk.speed } returns speed
            every { this@mockk.gear } returns gear
        }
    )

    private fun idle(step: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(step)
        // The vendor calls run on the camera thread; give it a moment to post back to Main.
        Thread.sleep(2)
        shadowOf(Looper.getMainLooper()).idle()
    }

    // --- main switch ---

    @Test fun `the main switch is traced old to new`() {
        BlindSpotPreferences.setEnabled(prefs, true)
        BlindSpotPreferences.setEnabled(prefs, false)

        assertEquals(
            listOf("user toggle id=blindspot on=true was=false", "user toggle id=blindspot on=false was=true"),
            events(),
        )
        assertEquals(false, prefs.getBoolean(BlindSpotPreferences.KEY_ENABLED, true))
    }

    // --- arming gate ---

    @Test fun `each arming transition is traced once with its reason`() {
        prefs.edit().putBoolean(BlindSpotPreferences.KEY_ENABLED, true).commit()

        poll(speed = 3, gear = 4)   // below the warm band: stays disarmed, nothing to say
        poll(speed = 30, gear = 4)
        poll(speed = 40, gear = 4)  // still armed
        poll(speed = 30, gear = 2)

        assertEquals(
            listOf(
                "camera armed on=true reason=ok speed=30 gear=4",
                "camera armed on=false reason=reverse speed=30 gear=2",
            ),
            events(),
        )
    }

    @Test fun `a speed below the band is named in the disarm reason`() {
        prefs.edit().putBoolean(BlindSpotPreferences.KEY_ENABLED, true).commit()
        poll(speed = 30, gear = 4)
        poll(speed = 3, gear = 4)

        assertEquals("camera armed on=false reason=speed_3_<_15 speed=3 gear=4", events().last())
    }

    // --- watchdog ---

    @Test fun `windows whose surface never arrives are given up with the reason, and the factory view is traced`() {
        prefs.edit().putBoolean(BlindSpotPreferences.KEY_ENABLED, true).commit()
        coEvery { helper.readBatch(any()) } answers {
            // Left blinker held: the camera, and with it the windows, only come up on a signal.
            listOf(0 to 2, 0 to java.lang.Float.floatToRawIntBits(40f), 0 to 4, 0 to 0, 0 to 0)
        }
        controller.start(scope)
        poll(speed = 40, gear = 4)

        // Robolectric draws nothing, so the TextureViews never get a surface: the 8 s surface
        // watchdog is the one that fires.
        repeat(SURFACE_TICKS) { idle(TICK) }
        nativeCamera.value = true
        repeat(3) { idle(TICK) }
        nativeCamera.value = false
        repeat(3) { idle(TICK) }

        val lines = events()
        assertTrue(lines.toString(), "camera fail reason=no_surface" in lines)
        assertEquals(
            listOf("camera native-360 on=true", "camera native-360 on=false"),
            lines.filter { it.startsWith("camera native-360") },
        )
        val cameraLog = controller.dumpLines().single { it.startsWith("camera_log=") }
        assertTrue(cameraLog, cameraLog.removePrefix("camera_log=").split(" | ").all { STAMP.matches(it) })
    }

    private companion object {
        val ID = Regex(" #\\d+")
        val SPACES = Regex(" +")
        val STAMP = Regex("""\d{2}:\d{2}:\d{2}\.\d{3} .+""")
        val TICK: Duration = Duration.ofMillis(150)
        /** A little over the 8 s surface timeout in 150 ms ticks. */
        const val SURFACE_TICKS = 70
    }
}
