package com.bydmate.app.data.local

import android.content.Context
import android.util.Log
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.DiPlusDbReader
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val energyDataReader: EnergyDataReader,
    private val tripRepository: TripRepository,
    private val tripDao: TripDao,
    private val tripPointDao: TripPointDao,
    private val idleDrainDao: IdleDrainDao,
    private val diPlusDbReader: DiPlusDbReader,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "HistoryImporter"
        private const val MIN_TRIP_DURATION_SEC = 30L
        private const val DEDUP_WINDOW_MS = 300_000L // ±5 min for time-based dedup
    }

    private val syncMutex = Mutex()

    data class ImportResult(
        val trips: Int,
        val idleDrains: Int = 0,
        val error: String? = null,
        val details: String? = null
    ) {
        val isError: Boolean get() = error != null
        val count: Int get() = trips + idleDrains
    }

    /**
     * Event-based sync: check if energydata changed, import new records.
     * Called on service start and app foreground.
     * Protected by mutex to prevent concurrent duplicate imports.
     */
    suspend fun syncFromEnergyData(): ImportResult {
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "syncFromEnergyData: already running, skipping")
            return ImportResult(trips = 0, details = "Синхронизация уже идёт")
        }
        return try {
            if (!energyDataReader.hasSourceChanged(context)) {
                Log.d(TAG, "syncFromEnergyData: source unchanged, skipping")
                return ImportResult(trips = 0, details = "Без изменений")
            }
            doSync()
        } catch (e: EnergyDataException) {
            Log.w(TAG, "syncFromEnergyData failed: ${e.message}", e)
            ImportResult(trips = 0, error = e.message)
        } catch (e: Exception) {
            Log.e(TAG, "syncFromEnergyData unexpected error", e)
            ImportResult(trips = 0, error = e.message ?: e.toString())
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun doSync(): ImportResult {
        val lastImportTs = settingsRepository.getLastEnergyImportTs()
        val bydRecords = energyDataReader.readTripsSince(lastImportTs)

        if (bydRecords.isEmpty()) {
            return ImportResult(trips = 0, details = "Нет новых записей")
        }

        var tripsImported = 0
        var idleDrainsImported = 0
        var skippedDuplicate = 0
        var maxTs = lastImportTs

        for (byd in bydRecords) {
            val startTsMs = byd.startTimestamp * 1000L
            val endTsMs = byd.endTimestamp * 1000L

            // Track max timestamp for next sync
            if (byd.startTimestamp > maxTs) maxTs = byd.startTimestamp

            // Skip very short sessions
            if (byd.duration < MIN_TRIP_DURATION_SEC) continue

            // Check duplicate by byd_id
            val existingById = tripDao.getByBydId(byd.id)
            if (existingById != null) {
                skippedDuplicate++
                continue
            }

            // Check duplicate by time overlap (catches v1.x live trips without byd_id)
            val existingByTime = tripDao.getByStartTsRange(
                startTsMs - DEDUP_WINDOW_MS,
                startTsMs + DEDUP_WINDOW_MS
            )
            if (existingByTime != null) {
                // Update existing trip with byd_id so future syncs skip it instantly
                tripRepository.updateTrip(existingByTime.copy(
                    source = "energydata",
                    bydId = byd.id,
                    distanceKm = byd.tripKm,
                    kwhConsumed = byd.electricityKwh
                ))
                skippedDuplicate++
                continue
            }

            val isIdleDrain = byd.tripKm == 0.0

            if (isIdleDrain) {
                idleDrainDao.insert(
                    IdleDrainEntity(
                        startTs = startTsMs,
                        endTs = endTsMs,
                        kwhConsumed = byd.electricityKwh
                    )
                )
                idleDrainsImported++
            }

            // Insert all records (including zero-km) as trips for visibility
            val kwhPer100km = if (byd.tripKm > 0) {
                byd.electricityKwh / byd.tripKm * 100.0
            } else null

            val avgSpeed = if (byd.duration > 0 && byd.tripKm > 0) {
                byd.tripKm / (byd.duration / 3600.0)
            } else null

            tripRepository.insertTrip(
                TripEntity(
                    startTs = startTsMs,
                    endTs = endTsMs,
                    distanceKm = byd.tripKm,
                    kwhConsumed = byd.electricityKwh,
                    kwhPer100km = kwhPer100km,
                    avgSpeedKmh = avgSpeed,
                    source = "energydata",
                    bydId = byd.id
                )
            )
            tripsImported++
        }

        settingsRepository.setLastEnergyImportTs(maxTs)

        Log.d(TAG, "Sync done: $tripsImported trips, $idleDrainsImported idle, $skippedDuplicate dups")
        return ImportResult(
            trips = tripsImported,
            idleDrains = idleDrainsImported,
            details = "+$tripsImported поездок, +$idleDrainsImported стоянок, $skippedDuplicate дублей"
        )
    }

    /**
     * Enrich trips that lack SOC data with DiPlus TripInfo matches.
     */
    suspend fun enrichWithDiPlus() {
        try {
            val diplusTrips = diPlusDbReader.readTripInfo()
            if (diplusTrips.isEmpty()) {
                Log.d(TAG, "enrichWithDiPlus: no DiPlus trips available")
                return
            }

            val batteryCapacity = settingsRepository.getBatteryCapacity()
            val tripsWithoutSoc = tripDao.getTripsWithoutSoc()

            var enriched = 0
            for (trip in tripsWithoutSoc) {
                val endTs = trip.endTs ?: continue
                val match = diPlusDbReader.findMatchingTrip(diplusTrips, trip.startTs, endTs) ?: continue

                val socStart = match.socStart.toInt()
                val socEnd = match.socEnd.toInt()
                val kwhConsumed = (match.socStart - match.socEnd) / 100.0 * batteryCapacity
                val kwhPer100km = if (trip.distanceKm != null && trip.distanceKm > 0 && kwhConsumed > 0) {
                    kwhConsumed / trip.distanceKm * 100.0
                } else trip.kwhPer100km

                tripRepository.updateTrip(trip.copy(
                    socStart = socStart,
                    socEnd = socEnd,
                    kwhConsumed = if (kwhConsumed > 0) kwhConsumed else trip.kwhConsumed,
                    kwhPer100km = kwhPer100km,
                    avgSpeedKmh = if (match.avgSpeed > 0) match.avgSpeed else trip.avgSpeedKmh
                ))
                enriched++
            }

            Log.d(TAG, "enrichWithDiPlus: enriched $enriched/${tripsWithoutSoc.size} trips")
        } catch (e: Exception) {
            Log.e(TAG, "enrichWithDiPlus failed", e)
        }
    }

    /**
     * Calculate cost for trips that have kWh but no cost yet.
     */
    suspend fun calculateMissingCosts(tariff: Double) {
        try {
            val trips = tripDao.getTripsWithoutCost()
            var calculated = 0
            for (trip in trips) {
                val kwh = trip.kwhConsumed ?: continue
                tripRepository.updateTrip(trip.copy(cost = kwh * tariff))
                calculated++
            }
            Log.d(TAG, "calculateMissingCosts: $calculated trips, tariff=$tariff")
        } catch (e: Exception) {
            Log.e(TAG, "calculateMissingCosts failed", e)
        }
    }

    /**
     * Attach unattached GPS points to trips by time window.
     */
    suspend fun attachGpsPoints() {
        try {
            val trips = tripRepository.getAllTrips().first()
            var attached = 0
            for (trip in trips) {
                val endTs = trip.endTs ?: continue
                val count = tripPointDao.attachToTrip(trip.id, trip.startTs, endTs)
                if (count > 0) attached += count
            }
            Log.d(TAG, "attachGpsPoints: attached $attached points")
        } catch (e: Exception) {
            Log.e(TAG, "attachGpsPoints failed", e)
        }
    }

    /**
     * Deduplicate existing v1.x live-tracked trips with energydata records.
     * Used during v1.x → v2.0 upgrade.
     */
    suspend fun deduplicateWithExisting() {
        try {
            val bydRecords = energyDataReader.readTrips()
            val liveTrips = tripDao.getLiveTrips()
            var updated = 0
            var inserted = 0

            for (byd in bydRecords) {
                if (byd.duration < MIN_TRIP_DURATION_SEC) continue

                val startTsMs = byd.startTimestamp * 1000L
                val endTsMs = byd.endTimestamp * 1000L

                // Already imported?
                if (tripDao.getByBydId(byd.id) != null) continue

                // Find matching live trip (startTs within ±5 min)
                val match = liveTrips.firstOrNull { live ->
                    kotlin.math.abs(live.startTs - startTsMs) < 300_000L
                }

                if (match != null) {
                    // Update existing trip with energydata ID
                    tripRepository.updateTrip(match.copy(
                        source = "energydata",
                        bydId = byd.id
                    ))
                    updated++
                } else {
                    // Insert as new energydata trip
                    val kwhPer100km = if (byd.tripKm > 0) {
                        byd.electricityKwh / byd.tripKm * 100.0
                    } else null

                    tripRepository.insertTrip(TripEntity(
                        startTs = startTsMs,
                        endTs = endTsMs,
                        distanceKm = byd.tripKm,
                        kwhConsumed = byd.electricityKwh,
                        kwhPer100km = kwhPer100km,
                        source = "energydata",
                        bydId = byd.id
                    ))
                    inserted++
                }
            }

            // Update last import timestamp
            val maxTs = bydRecords.maxOfOrNull { it.startTimestamp } ?: 0L
            if (maxTs > 0) settingsRepository.setLastEnergyImportTs(maxTs)

            Log.d(TAG, "deduplicateWithExisting: updated=$updated, inserted=$inserted")
        } catch (e: Exception) {
            Log.e(TAG, "deduplicateWithExisting failed", e)
        }
    }

    /**
     * One-time cleanup of duplicate trips already in DB.
     * Finds trip pairs with startTs within ±5 min, keeps the best one (with bydId),
     * deletes the rest. Runs once, sets dedup_cleanup_done flag.
     */
    suspend fun cleanupDuplicates(): Int {
        if (settingsRepository.isDedupCleanupDone()) return 0

        return try {
            val allTrips = tripDao.getAllSnapshot() // sorted by start_ts
            var deleted = 0

            // Mark trips to delete: for each pair within ±5 min, keep the better one
            val toDelete = mutableSetOf<Long>()

            for (i in allTrips.indices) {
                if (allTrips[i].id in toDelete) continue

                for (j in i + 1 until allTrips.size) {
                    if (allTrips[j].id in toDelete) continue

                    val diff = allTrips[j].startTs - allTrips[i].startTs
                    if (diff > DEDUP_WINDOW_MS) break // sorted, no more matches possible

                    // Found a duplicate pair
                    val a = allTrips[i]
                    val b = allTrips[j]

                    // Keep the one with bydId (energydata), delete the other
                    val loser = when {
                        a.bydId != null && b.bydId == null -> b
                        b.bydId != null && a.bydId == null -> a
                        a.source == "energydata" && b.source != "energydata" -> b
                        b.source == "energydata" && a.source != "energydata" -> a
                        // Both same quality — keep the first one
                        else -> b
                    }
                    toDelete.add(loser.id)
                }
            }

            for (id in toDelete) {
                tripDao.deleteById(id)
                deleted++
            }

            settingsRepository.setDedupCleanupDone()
            Log.i(TAG, "cleanupDuplicates: deleted $deleted duplicates out of ${allTrips.size} total")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "cleanupDuplicates failed", e)
            0
        }
    }

    /**
     * Legacy sync method (backward compat for Settings import button).
     */
    suspend fun sync(): ImportResult = syncFromEnergyData()

    suspend fun forceImport(): ImportResult = syncFromEnergyData()
}
