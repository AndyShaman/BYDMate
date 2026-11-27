package com.bydmate.app.camera

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterJournal
import com.bydmate.app.data.camera.CameraStateMonitor
import com.bydmate.app.data.push.FidPushChannel
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.helper.push.FidPushEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/**
 * #476: before attaching blind-spot windows, missing overlay permission is requested via the
 * helper once; when overlay permission is already granted, the helper is not called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BlindSpotControllerOverlayPermissionTest {

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
        prefs.edit().clear().putBoolean(BlindSpotPreferences.KEY_ENABLED, true).commit()
        android.hardware.AVMCamera.reset()
        coEvery { helper.readBatch(any()) } answers {
            listOf(
                0 to BLINK_LEFT,
                0 to java.lang.Float.floatToRawIntBits(40f),
                0 to GEAR_D,
                0 to 0,
                0 to 0,
            )
        }
        controller = BlindSpotController(
            context,
            BlindSpotPreferences(context),
            helper,
            ClusterJournal(context.getSharedPreferences("cluster_journal_overlay_test", Context.MODE_PRIVATE)),
            monitor,
            fidPush,
        )
    }

    @After fun tearDown() {
        controller.stop()
        controller.idleWithCameraThread(Duration.ofMillis(10))
        scope.cancel()
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun armAndTick() {
        controller.start(scope)
        controller.onPollSnapshot(
            mockk<DiParsData> {
                every { speed } returns 40
                every { gear } returns GEAR_D
            }
        )
        repeat(WARM_TICKS) {
            controller.idleWithCameraThread(TICK)
        }
    }

    @Test fun `grants overlay permission once via helper when canDrawOverlays is false`() {
        ShadowSettings.setCanDrawOverlays(false)
        coEvery { helper.grantOverlayPermission() } answers {
            ShadowSettings.setCanDrawOverlays(true)
            true
        }

        armAndTick()

        coVerify(exactly = 1) { helper.grantOverlayPermission() }
    }

    @Test fun `never calls grantOverlayPermission when canDrawOverlays is already true`() {
        ShadowSettings.setCanDrawOverlays(true)

        armAndTick()

        coVerify(exactly = 0) { helper.grantOverlayPermission() }
    }

    private companion object {
        const val BLINK_LEFT = 2
        const val GEAR_D = 4
        const val WARM_TICKS = 6
        val TICK: Duration = Duration.ofMillis(150)
    }
}
