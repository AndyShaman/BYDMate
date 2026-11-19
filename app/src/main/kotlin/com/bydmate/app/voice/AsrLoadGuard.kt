package com.bydmate.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/** Breaks the native-crash loop around sherpa-onnx ASR model loads. Loading a corrupt
 *  .onnx aborts the whole process from JNI (uncaught Ort::Exception -> std::terminate ->
 *  SIGABRT), which no Kotlin try/catch can intercept — the on-disk counter below is the
 *  only surviving witness. A per-artifact counter is bumped synchronously BEFORE every
 *  native load and cleared right after it returns; a load that kills the process leaves
 *  the bump behind. After [TRIP_THRESHOLD] interrupted loads of the same artifact the
 *  guard trips: [GigaAmAsrEngine] refuses further loads and TrackingService checks the
 *  model files before it quarantines them (field defect: Sea Lion 07, power-cut
 *  corrupted the GigaAM v3 model and every service start crash-looped, 2026-07-16).
 *
 *  Only interrupted loads of EARLIER processes count ([inherited], read once when this
 *  process builds the guard): the bump of a load still running in this process is not
 *  evidence of anything yet. Counting it made a PTT pressed during the start-up warm-up
 *  answer "model missing" while the files were fine (DiLink 3 field log, 2026-11). */
class AsrLoadGuard(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences("asr_load_guard", Context.MODE_PRIVATE))

    @Volatile private var inherited: Map<String, Int> = storedCounts()

    /** Artifacts whose load this process has begun and not finished. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun isTripped(): Boolean = inherited.values.any { it >= TRIP_THRESHOLD }

    /** The counters as this process found them at start (for the diagnostic dump). */
    fun inheritedCounts(): Map<String, Int> = inherited

    /** The counters as stored now, this process's running loads included. */
    fun storedCounts(): Map<String, Int> = ARTIFACTS.associateWith { prefs.getInt(it, 0) }

    /** commit(), not apply(): the bump must be on flash before the native call can abort. */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun noteLoadBegin(artifact: String) {
        inFlight.add(artifact)
        prefs.edit().putInt(artifact, prefs.getInt(artifact, 0) + 1).commit()
    }

    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun noteLoadSuccess(artifact: String) {
        inFlight.remove(artifact)
        prefs.edit().putInt(artifact, 0).commit()
    }

    /**
     * Takes back the bump of every load this process has in flight. Called right before the app
     * has its own process killed on purpose (the Android 10 accessibility recovery force-stops the
     * package): that kill says nothing about the model files, so it must not count towards the
     * trip. Returns how many loads were in flight.
     */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun withdrawInFlight(): Int {
        val running = inFlight.toList()
        if (running.isEmpty()) return 0
        val edit = prefs.edit()
        running.forEach { edit.putInt(it, (prefs.getInt(it, 0) - 1).coerceAtLeast(0)) }
        edit.commit()
        inFlight.clear()
        return running.size
    }

    /** Called once the tripped model files were checked (or quarantined): loads may run again. */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun reset() {
        prefs.edit().clear().commit()
        inherited = ARTIFACTS.associateWith { 0 }
        inFlight.clear()
    }

    companion object {
        const val ARTIFACT_RECOGNIZER = "interrupted_recognizer_loads"
        const val ARTIFACT_VAD = "interrupted_vad_loads"
        const val ARTIFACT_TTS = "interrupted_tts_loads"
        private val ARTIFACTS = listOf(ARTIFACT_RECOGNIZER, ARTIFACT_VAD, ARTIFACT_TTS)

        /** 2, not 1: a single interrupted load can be an unrelated mid-load process kill
         *  (LMK, force-stop); quarantining the 226 MiB model on one such event would be a
         *  false positive costing the user a full re-download. */
        internal const val TRIP_THRESHOLD = 2
    }
}
