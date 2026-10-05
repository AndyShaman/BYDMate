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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The factory 360 view against our warm camera (tester dump 2026-10-03, fixture
 * blindspot-native360-xp-20261003.txt): the camera was opened 0.2 s after the 360 came up and kept
 * streaming two previews under it, and the 360 stuttered. While the 360 is in the foreground the
 * camera is closed and stays closed; once it is gone the camera warms again by the usual rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BlindSpotControllerNative360Test {

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
    /** Windows already handed a surface; a re-attach after a teardown brings new ones. */
    private val fed = mutableSetOf<TextureView>()

    @Before fun setUp() {
        prefs.edit().clear().putBoolean(BlindSpotPreferences.KEY_ENABLED, true).commit()
        android.hardware.AVMCamera.reset()
        coEvery { helper.readBatch(any()) } answers {
            // Blinker off, 40 km/h, D: inside the warm band, nothing to show.
            listOf(0 to 1, 0 to java.lang.Float.floatToRawIntBits(40f), 0 to 4, 0 to 0, 0 to 0)
        }
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

    private fun cameraEvents() = events().filter { it.startsWith("camera open") || it.startsWith("camera close") }

    private fun arm() {
        controller.start(scope)
        controller.onPollSnapshot(
            mockk<DiParsData> {
                every { speed } returns 40
                every { gear } returns 4
            }
        )
    }

    private fun idle(step: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(step)
        // The vendor calls run on the camera thread; give it a moment to post back to Main.
        Thread.sleep(2)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Ticks the loop, handing every attached window the surface and the frames Robolectric never draws. */
    private fun ticks(n: Int) = repeat(n) {
        textureViews().forEach { view ->
            val listener = view.surfaceTextureListener ?: return@forEach
            if (fed.add(view)) {
                listener.onSurfaceTextureAvailable(SurfaceTexture(0), 1280, 480)
            }
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

    /** The native-360 transitions of the fixture's trace interval, in the order the car sent them. */
    private fun fixtureNative360Order(): List<Boolean> =
        requireNotNull(javaClass.classLoader?.getResource(FIXTURE)).readText().lines()
            .filter { !it.startsWith("#") && !it.contains("I/") && it.contains("camera native-360 on=") }
            .map { it.substringAfter("on=").substringBefore(' ').toBoolean() }

    @Test fun `the factory 360 closes the warm camera and keeps it closed until it is gone`() {
        val order = fixtureNative360Order()
        assertEquals(listOf(true, false), order)
        arm()
        ticks(WARM_TICKS)
        assertEquals(listOf("camera open ok=true id=7 indexes=2,3"), cameraEvents())

        nativeCamera.value = order[0]
        ticks(HELD_TICKS)
        assertEquals(
            listOf("camera open ok=true id=7 indexes=2,3", "camera close reason=native_360 result=clean"),
            cameraEvents(),
        )

        nativeCamera.value = order[1]
        ticks(WARM_TICKS)
        assertEquals(
            listOf(
                "camera open ok=true id=7 indexes=2,3",
                "camera close reason=native_360 result=clean",
                "camera open ok=true id=7 indexes=2,3",
            ),
            cameraEvents(),
        )
    }

    /** The fixture's own order: armed while the 360 is already up. Nothing opens until it goes. */
    @Test fun `armed under the factory 360 the camera waits for it to go`() {
        nativeCamera.value = true
        arm()
        ticks(HELD_TICKS)
        assertEquals(emptyList<String>(), cameraEvents())

        nativeCamera.value = false
        ticks(WARM_TICKS)
        assertEquals(listOf("camera open ok=true id=7 indexes=2,3"), cameraEvents())
    }

    private companion object {
        const val FIXTURE = "native-stack-fixtures/blindspot-native360-xp-20261003.txt"
        val ID = Regex(" #\\d+")
        val SPACES = Regex(" +")
        val TICK: Duration = Duration.ofMillis(150)
        /** Ticks to attach the windows, see the surfaces and open the camera. */
        const val WARM_TICKS = 6
        /** 6 s of the 360 held up, well past the old instant reopen. */
        const val HELD_TICKS = 40
    }
}
