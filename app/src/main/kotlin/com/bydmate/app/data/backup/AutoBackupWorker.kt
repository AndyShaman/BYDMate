package com.bydmate.app.data.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bydmate.app.data.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs one automatic backup (see [AutoBackupScheduler]); a pending Telegram upload retries with backoff. */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val sink: TelegramBackupSink,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_FORCE, false)
        val outcome = withContext(Dispatchers.IO) {
            AutoBackupRunner(backupManager, sink, settingsRepository)
                .run(force = forceExport(manual, runAttemptCount), manual = manual)
        }
        return when (capRetries(outcome, runAttemptCount)) {
            RunOutcome.SUCCESS -> Result.success()
            RunOutcome.RETRY -> Result.retry()
            RunOutcome.FAILURE -> Result.failure()
        }
    }

    companion object {
        /** Input flag of the manual run: export afresh even when an upload is pending. */
        const val KEY_FORCE = "force"
    }
}

/**
 * Only the first attempt of a manual run exports afresh; its retries deliver the pending file
 * instead of piling up new exports.
 */
internal fun forceExport(manual: Boolean, attempt: Int): Boolean = manual && attempt == 0

/** WorkManager attempt (0-based) from which a transient upload failure stops retrying: 3 attempts in all. */
internal const val MAX_RETRY_ATTEMPT = 2

/**
 * Bounds the backoff loop: from [MAX_RETRY_ATTEMPT] a RETRY ends the work as SUCCESS. The file
 * stays pending, so the next due check tries it again.
 */
internal fun capRetries(outcome: RunOutcome, attempt: Int): RunOutcome =
    if (outcome == RunOutcome.RETRY && attempt >= MAX_RETRY_ATTEMPT) {
        Log.w("AutoBackup", "upload failed on attempt $attempt, giving up until the next run, pending kept")
        RunOutcome.SUCCESS
    } else outcome
