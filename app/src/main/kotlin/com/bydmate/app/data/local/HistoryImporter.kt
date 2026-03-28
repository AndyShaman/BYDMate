package com.bydmate.app.data.local

import android.content.Context
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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
        if (alreadyDone == "true") return ImportResult(0, skipped = true)
        return doImport()
    }

    /** Force re-import (from Settings button). */
    suspend fun forceImport(): ImportResult {
        return doImport()
    }

    private suspend fun doImport(): ImportResult {
        val bydTrips = energyDataReader.readTrips()
        if (bydTrips.isEmpty()) {
            settingsRepository.setString(KEY_IMPORT_DONE, "true")
            return ImportResult(0, skipped = false)
        }

        var imported = 0
        for (byd in bydTrips) {
            // Skip very short "trips" (< 0.1 km or < 30 sec)
            if (byd.tripKm < 0.1 || byd.duration < 30) continue

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
            imported++
        }

        settingsRepository.setString(KEY_IMPORT_DONE, "true")
        return ImportResult(imported, skipped = false)
    }

    data class ImportResult(val count: Int, val skipped: Boolean)
}
