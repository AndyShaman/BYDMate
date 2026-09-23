package com.bydmate.app.data.backup

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bydmate.app.data.repository.SettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides at ignition whether an automatic backup is due and hands it to [AutoBackupWorker].
 *
 * There is deliberately no periodic WorkManager job: the head unit sleeps most of the day, so a
 * periodic job would drift and fire at random wake-ups. "Daily" means "at the first ignition after
 * 24 h have passed since the last successful export"; weekly and monthly work the same way.
 */
@Singleton
class AutoBackupScheduler @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "AutoBackup"
        const val WORK_NAME = "auto_backup"
        /** Own name so a scheduled run waiting for the network never holds up a manual one. */
        const val MANUAL_WORK_NAME = "auto_backup_manual"
        /** Lets the head unit finish its own start-up (sync, modem) before the zip is built. */
        private const val START_DELAY_MIN = 2L
    }

    /** Called from TrackingService.onCreate; enqueues the worker only when a backup is due. */
    suspend fun enqueueIfDue(context: Context) {
        val period = settingsRepository.getAutoBackupPeriod()
        val lastTs = settingsRepository.getAutoBackupLastTs()
        val pendingPath = settingsRepository.getAutoBackupPendingUpload()
        val pending = pendingPath.isNotEmpty() && File(pendingPath).exists()
        val due = isAutoBackupDue(period, lastTs, System.currentTimeMillis(), pending)
        Log.i(TAG, "due period=${period.key} last=$lastTs pending=$pending → ${if (due) "run" else "skip"}")
        if (!due) return
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInitialDelay(START_DELAY_MIN, TimeUnit.MINUTES)
        // With a bot the upload is the point of the run, so wait for the network; without one the
        // local copy must not depend on it.
        if (settingsRepository.getTgBackupToken().isNotBlank()) {
            request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        }
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request.build())
    }

    /** «Выкл» or «Отключить»: drops a scheduled run that is still waiting or retrying; manual runs stay. */
    fun cancelScheduled(context: Context) {
        Log.i(TAG, "scheduled run cancelled")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Manual run from Settings: a fresh export regardless of the period, last run and pending upload.
     * Runs under [MANUAL_WORK_NAME] with no delay or constraints, so it may overlap a scheduled
     * run: export() is serialized and each run uploads its own file. KEEP: a second tap while a
     * manual run is in flight does not start another one.
     */
    fun enqueueNow(context: Context) {
        Log.i(TAG, "manual run requested")
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setInputData(workDataOf(AutoBackupWorker.KEY_FORCE to true))
                .build(),
        )
    }
}

/**
 * A backup is due when the period is on and either the interval has elapsed or an upload is pending.
 * A last run in the future (clock set back) counts as due, otherwise backups would stop until then.
 */
internal fun isAutoBackupDue(period: AutoBackupPeriod, lastTs: Long, now: Long, pendingUpload: Boolean): Boolean {
    if (period == AutoBackupPeriod.OFF) return false
    return pendingUpload || lastTs > now || now - lastTs >= period.intervalMs
}
