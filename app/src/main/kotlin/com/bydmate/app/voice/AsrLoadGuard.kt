package com.bydmate.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/** Breaks the native-crash loop around sherpa-onnx ASR model loads. Loading a corrupt
 *  .onnx aborts the whole process from JNI (uncaught Ort::Exception -> std::terminate ->
 *  SIGABRT), which no Kotlin try/catch can intercept — the on-disk counter below is the
 *  only surviving witness. A per-artifact counter is bumped synchronously BEFORE every
 *  native load and cleared right after it returns; a load that kills the process leaves
 *  the bump behind. After [TRIP_THRESHOLD] interrupted loads of the same artifact the
 *  guard trips: [GigaAmAsrEngine] refuses further loads and TrackingService quarantines
 *  the model files so the user can re-download (field defect: Sea Lion 07, power-cut
 *  corrupted the GigaAM v3 model and every service start crash-looped, 2026-07-16). */
class AsrLoadGuard(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences("asr_load_guard", Context.MODE_PRIVATE))

    fun isTripped(): Boolean =
        listOf(ARTIFACT_RECOGNIZER, ARTIFACT_VAD, ARTIFACT_TTS).any { prefs.getInt(it, 0) >= TRIP_THRESHOLD }

    /** commit(), not apply(): the bump must be on flash before the native call can abort. */
    @SuppressLint("ApplySharedPref")
    fun noteLoadBegin(artifact: String) {
        prefs.edit().putInt(artifact, prefs.getInt(artifact, 0) + 1).commit()
    }

    @SuppressLint("ApplySharedPref")
    fun noteLoadSuccess(artifact: String) {
        prefs.edit().putInt(artifact, 0).commit()
    }

    /** Called after the corrupt model files were quarantined: a fresh download may load. */
    @SuppressLint("ApplySharedPref")
    fun reset() {
        prefs.edit().clear().commit()
    }

    companion object {
        const val ARTIFACT_RECOGNIZER = "interrupted_recognizer_loads"
        const val ARTIFACT_VAD = "interrupted_vad_loads"
        const val ARTIFACT_TTS = "interrupted_tts_loads"

        /** 2, not 1: a single interrupted load can be an unrelated mid-load process kill
         *  (LMK, force-stop); quarantining the 226 MiB model on one such event would be a
         *  false positive costing the user a full re-download. */
        internal const val TRIP_THRESHOLD = 2
    }
}
