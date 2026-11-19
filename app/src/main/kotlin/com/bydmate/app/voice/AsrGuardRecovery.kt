package com.bydmate.app.voice

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.bydmate.app.voice.GigaAmModelManager.Integrity

/**
 * What a service start does with a tripped [AsrLoadGuard]: the files are checked against the
 * hashes taken when they were known good, and deleted only when that check says they are bad.
 *
 * Deleting on every trip cost DiLink 3 drivers the 226 MB model again and again (field log,
 * 2026-11): the Android 10 accessibility recovery force-stops the app seconds after every start,
 * while the start-up warm-up is still loading the model, so the guard kept counting kills that
 * had nothing to do with the files.
 */
class AsrGuardRecovery(
    private val guard: AsrLoadGuard,
    private val files: Files,
    private val journal: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(guard: AsrLoadGuard, manager: GigaAmModelManager, journal: SharedPreferences) :
        this(guard, ManagerFiles(manager), journal)

    private class ManagerFiles(private val manager: GigaAmModelManager) : Files {
        override suspend fun verifyIntegrity() = manager.verifyIntegrity()
        override fun unverifiedKeptBefore() = manager.unverifiedKeptBefore()
        override suspend fun markUnverifiedKept() = manager.markUnverifiedKept()
        override suspend fun delete() = manager.delete()
    }

    /** The part of [GigaAmModelManager] this needs; an interface so the decision is unit-tested. */
    interface Files {
        suspend fun verifyIntegrity(): Integrity
        fun unverifiedKeptBefore(): Boolean
        suspend fun markUnverifiedKept()
        suspend fun delete()
    }

    enum class Outcome {
        NOT_TRIPPED,
        /** Files match their hashes: the interrupted loads were process kills, the model stays. */
        KEPT_INTACT,
        /** No hash to compare with yet: kept once, so a good model is not thrown away on a guess. */
        KEPT_UNVERIFIED,
        /** A file differs from its hash, is missing, or stayed unverifiable after a second trip. */
        DELETED,
    }

    suspend fun run(): Outcome {
        if (!guard.isTripped()) return Outcome.NOT_TRIPPED
        val counts = guard.inheritedCounts()
        val integrity = runCatching { files.verifyIntegrity() }.getOrDefault(Integrity.UNVERIFIED)
        val outcome = decide(integrity, files.unverifiedKeptBefore())
        when (outcome) {
            Outcome.DELETED -> files.delete()
            Outcome.KEPT_UNVERIFIED, Outcome.KEPT_INTACT -> files.markUnverifiedKept()
            Outcome.NOT_TRIPPED -> Unit
        }
        guard.reset()
        record(outcome, integrity, counts)
        return outcome
    }

    @SuppressLint("ApplySharedPref")
    private fun record(outcome: Outcome, integrity: Integrity, counts: Map<String, Int>) {
        journal.edit()
            .putLong(KEY_LAST_AT, now())
            .putString(KEY_LAST, "${outcome.name.lowercase()} integrity=${integrity.name.lowercase()} " +
                "recognizer=${counts[AsrLoadGuard.ARTIFACT_RECOGNIZER] ?: 0} vad=${counts[AsrLoadGuard.ARTIFACT_VAD] ?: 0}")
            .putInt(KEY_COUNT_PREFIX + outcome.name, journal.getInt(KEY_COUNT_PREFIX + outcome.name, 0) + 1)
            .commit()
    }

    companion object {
        const val PREFS_NAME = "asr_guard_recovery"
        const val KEY_LAST_AT = "last_at"
        const val KEY_LAST = "last"
        const val KEY_COUNT_PREFIX = "count_"

        fun decide(integrity: Integrity, unverifiedKeptBefore: Boolean): Outcome = when (integrity) {
            // Kept once; a second trip with no successful load between them means the files
            // themselves abort the load (not a power cut), so they go as before.
            Integrity.INTACT -> if (unverifiedKeptBefore) Outcome.DELETED else Outcome.KEPT_INTACT
            Integrity.CORRUPT -> Outcome.DELETED
            Integrity.UNVERIFIED -> if (unverifiedKeptBefore) Outcome.DELETED else Outcome.KEPT_UNVERIFIED
        }

        /** One dump line: what the last trip did and how often each outcome happened. */
        fun dumpLine(journal: SharedPreferences): String {
            val counts = Outcome.entries.filter { it != Outcome.NOT_TRIPPED }
                .joinToString(" ") { "${it.name.lowercase()}=${journal.getInt(KEY_COUNT_PREFIX + it.name, 0)}" }
            val at = journal.getLong(KEY_LAST_AT, 0L)
            val last = journal.getString(KEY_LAST, null)
            return "guard_recovery: $counts last=${last ?: "-"}" +
                if (at > 0L) " at=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))}" else ""
        }
    }
}
