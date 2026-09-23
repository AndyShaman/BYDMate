package com.bydmate.app.data.backup

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What still needs the user after a restore. */
sealed interface PostRestoreItem {
    data object Overlay : PostRestoreItem
    data object Mic : PostRestoreItem
    data object Contacts : PostRestoreItem
    data object AsrModel : PostRestoreItem
    data class TtsVoice(val voiceId: String) : PostRestoreItem
}

/** Information-only lines: nothing the app or a button can do about them. */
enum class PostRestoreNotice { REBOOT }

data class PostRestoreReport(
    val items: List<PostRestoreItem>,
    val notices: Set<PostRestoreNotice>,
) {
    val isEmpty: Boolean get() = items.isEmpty() && notices.isEmpty()
}

/** Restored toggles the check cares about. */
data class RestoredToggles(
    val widget: Boolean,
    val blindSpot: Boolean,
    val voice: Boolean,
    val agent: Boolean,
    val tts: Boolean,
    /** TTS speaks through the downloaded offline voice (not an online backend). */
    val ttsOffline: Boolean,
    val ttsVoiceId: String,
    val nativeAssistantDisabled: Boolean,
)

/** Everything the check reads from or does to the system; faked in tests. */
interface PostRestoreProbes {
    suspend fun toggles(): RestoredToggles
    fun canDrawOverlays(): Boolean
    fun hasPermission(name: String): Boolean
    fun asrModelReady(): Boolean
    fun ttsVoiceReady(voiceId: String): Boolean
    suspend fun grantOverlayViaDaemon(): Boolean
    suspend fun attachWidget()
}

/**
 * First start after a backup restore: the settings came back, but state that lives outside the
 * backup (overlay permission, runtime permissions, downloaded voice models, the native-assistant
 * reboot) did not. Fixes what it can silently and reports the rest for one dialog.
 */
class PostRestoreCheck(
    private val state: SharedPreferences,
    private val probes: PostRestoreProbes,
    private val currentBootId: () -> String? = ::readBootId,
) {
    private val _report = MutableStateFlow<PostRestoreReport?>(null)
    val report: StateFlow<PostRestoreReport?> = _report.asStateFlow()

    // Serializes runIfPending/recheck, so two probes never publish out of order.
    private val mutex = Mutex()
    // Guards generation + publish against dismiss(), which runs on the UI thread and must not wait.
    private val lock = Any()
    // Bumped by dismiss(): a probe that started before it must not bring the report back.
    private var generation = 0

    suspend fun runIfPending(): PostRestoreReport? {
        mutex.withLock {
            if (!state.getBoolean(KEY_PENDING, false)) return null
            val gen = synchronized(lock) { generation }
            val toggles = probes.toggles()
            var overlay = probes.canDrawOverlays()
            if (!overlay && needsOverlay(toggles)) {
                overlay = probes.grantOverlayViaDaemon() && probes.canDrawOverlays()
                if (overlay) {
                    Log.i(TAG, "PostRestore: overlay granted via daemon")
                    if (toggles.widget) probes.attachWidget()
                }
            }
            return publish(gen, evaluate(toggles, overlay), toggles, overlay)
        }
    }

    /** Re-probes after the user acted in the dialog; drops what is fixed now. */
    suspend fun recheck() {
        mutex.withLock {
            val gen = synchronized(lock) { generation }
            val hadOverlayItem = _report.value?.items?.contains(PostRestoreItem.Overlay) ?: return
            val toggles = probes.toggles()
            val overlay = probes.canDrawOverlays()
            if (hadOverlayItem && overlay && toggles.widget) probes.attachWidget()
            publish(gen, evaluate(toggles, overlay), toggles, overlay)
        }
    }

    fun dismiss() {
        synchronized(lock) {
            generation++
            clearMarker()
            _report.value = null
        }
    }

    private fun needsOverlay(t: RestoredToggles) = t.widget || t.blindSpot || t.voice || t.agent

    private fun evaluate(t: RestoredToggles, overlay: Boolean): PostRestoreReport {
        val items = buildList {
            if (needsOverlay(t) && !overlay) add(PostRestoreItem.Overlay)
            if (t.voice && !probes.hasPermission(Manifest.permission.RECORD_AUDIO)) add(PostRestoreItem.Mic)
            if (t.agent && !probes.hasPermission(Manifest.permission.READ_CONTACTS)) add(PostRestoreItem.Contacts)
            if (t.voice && !probes.asrModelReady()) add(PostRestoreItem.AsrModel)
            if (t.tts && t.ttsOffline && !probes.ttsVoiceReady(t.ttsVoiceId)) {
                add(PostRestoreItem.TtsVoice(t.ttsVoiceId))
            }
        }
        val reboot = t.nativeAssistantDisabled && sameBootAsRestore()
        val notices = if (reboot) setOf(PostRestoreNotice.REBOOT) else emptySet()
        return PostRestoreReport(items, notices)
    }

    /** The head unit has not rebooted since the restore; an unknown boot id counts as "not yet". */
    private fun sameBootAsRestore(): Boolean {
        val marked = state.getString(KEY_BOOT_ID, null) ?: return true
        val current = currentBootId() ?: return true
        return marked == current
    }

    private fun publish(
        gen: Int,
        report: PostRestoreReport,
        t: RestoredToggles,
        overlay: Boolean,
    ): PostRestoreReport {
        synchronized(lock) {
            if (gen != generation) {
                Log.i(TAG, "PostRestore: dismissed while probing, result dropped")
                return report
            }
            Log.i(
                TAG,
                "PostRestore: pending=true overlay=$overlay " +
                    "mic=${flag(t.voice, PostRestoreItem.Mic !in report.items)} " +
                    "contacts=${flag(t.agent, PostRestoreItem.Contacts !in report.items)} " +
                    "asr=${flag(t.voice, PostRestoreItem.AsrModel !in report.items)} " +
                    "tts=${flag(t.tts, report.items.none { it is PostRestoreItem.TtsVoice })} " +
                    "reboot=${PostRestoreNotice.REBOOT in report.notices}",
            )
            if (report.isEmpty) {
                clearMarker()
                _report.value = null
                Log.i(TAG, "PostRestore: nothing to fix, marker cleared")
            } else {
                _report.value = report
            }
            return report
        }
    }

    /** "off" when the feature is not enabled, else whether its prerequisite is in place. */
    private fun flag(enabled: Boolean, ok: Boolean) = if (!enabled) "off" else ok.toString()

    private fun clearMarker() {
        if (!state.edit().remove(KEY_PENDING).remove(KEY_TS).remove(KEY_BOOT_ID).commit()) {
            Log.w(TAG, "PostRestore: marker clear not committed")
        }
    }

    companion object {
        private const val TAG = "PostRestoreCheck"

        /** Deliberately outside [BackupManager.PREFS_FILES]: a backup must never carry the marker. */
        const val PREFS_NAME = "bydmate_restore_state"
        const val KEY_PENDING = "post_restore_pending"
        const val KEY_TS = "post_restore_ts"
        const val KEY_BOOT_ID = "post_restore_boot_id"
        private const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"

        /** Kernel id of the current boot of the head unit, null when unreadable. */
        fun readBootId(): String? =
            runCatching { File(BOOT_ID_PATH).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

        fun markPending(context: Context, bootId: String? = readBootId()) {
            val ok = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PENDING, true)
                .putLong(KEY_TS, System.currentTimeMillis())
                .putString(KEY_BOOT_ID, bootId)
                .commit()
            if (!ok) Log.w(TAG, "PostRestore: marker not committed")
        }

        /** One line for the diagnostics dump header. */
        fun dumpLine(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ts = prefs.getLong(KEY_TS, 0L)
            val iso = if (ts > 0L) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(ts)) else "-"
            return "post_restore: pending=${prefs.getBoolean(KEY_PENDING, false)} ts=$iso"
        }
    }
}
