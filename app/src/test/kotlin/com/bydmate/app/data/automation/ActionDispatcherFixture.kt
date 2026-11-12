package com.bydmate.app.data.automation

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.telegram.TelegramReporter
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.util.AppStrings
import com.bydmate.app.voice.AudioCapture
import com.bydmate.app.voice.VoiceAutomationActions
import io.mockk.mockk

/**
 * Builds [ActionDispatcher] for tests: every collaborator defaults to a relaxed mock,
 * a test passes by name only the ones it stubs, verifies or wants real or strict.
 * One parameter per constructor argument of [ActionDispatcher], hence the long list;
 * the two `dagger.Lazy` arguments are taken as plain objects and wrapped here.
 */
@Suppress("LongParameterList")
fun testDispatcher(
    vehicleApi: VehicleApi = mockk(relaxed = true),
    helper: HelperClient = mockk(relaxed = true),
    context: Context = mockk(relaxed = true),
    voiceActions: VoiceAutomationActions = mockk(relaxed = true),
    clusterVoiceControl: ClusterVoiceControl = mockk(relaxed = true),
    audioCapture: AudioCapture = mockk(relaxed = true),
    splitSessionManager: SplitSessionManager = mockk(relaxed = true),
    appStrings: AppStrings = mockk(relaxed = true),
    telegramReporter: TelegramReporter = mockk(relaxed = true),
) = ActionDispatcher(
    vehicleApi, helper, context, dagger.Lazy { voiceActions }, clusterVoiceControl,
    audioCapture, splitSessionManager, appStrings, dagger.Lazy { telegramReporter },
)
