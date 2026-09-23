package com.bydmate.app.data.backup

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.bydmate.app.camera.BlindSpotPreferences
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.ui.widget.WidgetController
import com.bydmate.app.ui.widget.WidgetPreferences
import com.bydmate.app.voice.GigaAmModelManager
import com.bydmate.app.voice.TtsModelManager
import com.bydmate.app.voice.TtsVoiceCatalog
import com.bydmate.app.voice.online.TtsRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [PostRestoreProbes] over the real system: the same sources the features themselves read. */
class AndroidPostRestoreProbes(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val helperBootstrap: HelperBootstrap,
    private val helperClient: HelperClient,
    private val gigaAmModelManager: GigaAmModelManager,
    private val ttsModelManager: TtsModelManager,
) : PostRestoreProbes {

    override suspend fun toggles(): RestoredToggles {
        // Voice/TTS: the "voice" prefs mirror is what VoiceGate and the key service read.
        val voice = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        return RestoredToggles(
            widget = WidgetPreferences(context).isEnabled(),
            blindSpot = BlindSpotPreferences(context).enabled,
            voice = voice.getBoolean(SettingsRepository.KEY_VOICE_ENABLED, false),
            agent = settingsRepository.isAgentEnabled(),
            tts = voice.getBoolean(SettingsRepository.KEY_TTS_ENABLED, false),
            ttsOffline = voice.getString("tts_source", TtsRouter.OFFLINE) !in ONLINE_TTS_SOURCES,
            ttsVoiceId = voice.getString("tts_voice", TtsModelManager.DEFAULT_VOICE_ID)
                ?: TtsModelManager.DEFAULT_VOICE_ID,
            nativeAssistantDisabled =
                settingsRepository.getString(SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT, "") == "true",
        )
    }

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    override fun hasPermission(name: String): Boolean =
        context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    override fun asrModelReady(): Boolean = gigaAmModelManager.isReady()

    override fun ttsVoiceReady(voiceId: String): Boolean = ttsModelManager.isReady(TtsVoiceCatalog.byId(voiceId))

    override suspend fun grantOverlayViaDaemon(): Boolean =
        helperBootstrap.ensureRunning() && helperClient.grantOverlayPermission()

    override suspend fun attachWidget() = withContext(Dispatchers.Main) { WidgetController.attach(context) }

    private companion object {
        // Ids of the online backends wired into TtsRouter in VoiceModule; any other source value
        // (including a legacy one) speaks through the downloaded offline voice.
        val ONLINE_TTS_SOURCES = setOf("gemini", "minimax")
    }
}
