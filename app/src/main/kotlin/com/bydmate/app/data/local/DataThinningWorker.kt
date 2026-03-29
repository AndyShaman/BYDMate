package com.bydmate.app.data.local

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.TripPointDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily worker that thins old data points to save disk space.
 *
 * Thinning tiers:
 * - < 7 days: full resolution (every 3 sec)
 * - 7-30 days: ~1 point per 15 sec
 * - > 30 days: ~1 point per 60 sec
 */
@HiltWorker
class DataThinningWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tripPointDao: TripPointDao,
    private val chargePointDao: ChargePointDao
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DataThinning"
        const val WORK_NAME = "data_thinning"
        private const val DAYS_7_MS = 7L * 24 * 60 * 60 * 1000
        private const val DAYS_30_MS = 30L * 24 * 60 * 60 * 1000
        private const val INTERVAL_15S = 15_000L
        private const val INTERVAL_60S = 60_000L
    }

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            val tripsBefore = tripPointDao.getCount()
            val chargesBefore = chargePointDao.getCount()

            // Tier 1: 7-30 days old → keep 1 per 15 sec
            val cutoff30 = now - DAYS_30_MS
            val cutoff7 = now - DAYS_7_MS
            tripPointDao.thinOldPoints(cutoff7, INTERVAL_15S)
            chargePointDao.thinOldPoints(cutoff7, INTERVAL_15S)

            // Tier 2: > 30 days old → keep 1 per 60 sec
            tripPointDao.thinOldPoints(cutoff30, INTERVAL_60S)
            chargePointDao.thinOldPoints(cutoff30, INTERVAL_60S)

            val tripsAfter = tripPointDao.getCount()
            val chargesAfter = chargePointDao.getCount()
            val tripsDeleted = tripsBefore - tripsAfter
            val chargesDeleted = chargesBefore - chargesAfter

            if (tripsDeleted > 0 || chargesDeleted > 0) {
                Log.i(TAG, "Thinned: trip_points $tripsBefore→$tripsAfter (-$tripsDeleted), charge_points $chargesBefore→$chargesAfter (-$chargesDeleted)")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Data thinning failed: ${e.message}", e)
            Result.retry()
        }
    }
}
