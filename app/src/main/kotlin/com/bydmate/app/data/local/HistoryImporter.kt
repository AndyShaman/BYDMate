package com.bydmate.app.data.local

import android.util.Log
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.TripRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryImporter @Inject constructor(
    private val energyDataReader: EnergyDataReader,
    private val tripRepository: TripRepository,
    private val idleDrainDao: IdleDrainDao
) {
    companion object {
        private const val TAG = "HistoryImporter"
        private const val MIN_TRIP_KM = 0.1
        private const val MIN_TRIP_DURATION_SEC = 30L
    }

    /**
     * Sync BYD energydata with our database. Called on every app launch.
     * Imports new trips and idle drain sessions, skips duplicates by startTs.
     */
    suspend fun sync(): ImportResult {
        return try {
            doImport()
        } catch (e: EnergyDataException) {
            Log.w(TAG, "sync() failed: ${e.message}", e)
            ImportResult(trips = 0, idleDrains = 0, error = e.message)
        } catch (e: Exception) {
            Log.e(TAG, "sync() unexpected error", e)
            ImportResult(trips = 0, idleDrains = 0, error = e.message ?: e.toString())
        }
    }

    /** Force re-import (from Settings button). Same as sync but always called manually. */
    suspend fun forceImport(): ImportResult = sync()

    private suspend fun doImport(): ImportResult {
        val bydRecords = energyDataReader.readTrips()

        if (bydRecords.isEmpty()) {
            return ImportResult(
                trips = 0, idleDrains = 0, error = null,
                details = "В базе BYD 0 записей"
            )
        }

        // Load existing data to skip duplicates (by startTs in milliseconds)
        val existingTrips = tripRepository.getAllTrips().first()
        val existingTripStartTs = existingTrips.map { it.startTs }.toHashSet()

        // For idle drains, check existing too
        val existingDrainStartTs = mutableSetOf<Long>()
        // We don't have getAllIdleDrains, so just track what we insert this round

        var tripsImported = 0
        var idleDrainsImported = 0
        var skippedDuplicate = 0
        var skippedTooShort = 0

        for (byd in bydRecords) {
            // BYD stores timestamps in epoch seconds, convert to milliseconds
            val startTsMs = byd.startTimestamp * 1000L
            val endTsMs = byd.endTimestamp * 1000L

            // Determine if this is an idle drain (0.0 km) or a real trip
            val isIdleDrain = byd.tripKm < MIN_TRIP_KM

            if (isIdleDrain) {
                // Skip very short idle sessions (< 30 sec)
                if (byd.duration < MIN_TRIP_DURATION_SEC) {
                    skippedTooShort++
                    continue
                }

                // Skip if already in trip DB as duplicate
                if (startTsMs in existingTripStartTs || startTsMs in existingDrainStartTs) {
                    skippedDuplicate++
                    continue
                }

                idleDrainDao.insert(
                    IdleDrainEntity(
                        startTs = startTsMs,
                        endTs = endTsMs,
                        kwhConsumed = byd.electricityKwh
                        // No SOC data in BYD's history DB
                    )
                )
                existingDrainStartTs.add(startTsMs)
                idleDrainsImported++
            } else {
                // Real trip — skip very short ones
                if (byd.duration < MIN_TRIP_DURATION_SEC) {
                    skippedTooShort++
                    continue
                }

                // Skip duplicates
                if (startTsMs in existingTripStartTs) {
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
                        startTs = startTsMs,
                        endTs = endTsMs,
                        distanceKm = byd.tripKm,
                        kwhConsumed = byd.electricityKwh,
                        kwhPer100km = kwhPer100km,
                        avgSpeedKmh = avgSpeed
                    )
                )
                existingTripStartTs.add(startTsMs)
                tripsImported++
            }
        }

        Log.d(TAG, "Import done: $tripsImported trips, $idleDrainsImported idle drains, " +
            "$skippedDuplicate duplicates, $skippedTooShort too short")

        val details = "Найдено ${bydRecords.size} в БД BYD: " +
            "+$tripsImported поездок, +$idleDrainsImported стоянок, " +
            "пропущено $skippedDuplicate дублей, $skippedTooShort коротких"
        return ImportResult(
            trips = tripsImported, idleDrains = idleDrainsImported,
            error = null, details = details
        )
    }

    data class ImportResult(
        val trips: Int,
        val idleDrains: Int = 0,
        val error: String? = null,
        val details: String? = null
    ) {
        val isError: Boolean get() = error != null
        val count: Int get() = trips + idleDrains
    }
}
