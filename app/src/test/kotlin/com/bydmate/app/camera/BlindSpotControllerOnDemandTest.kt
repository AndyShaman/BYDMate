package com.bydmate.app.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
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
 * The camera is opened on demand: only when a turn signal asks for a view, never ahead of it at
 * speed, and closed again once the window has been hidden for the cool-down. A blinker pulled
 * again inside the cool-down reuses the camera that is still open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BlindSpotControllerOnDemandTest {

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
    private val fed = mutableSetOf<TextureView>()

    /** What the fake car answers on the next batch read. */
    @Volatile private var blink = BLINK_OFF
    @Volatile private var gear = GEAR_D

    @Before fun setUp() {
        prefs.edit().clear().putBoolean(BlindSpotPreferences.KEY_ENABLED, true).commit()
        android.hardware.AVMCamera.reset()
        coEvery { helper.readBatch(any()) } answers {
            listOf(0 to blink, 0 to java.lang.Float.floatToRawIntBits(40f), 0 to gear, 0 to 0, 0 to 0)
        }
        controller = BlindSpotController(
            context, BlindSpotPreferences(context), helper,
            ClusterJournal(context.getSharedPreferences("cluster_journal_test", Context.MODE_PRIVATE)),
            monitor, fidPush,
        )
        controller.start(scope)
        controller.onPollSnapshot(
            mockk<DiParsData> {
                every { speed } returns 40
                every { this@mockk.gear } returns GEAR_D
            }
        )
    }

    @After fun tearDown() {
        controller.stop()
        idle(Duration.ofMillis(10))
        scope.cancel()
    }

    private fun events() = trace.events().map { it.replace(ID, "").replace(SPACES, " ") }

    private fun cameraEvents() = events().filter { it.startsWith("camera open ") || it.startsWith("camera close") }

    private fun idle(step: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(step)
        Thread.sleep(2)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Ticks the loop, handing every attached window the surface and the frames Robolectric never draws. */
    private fun ticks(n: Int) = repeat(n) {
        textureViews().forEach { view ->
            val listener = view.surfaceTextureListener ?: return@forEach
            if (fed.add(view)) listener.onSurfaceTextureAvailable(SurfaceTexture(0), 1280, 480)
            val texture = SurfaceTexture(0)
            listener.onSurfaceTextureUpdated(texture)
            listener.onSurfaceTextureUpdated(texture)
        }
        idle(TICK)
    }

    @Suppress("UNCHECKED_CAST")
    private fun textureViews(): List<TextureView> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val roots = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>
        return roots.flatMap { findTextures(it) }
    }

    private fun findTextures(view: View): List<TextureView> = when (view) {
        is TextureView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { findTextures(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun openOnLeftSignal() {
        blink = BLINK_LEFT
        ticks(WARM_TICKS)
        assertEquals(listOf(OPEN), cameraEvents())
    }

    @Test fun `a turn signal at speed opens the camera and traces request and first frame`() {
        ticks(WARM_TICKS)
        assertEquals(emptyList<String>(), cameraEvents())

        openOnLeftSignal()
        val lines = events()
        assertTrue(lines.toString(), lines.any { it == "camera open-request side=left" })
        assertTrue(lines.toString(), lines.any { it.startsWith("camera first-frame side=left ms=") })
    }

    @Test fun `no turn signal at speed keeps the camera closed`() {
        ticks(IDLE_TICKS)
        assertEquals(emptyList<String>(), cameraEvents())
        assertEquals(0, textureViews().size)
    }

    @Test fun `signal off closes the camera only after the hide delay`() {
        openOnLeftSignal()
        blink = BLINK_OFF
        ticks(BEFORE_DELAY_TICKS)
        assertEquals(listOf(OPEN), cameraEvents())

        ticks(PAST_DELAY_TICKS)
        assertEquals(listOf(OPEN, "camera close reason=signal_off_for_10_s result=clean"), cameraEvents())
    }

    @Test fun `a quick re-signal during the hide delay reuses the open camera`() {
        openOnLeftSignal()
        blink = BLINK_OFF
        ticks(BEFORE_DELAY_TICKS / 2)
        blink = BLINK_RIGHT
        ticks(WARM_TICKS)

        assertEquals(listOf(OPEN), cameraEvents())
        assertTrue(events().toString(), "camera reuse side=right" in events())
    }

    @Test fun `reverse closes the camera at once`() {
        openOnLeftSignal()
        gear = GEAR_R
        ticks(2)
        assertEquals(listOf(OPEN, "camera close reason=reverse result=clean"), cameraEvents())
    }

    @Test fun `the factory 360 closes the camera at once`() {
        openOnLeftSignal()
        nativeCamera.value = true
        ticks(2)
        assertEquals(listOf(OPEN, "camera close reason=native_360 result=clean"), cameraEvents())
    }

    @Test fun `the switch going off closes the camera at once`() {
        openOnLeftSignal()
        BlindSpotPreferences.setEnabled(prefs, false)
        ticks(2)
        assertEquals(listOf(OPEN, "camera close reason=feature_switched_off result=clean"), cameraEvents())
    }

    private companion object {
        const val BLINK_OFF = 1
        const val BLINK_LEFT = 2
        const val BLINK_RIGHT = 4
        const val GEAR_D = 4
        const val GEAR_R = 2
        const val OPEN = "camera open ok=true id=7 indexes=2,3"
        val ID = Regex(" #\\d+")
        val SPACES = Regex(" +")
        val TICK: Duration = Duration.ofMillis(150)
        /** Ticks to attach the windows, see the surfaces, open the camera and see a frame. */
        const val WARM_TICKS = 6
        /** 12 s at speed without a signal. */
        const val IDLE_TICKS = 80
        /** 9 s: inside the 10 s hide delay. */
        const val BEFORE_DELAY_TICKS = 60
        /** Another 2 s: past it. */
        const val PAST_DELAY_TICKS = 14
    }
}
