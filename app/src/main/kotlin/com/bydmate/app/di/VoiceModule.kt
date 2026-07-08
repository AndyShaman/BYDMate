package com.bydmate.app.di

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.TrackingService
import com.bydmate.app.voice.*
import com.bydmate.app.voice.online.MiniMaxTtsBackend
import com.bydmate.app.voice.online.OpenRouterTtsBackend
import com.bydmate.app.voice.online.TtsRouter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * Returns the resolved TTS source, migrating legacy "openai" to "offline" at the runtime read
 * seam so that a user who speaks before ever opening Settings still gets the correct source.
 * Calls [persist] with the new value when a migration is needed (idempotent: next read will
 * see the already-written "offline" and skip this branch entirely).
 */
internal fun migrateLegacyTtsSource(stored: String, persist: (String) -> Unit): String {
    if (stored == "openai") {
        persist(TtsRouter.OFFLINE)
        return TtsRouter.OFFLINE
    }
    return stored
}

@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {
    @Provides @Singleton
    fun provideVoiceModelManager(@ApplicationContext ctx: Context, http: OkHttpClient) =
        VoiceModelManager(ctx, http)

    @Provides @Singleton
    fun provideAsrEngine(mm: VoiceModelManager): AsrEngine = VoskAsrEngine(mm)

    @Provides @Singleton
    fun provideTtsModelManager(@ApplicationContext ctx: Context, http: OkHttpClient) =
        TtsModelManager(ctx, http)

    @Provides @Singleton
    fun provideGigaAmModelManager(@ApplicationContext ctx: Context, http: OkHttpClient) =
        GigaAmModelManager(ctx, http)

    @Provides @Singleton
    fun provideContinuousAsr(mm: GigaAmModelManager): ContinuousAsr = GigaAmAsrEngine(mm)

    @Provides @Singleton
    fun provideRuStressMarker(mm: TtsModelManager): RuStressMarker =
        RuStressMarker {
            listOf("vits-ru-multi", "supertonic-ru")
                .map { File(mm.modelDirPath(it), TtsModelManager.STRESS_DICT_FILE) }
                .firstOrNull { it.isFile }
        }

    @Provides @Singleton
    fun provideSelectedTtsVoice(@ApplicationContext ctx: Context): () -> TtsVoice = {
        val prefs = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val id = prefs.getString("tts_voice", TtsModelManager.DEFAULT_VOICE_ID) ?: TtsModelManager.DEFAULT_VOICE_ID
        TtsVoiceCatalog.byId(id)
    }

    @Provides @Singleton
    fun provideTtsEngine(
        mm: TtsModelManager,
        marker: RuStressMarker,
        @ApplicationContext ctx: Context,
        http: OkHttpClient,
        connections: LlmConnectionResolver,
        settings: SettingsRepository,
        selectedTtsVoice: () -> TtsVoice,
    ): TtsEngine {
        val prefs = { ctx.getSharedPreferences("voice", Context.MODE_PRIVATE) }
        val offline = SherpaTtsEngine(
            mm,
            selectedVoice = selectedTtsVoice,
            rate = { prefs().getFloat("tts_rate", 1.0f) },
            liveliness = { prefs().getInt("tts_liveliness", 33) },
            marker = marker,
        )
        return TtsRouter(
            delegate = offline,
            backends = listOf(
                OpenRouterTtsBackend(
                    id = "gemini",
                    model = OpenRouterTtsBackend.GEMINI_MODEL,
                    maleVoice = "Charon",
                    femaleVoice = "Kore",
                    http = http,
                    connections = connections,
                ),
                MiniMaxTtsBackend(http = http, settingsRepository = settings),
            ),
            selectedSource = {
                val stored = prefs().getString("tts_source", TtsRouter.OFFLINE) ?: TtsRouter.OFFLINE
                migrateLegacyTtsSource(stored) { newVal ->
                    Log.i("VoiceModule", "legacy tts_source 'openai' migrated to offline")
                    prefs().edit().putString("tts_source", newVal).apply()
                }
            },
            selectedGender = { if (prefs().getString("agent_gender", "m") == "f") TtsGender.FEMALE else TtsGender.MALE },
        )
    }

    @Provides @Singleton
    fun provideAgentIdentity(@ApplicationContext context: Context): () -> AgentIdentity = {
        val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        AgentIdentity(
            name = prefs.getString("agent_name", "").orEmpty().trim(),
            persona = AgentPersona.fromId(prefs.getString("agent_persona", null)),
            gender = if (prefs.getString("agent_gender", "m") == "f") TtsGender.FEMALE else TtsGender.MALE,
        )
    }

    @Provides @Singleton
    fun provideAudioCapture(@ApplicationContext ctx: Context) =
        AudioCapture(ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

    @Provides @Singleton
    fun provideVoiceEarcon() = VoiceEarcon()

    @Provides @Singleton
    fun provideVoiceGate(@ApplicationContext ctx: Context): VoiceGate = object : VoiceGate {
        override fun isEnabled(): Boolean =
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getBoolean("voice_enabled", false)
        override fun vehicleSnapshot(): DiParsData? = TrackingService.lastData.value
        // Fix D: read the language override mirrored by SettingsViewModel.setVoiceLanguage().
        override fun preferredLang(): VoiceLang? =
            when (ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("voice_lang", "")) {
                "RU" -> VoiceLang.RU
                "EN" -> VoiceLang.EN
                else -> null
            }
        override fun ttsEnabled(): Boolean =
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getBoolean("tts_enabled", false)
    }

    @Provides @Singleton
    fun provideSelfEchoFilter() = SelfEchoFilter()
}
