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

    /**
     * Event-based sync: check if energydata changed, import new records.
     * Called on service start and app foreground.
     */
    suspend fun syncFromEnergyData(): ImportResult {
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
            val existing = tripDao.getByBydId(byd.id)
            if (existing != null) {
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
     * Legacy sync method (backward compat for Settings import button).
     */
    suspend fun sync(): ImportResult = syncFromEnergyData()

    suspend fun forceImport(): ImportResult = syncFromEnergyData()
}
