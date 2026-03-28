package com.bydmate.app.data.local

import android.content.Context
import android.util.Log
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val energyDataReader: EnergyDataReader,
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "HistoryImporter"
        private const val KEY_IMPORT_DONE = "history_import_done"
    }

    /**
     * Import BYD trip history on first launch.
     * Reads /storage/emulated/0/energydata and converts to TripEntity records.
     * BYD stores trip distance and its own kWh estimate — we recalculate
     * kWh/100km from those values (BYD estimate, not delta SOC — no SOC data
     * available in the history DB).
     */
    suspend fun importIfNeeded(): ImportResult {
        val alreadyDone = settingsRepository.getString(KEY_IMPORT_DONE, "false")
        if (alreadyDone == "true") {
            Log.d(TAG, "importIfNeeded(): already done, skipping")
            return ImportResult(count = 0, skipped = true, error = null)
        }
        return try {
            doImport()
        } catch (e: EnergyDataException) {
            Log.w(TAG, "importIfNeeded() failed: ${e.message}", e)
            // Do NOT set KEY_IMPORT_DONE — will retry on next launch
            ImportResult(count = 0, skipped = false, error = e.message)
        } catch (e: Exception) {
            Log.e(TAG, "importIfNeeded() unexpected error", e)
            ImportResult(count = 0, skipped = false, error = e.message ?: e.toString())
        }
    }

    /** Force re-import (from Settings button). Skips duplicates by startTs. */
    suspend fun forceImport(): ImportResult {
        return try {
            doImport()
        } catch (e: EnergyDataException) {
            Log.w(TAG, "forceImport() failed: ${e.message}", e)
            ImportResult(count = 0, skipped = false, error = e.message)
        } catch (e: Exception) {
            Log.e(TAG, "forceImport() unexpected error", e)
            ImportResult(count = 0, skipped = false, error = e.message ?: e.toString())
        }
    }

    private suspend fun doImport(): ImportResult {
        Log.d(TAG, "doImport() started")

        val bydTrips = energyDataReader.readTrips()
        Log.d(TAG, "Read ${bydTrips.size} BYD trips from energydata DB")

        if (bydTrips.isEmpty()) {
            settingsRepository.setString(KEY_IMPORT_DONE, "true")
            return ImportResult(count = 0, skipped = false, error = null)
        }

        // Load existing trips to skip duplicates (by startTs)
        val existingTrips = tripRepository.getAllTrips().first()
        val existingStartTsSet = existingTrips.map { it.startTs }.toHashSet()
        Log.d(TAG, "Existing trips in DB: ${existingTrips.size}, " +
            "unique startTs values: ${existingStartTsSet.size}")

        var imported = 0
        var skippedShort = 0
        var skippedDuplicate = 0
        for (byd in bydTrips) {
            // Skip very short "trips" (< 0.1 km or < 30 sec)
            if (byd.tripKm < 0.1 || byd.duration < 30) {
                skippedShort++
                continue
            }

            // Skip duplicates by startTs
            if (byd.startTimestamp in existingStartTsSet) {
                skippedDuplicate++
                continue
            }

            val kwhPer100km = if (byd.tripKm > 0) {
                byd.electricityKwh / byd.tripKm * 100.0
            } else null

            val avgSpeed = if (byd.duration > 0) {
                byd.tripKm / (byd.duration / 3600.0)
            } else null

            tripRepository.insertTrip(
                TripEntity(
                    startTs = byd.startTimestamp,
                    endTs = byd.endTimestamp,
                    distanceKm = byd.tripKm,
                    kwhConsumed = byd.electricityKwh,
                    kwhPer100km = kwhPer100km,
                    avgSpeedKmh = avgSpeed
                    // No SOC or temp data in BYD's history DB
                )
            )
            existingStartTsSet.add(byd.startTimestamp)
            imported++
        }

        Log.d(TAG, "Import done: $imported imported, " +
            "$skippedShort skipped (too short), " +
            "$skippedDuplicate skipped (duplicate)")

        settingsRepository.setString(KEY_IMPORT_DONE, "true")
        return ImportResult(count = imported, skipped = false, error = null)
    }

    data class ImportResult(
        val count: Int,
        val skipped: Boolean,
        /** Non-null if import failed; contains a human-readable error message. */
        val error: String? = null
    ) {
        val isError: Boolean get() = error != null
    }
}
