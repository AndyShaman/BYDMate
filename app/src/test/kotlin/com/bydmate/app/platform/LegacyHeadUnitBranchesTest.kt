package com.bydmate.app.platform

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.NativeAssistant
import com.bydmate.app.di.VoiceModule
import com.bydmate.app.voice.AsrLoadGuard
import com.bydmate.app.voice.GigaAmModelManager
import com.bydmate.app.voice.SherpaTtsEngine
import com.bydmate.app.voice.TtsModelManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The Android 10 changes of PR #261 as the production wiring picks them, on the real SDK level:
 * Android 12/13 head units (DiLink 5.0/5.1) must run exactly what they ran before the change.
 * The behaviour of each branch is pinned in the component tests; this pins which branch runs.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyHeadUnitBranchesTest {

    private fun productionAsr() = VoiceModule.provideContinuousAsr(
        mockk<GigaAmModelManager>(relaxed = true).also { every { it.isReady() } returns true },
        AsrLoadGuard(ApplicationProvider.getApplicationContext<android.content.Context>()),
    )

    private fun trackRoute(): String {
        val engine = SherpaTtsEngine(mockk<TtsModelManager>(relaxed = true))
        engine.ensureTrackForRate(SAMPLE_RATE)
        return ShadowLog.getLogsForTag("SherpaTtsEngine").map { it.msg }.single { it.startsWith("track created") }
    }

    private fun packageManagerWithVrassistant(): PackageManager = mockk<PackageManager>().also {
        every { it.getApplicationEnabledSetting(NativeAssistant.VRASSISTANT) } returns
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }

    // --- Items 4 and 5: warm VAD, warm before listening ---

    @Config(sdk = [32, 33])
    @Test fun `not android 10 - the recognizer builds no vad ahead and listening needs no warm-up`() {
        val asr = productionAsr()

        assertFalse(asr.requiresWarmBeforeListening())
        assertTrue(asr.isWarm())
    }

    @Config(sdk = [29])
    @Test fun `android 10 - the recognizer is warmed before listening`() {
        val asr = productionAsr()

        assertTrue(asr.requiresWarmBeforeListening())
        assertFalse(asr.isWarm())
    }

    // --- Item 6: the assistant's voice stream ---

    @Config(sdk = [32, 33])
    @Test fun `not android 10 - the assistant's voice keeps the voice stream 17`() {
        assertEquals("track created: rate=$SAMPLE_RATE state=1 stream=17 viaFallback=false", trackRoute())
    }

    @Config(sdk = [29])
    @Test fun `android 10 - the assistant's voice goes to the navigation stream 14`() {
        assertEquals("track created: rate=$SAMPLE_RATE state=1 stream=14 viaFallback=false", trackRoute())
    }

    // --- Item 7: vrassistant ---

    @Config(sdk = [32, 33])
    @Test fun `not android 10 - vrassistant is left alone even when installed`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val pm = packageManagerWithVrassistant()

        NativeAssistant.setDisabled(helper, pm, disabled = true)
        NativeAssistant.setDisabled(helper, pm, disabled = false)

        coVerify(exactly = 1) { helper.setAppHidden(NativeAssistant.AUTOVOICE, true) }
        coVerify(exactly = 1) { helper.setAppHidden(NativeAssistant.AUTOVOICE, false) }
        coVerify(exactly = 0) { helper.setAppHidden(NativeAssistant.VRASSISTANT, any()) }
    }

    @Config(sdk = [32, 33])
    @Test fun `not android 10 - a disabled vrassistant does not turn the toggle on`() {
        val pm = mockk<PackageManager>().also {
            every { it.getApplicationEnabledSetting(NativeAssistant.AUTOVOICE) } returns
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            every { it.getApplicationEnabledSetting(NativeAssistant.VRASSISTANT) } returns
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }

        assertFalse(NativeAssistant.disabledInSystem(pm))
    }

    @Config(sdk = [29])
    @Test fun `android 10 - an installed vrassistant follows the toggle`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)

        NativeAssistant.setDisabled(helper, packageManagerWithVrassistant(), disabled = true)

        coVerify(exactly = 1) { helper.setAppHidden(NativeAssistant.VRASSISTANT, true) }
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
