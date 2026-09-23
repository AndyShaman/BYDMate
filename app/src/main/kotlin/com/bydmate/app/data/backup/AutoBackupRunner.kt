package com.bydmate.app.data.backup

import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RunOutcome { SUCCESS, RETRY, FAILURE }

/**
 * A manual save (#238): the file left in Download, [sent] = the bot got a copy, [sendError] = why it
 * did not (TelegramError name or exception class); both empty when no bot is connected.
 */
data class ManualSaveResult(val file: File, val sent: Boolean, val sendError: String?)

/**
 * One automatic backup run (#237): export → rename to the `_auto_` name → rotate → deliver to
 * the user's Telegram bot. Pure of WorkManager so it can be unit-tested; [AutoBackupWorker] only
 * maps the [RunOutcome].
 *
 * Auto files keep the `bydmate_backup_` prefix so the restore picker lists them; the `_auto_`
 * infix keeps rotation away from the user's manual exports.
 */
class AutoBackupRunner(
    private val backupManager: BackupManager,
    private val sink: TelegramBackupSink,
    private val settingsRepository: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "AutoBackup"
        const val KEEP_LOCAL = 5
        private const val BACKUP_PREFIX = "bydmate_backup_"
        const val AUTO_PREFIX = "bydmate_backup_auto_"
        private const val ZIP_SUFFIX = ".zip"
        private const val BYTES_PER_MB = 1024.0 * 1024.0

        // last_result keys; Settings translates them, the dump prints them as is.
        const val RESULT_SENT = "sent"
        const val RESULT_LOCAL_ONLY = "local_only"
        const val RESULT_SEND_ERROR_PREFIX = "send_error:"
        const val RESULT_EXPORT_ERROR = "export_error"

        /** One run at a time across workers (scheduled + manual): pending and rotation are shared. */
        private val runLock = Mutex()
    }

    /**
     * [force] = a fresh export even when an upload is pending (first attempt of a manual run).
     * [manual] = started by the user, so it runs even with the period off; a scheduled run
     * re-reads the period first and stops when it was switched off after enqueueing.
     */
    suspend fun run(force: Boolean = false, manual: Boolean = force): RunOutcome = runLock.withLock {
        if (!manual && settingsRepository.getAutoBackupPeriod() == AutoBackupPeriod.OFF) {
            Log.i(TAG, "period is off, run skipped")
            return@withLock RunOutcome.SUCCESS
        }
        runLocked(force)
    }

    private suspend fun runLocked(force: Boolean): RunOutcome {
        val config = settingsRepository.getTgBackupConfig()
        val pendingPath = settingsRepository.getAutoBackupPendingUpload()
        var pending = pendingPath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }
        if (pending == null && pendingPath.isNotEmpty()) {
            // Rotated away or deleted by the user: forget it and take the normal export path.
            Log.i(TAG, "pending ${File(pendingPath).name} is gone, exporting afresh")
            settingsRepository.setAutoBackupPendingUpload("")
        }
        if (pending != null && force) {
            Log.i(TAG, "pending ${pending.name} superseded by a manual run")
            pending = null
        }
        if (pending != null && isScheduleDue()) {
            // A stuck upload (weeks without network) must not block fresh backups: once the period
            // has elapsed, a new export replaces the old pending file.
            Log.i(TAG, "pending ${pending.name} superseded by a due export")
            pending = null
        }
        if (pending != null) Log.i(TAG, "retrying upload of ${pending.name}")
        val file = pending ?: exportAndRotate(config) ?: return RunOutcome.FAILURE
        return deliver(file, config)
    }

    private suspend fun isScheduleDue(): Boolean = isAutoBackupDue(
        settingsRepository.getAutoBackupPeriod(),
        settingsRepository.getAutoBackupLastTs(),
        clock(),
        pendingUpload = false,
    )

    /** Exports, renames to the auto name and rotates; null (with last_result set) when export failed. */
    private suspend fun exportAndRotate(config: TgBackupConfig): File? {
        // export() throws a mix of IllegalStateException, IOException and SQLite errors; all of
        // them mean "no backup this time" and the next ignition retries (last_ts stays old).
        val parts = settingsRepository.getAutoBackupParts()
        val exported = runCatching { backupManager.export(parts) }.getOrElse { e ->
            Log.w(TAG, "export failed: ${e.javaClass.simpleName}: ${e.message}")
            settingsRepository.setAutoBackupLastResult(RESULT_EXPORT_ERROR)
            return null
        }
        val file = renameToAuto(exported)
        Log.i(TAG, "exported ${file.name} ${file.length()} parts=${BackupPart.toCsv(parts)}")
        settingsRepository.setAutoBackupLastTs(clock())
        // Pending before any network call: a run cancelled or killed mid-upload leaves the file
        // for the next attempt instead of losing it.
        if (config.configured) settingsRepository.setAutoBackupPendingUpload(file.absolutePath)
        rotate(file)
        return file
    }

    private fun renameToAuto(exported: File): File {
        if (!exported.name.startsWith(BACKUP_PREFIX)) return exported
        val target = File(exported.parentFile, AUTO_PREFIX + exported.name.removePrefix(BACKUP_PREFIX))
        if (exported.renameTo(target)) return target
        Log.w(TAG, "rename to ${target.name} failed, keeping ${exported.name}")
        return exported
    }

    /**
     * Keeps the newest [KEEP_LOCAL] auto backups next to [fresh]; manual exports are never touched.
     * [fresh] itself always survives, even if a skewed clock gave older files a later mtime.
     */
    private fun rotate(fresh: File) {
        val autoFiles = fresh.parentFile?.listFiles { file ->
            file.isFile && file.name.startsWith(AUTO_PREFIX) && file.name.endsWith(ZIP_SUFFIX) && file.name != fresh.name
        } ?: return
        val deleted = autoFiles.sortedByDescending { it.lastModified() }
            .drop(KEEP_LOCAL - 1)
            .count { it.delete() }
        Log.i(TAG, "rotation deleted $deleted")
    }

    /**
     * «Сохранить» in Settings: exports [parts] and sends one copy to the bot when it is connected.
     * Outside the auto backup flow: no rename, rotation, pending upload or last run. Throws when
     * the export fails.
     */
    suspend fun saveManual(parts: Set<BackupPart>): ManualSaveResult {
        val file = backupManager.export(parts)
        Log.i(TAG, "manual save ${file.name} ${file.length()} parts=${BackupPart.toCsv(parts)}")
        val config = settingsRepository.getTgBackupConfig()
        val chatId = config.chatId
        if (!config.configured || chatId == null) return ManualSaveResult(file, sent = false, sendError = null)
        if (file.length() > TelegramBackupSink.MAX_UPLOAD_BYTES) {
            return ManualSaveResult(file, sent = false, sendError = TelegramError.TOO_LARGE.name)
        }
        val error = sink.sendDocument(config.token, chatId, file, caption(file)).exceptionOrNull()
        if (error != null) Log.w(TAG, "manual send failed: ${error.javaClass.simpleName}: ${error.message}")
        return ManualSaveResult(
            file,
            sent = error == null,
            sendError = error?.let { (it as? TelegramSinkException)?.key ?: it.javaClass.simpleName },
        )
    }

    private suspend fun deliver(file: File, config: TgBackupConfig): RunOutcome {
        val chatId = config.chatId
        if (!config.configured || chatId == null) {
            settingsRepository.setAutoBackupPendingUpload("")
            settingsRepository.setAutoBackupLastResult(RESULT_LOCAL_ONLY)
            return RunOutcome.SUCCESS
        }
        if (file.length() > TelegramBackupSink.MAX_UPLOAD_BYTES) {
            // Bot API rejects it anyway; a permanent error, so the file does not stay pending.
            Log.w(TAG, "${file.name} is ${file.length()} bytes, over the Telegram limit")
            settingsRepository.setAutoBackupPendingUpload("")
            settingsRepository.setAutoBackupLastResult(RESULT_SEND_ERROR_PREFIX + TelegramError.TOO_LARGE.name)
            return RunOutcome.SUCCESS
        }
        val error = sink.sendDocument(config.token, chatId, file, caption(file)).exceptionOrNull()
        if (error == null) {
            settingsRepository.setAutoBackupPendingUpload("")
            settingsRepository.setAutoBackupLastResult(RESULT_SENT)
            return RunOutcome.SUCCESS
        }
        val sinkError = error as? TelegramSinkException
        settingsRepository.setAutoBackupLastResult(
            RESULT_SEND_ERROR_PREFIX + (sinkError?.key ?: error.javaClass.simpleName)
        )
        // Only a transient failure keeps the file pending: a permanent one (bad token, blocked bot,
        // file over 50 MB) would otherwise pin every later run to this file and stop new backups.
        if (sinkError?.transient == false) {
            settingsRepository.setAutoBackupPendingUpload("")
            return RunOutcome.SUCCESS
        }
        settingsRepository.setAutoBackupPendingUpload(file.absolutePath)
        return RunOutcome.RETRY
    }

    private fun caption(file: File): String {
        val ru = Locale("ru")
        val date = SimpleDateFormat("d MMMM HH:mm", ru).format(Date(file.lastModified()))
        val sizeMb = String.format(ru, "%.1f", file.length() / BYTES_PER_MB)
        return "BYDMate: бэкап $date, $sizeMb МБ"
    }
}
