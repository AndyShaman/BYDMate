package com.bydmate.app.data.local

import android.content.Context
import android.util.Log
import com.bydmate.app.data.local.dao.CounterSnapshotDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.OdometerMarks
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.data.trips.CounterSnapshotWriter
import com.bydmate.app.data.trips.TripFuel
import com.bydmate.app.domain.cost.CostCalculator
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryImporter @Inject @Suppress("LongParameterList") constructor( // Hilt-injected dependencies
    @ApplicationContext private val context: Context,
    private val energyDataReader: EnergyDataReader,
    private val tripRepository: TripRepository,
    private val tripDao: TripDao,
    private val tripPointDao: TripPointDao,
    private val idleDrainDao: IdleDrainDao,
    private val settingsRepository: SettingsRepository,
    private val lastSessionRepository: LastSessionRepository,
    private val tripTombstoneDao: TripTombstoneDao,
    private val costCalculator: CostCalculator,
    private val odometerMarks: OdometerMarks,
    private val vehicleKind: VehicleKindMonitor,
    private val counterSnapshotDao: CounterSnapshotDao,
    private val counterSnapshotWriter: CounterSnapshotWriter,
    private val lastStateDao: LastStateDao,
) {
    companion object {
        private const val TAG = "HistoryImporter"
        private const val MIN_TRIP_DURATION_SEC = 30L
        private const val DEDUP_WINDOW_MS = 300_000L // ±5 min for time-based dedup
        // Mirror of TrackingService.SESSION_IDLE_CLOSE_MS — the idle gap after which an
        // open SOC session is considered finished and promotable to a completed bookmark.
        private const val PENDING_SESSION_IDLE_CLOSE_MS = 10_000L

        /**
         * An energydata row starting later than now + this is from the future (head unit clock
         * jumped forward, #348). It must never become the import watermark: every real trip
         * after it would start "before" the watermark and be skipped forever. Kept small, since
         * an accepted row at now + skew hides every trip that starts within that skew. A skipped
         * row is not lost: the watermark stays below it, so it is read again once the clock
         * passes it.
         */
        internal const val FUTURE_SKEW_SEC = 60L

        internal fun isFromFuture(startTimestampSec: Long, nowSec: Long): Boolean =
            startTimestampSec > nowSec + FUTURE_SKEW_SEC

        /** The importer line of the log dump: watermark and the energydata rows it reads (#348). */
        internal fun importStateLine(watermarkSec: Long, rows: List<BydTripRecord>, nowSec: Long): String {
            val latest = rows.maxOfOrNull { it.startTimestamp }
            val future = rows.count { isFromFuture(it.startTimestamp, nowSec) }
            return "import: watermark=$watermarkSec (${dumpDate(watermarkSec)}) rows=${rows.size} " +
                "latest_start=${latest ?: "-"} (${latest?.let(::dumpDate) ?: "-"}) future_rows=$future"
        }

        private fun dumpDate(sec: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(sec * 1000L))

        /** A counter snapshot belongs to a trip edge only this close to it (DM-i, #184). */
        internal const val COUNTER_EDGE_WINDOW_MS = 90_000L

        /** Only trips this recent are offered counters; snapshots are kept 90 days, so it is safe. */
        internal const val COUNTER_ATTACH_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000

        /** A just-finished trip may borrow the live outside temperature; an older one may not. */
        internal const val FRESH_TEMP_WINDOW_MS = 10 * 60 * 1000L

        /**
         * Outside temperature to store on an imported energydata trip. energydata itself
         * carries no temperature, so the only honest source is the live snapshot — and only
         * while the drive has just ended; anything older gets null instead of a wrong number.
         */
        internal fun importedExteriorTemp(endTsMs: Long, nowMs: Long, liveTemp: Int?): Int? =
            liveTemp?.takeIf { nowMs >= endTsMs && nowMs - endTsMs <= FRESH_TEMP_WINDOW_MS }

        /**
         * End temperature for an imported trip: the reading the matched driving session
         * recorded while the car was on, else the live-snapshot fallback above (which only
         * fires for a drive that has just ended).
         */
        internal fun importedTempEnd(
            sessionTemp: Int?,
            endTsMs: Long,
            nowMs: Long,
            liveTemp: Int?,
        ): Int? = sessionTemp ?: importedExteriorTemp(endTsMs, nowMs, liveTemp)
    }

    private val syncMutex = Mutex()

    /** Set by doSync when it skipped a row from the future; guarded by [syncMutex]. */
    private var skippedFutureRow = false

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
            // A watermark already in the future (set before #348 was fixed) heals here, before the
            // unchanged-file shortcut: the file may stay untouched until the next drive ends.
            // The full re-read is safe: imported rows stop at bydId, deleted ones at the tombstone.
            val watermark = settingsRepository.getLastEnergyImportTs()
            val healed = isFromFuture(watermark, System.currentTimeMillis() / 1000L)
            if (healed) {
                Log.w(TAG, "syncFromEnergyData: watermark $watermark is in the future, resetting")
                clearImportWatermark()
            }
            if (!healed && !energyDataReader.peekSourceChanged(context)) {
                Log.d(TAG, "syncFromEnergyData: source unchanged, skipping")
                return ImportResult(trips = 0, details = "Без изменений")
            }
            skippedFutureRow = false
            val result = doSync()
            // Mark only on success — a thrown EnergyDataException leaves the
            // watermark untouched so the next event retries this file. A skipped future row
            // (#348) leaves it unmarked too: the next sync re-reads the file once the clock is right.
            if (!result.isError && !skippedFutureRow) energyDataReader.markSourceProcessed(context)
            result
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
        // SOC enrichment ordering guard: a trip finishes in one process lifetime but is
        // imported in a later one, and the DiLink head unit dies instantly at ignition-off
        // (onSessionEnd rarely fires). The prior drive's SOC bookmark is therefore left as a
        // stale `pending` on disk, promoted to `completed` only by reconcileStaleOpenSession.
        // That promotion lives in TrackingService.onCreate, but runSync() also runs from
        // BYDMateApp.onCreate (before the service reconcile) and from Settings/Welcome — so an
        // import could match (takeMatch searches only `completed`) before the bookmark is
        // promoted, write socStart/socEnd=null, and the watermark below would lock that loss
        // in forever. Reconcile here so every import path promotes the stale pending first.
        lastSessionRepository.reconcileStaleOpenSession(
            System.currentTimeMillis(), PENDING_SESSION_IDLE_CLOSE_MS)

        val lastImportTs = settingsRepository.getLastEnergyImportTs()
        // Until recycling is known the whole source is read: the records it hid already sit below
        // the watermark, and the next ids may never collide again (#348). The batch is cut from it.
        val remembered = settingsRepository.isEnergyIdsRecycled()
        val fullSource = if (remembered) null else energyDataReader.readTripsSince(0L)
        val nowSec = System.currentTimeMillis() / 1000L
        val firstDetected = fullSource != null && sourceRecycled(
            fullSource.filterNot { isFromFuture(it.startTimestamp, nowSec) }, lastImportTs,
        ) { clearImportWatermark() }
        val sourceRecycled = remembered || firstDetected
        val bydRecords = when {
            // One-time catch-up: this sync imports every record of the recreated source. Imported
            // rows stop at bydId, deleted ones at a tombstone with their start.
            firstDetected && fullSource != null -> fullSource.also {
                Log.w(TAG, "energydata source recycled, first detection: watermark $lastImportTs reset, " +
                    "re-reading all ${it.size} rows")
            }
            fullSource != null -> fullSource.filter { it.startTimestamp > lastImportTs }
            else -> energyDataReader.readTripsSince(lastImportTs)
        }
        // SOC enrichment uses per-trip session bookmarks (lastSessionRepository.takeMatch
        // below). Each imported trip consumes at most one bookmark, so a batch import matches
        // each trip to its own driving session instead of sharing one snapshot.

        if (bydRecords.isEmpty()) {
            return ImportResult(trips = 0, details = "Нет новых записей")
        }

        var tripsImported = 0
        var idleDrainsImported = 0
        var skippedDuplicate = 0
        var recycledIds = 0
        var implausibleKwh = 0
        var tempFromSession = 0
        var maxTs = if (firstDetected) 0L else lastImportTs
        val capacity = settingsRepository.getBatteryCapacity()
        // One read of the live snapshot for the whole batch: the drive that just ended is the
        // only one close enough in time for this temperature to belong to it.
        val nowMs = System.currentTimeMillis()
        val liveExteriorTemp = TrackingService.lastData.value?.exteriorTemp
        val marks = odometerMarks.snapshot()

        for (byd in bydRecords) {
            // Before maxTs and before any filter: a future row must not move the watermark (#348)
            if (isFromFuture(byd.startTimestamp, nowMs / 1000L)) {
                Log.w(TAG, "energydata row from the future skipped: id=${byd.id} start=${byd.startTimestamp}")
                skippedFutureRow = true
                continue
            }
            val startTsMs = byd.startTimestamp * 1000L
            val endTsMs = byd.endTimestamp * 1000L

            // Track max timestamp for next sync
            if (byd.startTimestamp > maxTs) maxTs = byd.startTimestamp

            // Skip very short sessions
            if (byd.duration < MIN_TRIP_DURATION_SEC) continue

            // Check duplicate by byd_id; a recycled id falls through to the checks below
            val existingById = tripDao.getByBydId(byd.id)
            if (existingById != null && !isRecycledId(existingById, byd)) {
                skippedDuplicate++
                continue
            }
            if (existingById != null) {
                releaseRecycledId(existingById, byd)
                recycledIds++
            }

            // Manually deleted by the user — never re-import (issue #81)
            if (isTombstoned(byd, sourceRecycled)) {
                skippedDuplicate++
                continue
            }

            // Check duplicate by time overlap (catches v1.x live trips without byd_id)
            val existingByTime = tripDao.getByStartTsRange(
                startTsMs - DEDUP_WINDOW_MS,
                startTsMs + DEDUP_WINDOW_MS
            )
            if (existingByTime != null) {
                // Update existing trip with byd_id so future syncs skip it instantly.
                // The copy keeps its odometer: the km changes, the readings do not.
                val kwh = saneKwh(
                    byd.id, byd.electricityKwh, byd.tripKm,
                    existingByTime.socStart, existingByTime.socEnd, capacity)
                if (kwh != byd.electricityKwh) implausibleKwh++
                val per100 = TripEnergySanity.per100For(kwh, byd.tripKm)
                tripRepository.updateTrip(existingByTime.copy(
                    source = "energydata",
                    bydId = byd.id,
                    distanceKm = byd.tripKm,
                    kwhConsumed = kwh,
                    kwhPer100km = per100
                ))
                skippedDuplicate++
                continue
            }

            val isIdleDrain = byd.tripKm == 0.0

            if (isIdleDrain) {
                // Same bound as the trip copy below; at zero km only the negative and absolute
                // rules can fire, and a drain record has no SOC pair to fall back on. The
                // parking window is kept either way (the hours statistics live off the
                // timestamps) — only the impossible kWh is dropped, exactly as the one-time
                // repair does for records already on disk.
                val drainKwh = if (TripEnergySanity.isPlausible(byd.electricityKwh, 0.0)) {
                    byd.electricityKwh
                } else {
                    Log.w(TAG, "energydata kwh implausible: id=${byd.id} " +
                        "kwh=${byd.electricityKwh} km=0 -> cleared (idle)")
                    implausibleKwh++
                    null
                }
                idleDrainDao.insert(
                    IdleDrainEntity(
                        startTs = startTsMs,
                        endTs = endTsMs,
                        kwhConsumed = drainKwh
                    )
                )
                idleDrainsImported++
            }

            // avgSpeedKmh: pure computation from energydata distance + duration.
            val avgSpeed = if (byd.duration > 0 && byd.tripKm > 0) {
                byd.tripKm / (byd.duration / 3600.0)
            } else null

            // SOC enrichment: match this trip's endTs to a recorded driving session and
            // graft its SOC bookmarks. Containment on endTs (within [sessionStart ..
            // sessionEnd + 30s]) avoids enriching trips that ended before the session
            // started; takeMatch consumes the bookmark so it can't bind to another trip in
            // the same batch. Null when no session window contains endTs.
            val sessionMatch = lastSessionRepository.takeMatch(endTsMs)
            val socStart = sessionMatch?.startSoc
            val socEnd = sessionMatch?.endSoc
            // The session bookmark carries the outside temperature too — energydata has
            // none, and the live fid is only readable while the car is on.
            val tempStart = sessionMatch?.startExteriorTemp
            val tempEnd = importedTempEnd(
                sessionMatch?.endExteriorTemp, endTsMs, nowMs, liveExteriorTemp)
            if (tempStart != null || sessionMatch?.endExteriorTemp != null) tempFromSession++

            // energydata has no odometer either: the finish comes from the live marks.
            val odo = OdometerMarks.match(marks, startTsMs, endTsMs, byd.tripKm)
            Log.i(TAG, "import odo: byd=${byd.id} end=$endTsMs mark=${odo.markChangeTs ?: "-"} " +
                "finish=${odo.finishKm ?: "-"} start=${odo.startKm ?: "-"} (${odo.reason})")

            // Insert all records (including zero-km) as trips for visibility. The SOC pair
            // read above is what the sanity bound falls back on when BYD's own kWh is impossible.
            val kwh = saneKwh(byd.id, byd.electricityKwh, byd.tripKm, socStart, socEnd, capacity)
            if (kwh != byd.electricityKwh) implausibleKwh++
            val kwhPer100km = TripEnergySanity.per100For(kwh, byd.tripKm)

            tripRepository.insertTrip(
                TripEntity(
                    startTs = startTsMs,
                    endTs = endTsMs,
                    distanceKm = byd.tripKm,
                    kwhConsumed = kwh,
                    kwhPer100km = kwhPer100km,
                    avgSpeedKmh = avgSpeed,
                    socStart = socStart,
                    socEnd = socEnd,
                    exteriorTemp = tempStart,
                    exteriorTempEnd = tempEnd,
                    odometerStartKm = odo.startKm,
                    odometerEndKm = odo.finishKm,
                    source = "energydata",
                    bydId = byd.id
                )
            )
            tripsImported++
        }

        settingsRepository.setLastEnergyImportTs(maxTs)

        Log.d(TAG, "Sync done: $tripsImported trips, $idleDrainsImported idle, $skippedDuplicate dups, " +
            "$recycledIds recycled ids (source recycled=$sourceRecycled), $implausibleKwh implausible kwh")
        Log.i(TAG, "import temp: from_session=$tempFromSession/$tripsImported " +
            "live=${liveExteriorTemp ?: "-"} window=${FRESH_TEMP_WINDOW_MS / 60_000}min")
        return ImportResult(
            trips = tripsImported,
            idleDrains = idleDrainsImported,
            details = "+$tripsImported поездок, +$idleDrainsImported стоянок, $skippedDuplicate дублей"
        )
    }

    /**
     * energydata `_id` restarts from 1 when the car recreates its DB (reset, firmware update, a
     * backup restored onto it), while the trips table keeps the old ids (#348). A real match
     * starts within [DEDUP_WINDOW_MS] of the record (energydata trips store its start, live trips
     * were linked inside that window); a far-off start means the id was recycled.
     */
    private fun isRecycledId(existing: TripEntity, byd: BydTripRecord): Boolean =
        kotlin.math.abs(existing.startTs - byd.startTimestamp * 1000L) > DEDUP_WINDOW_MS

    /** Unlinks the old trip from a recycled id, so later lookups and tombstones by id mean the new record. */
    private suspend fun releaseRecycledId(existing: TripEntity, byd: BydTripRecord) {
        Log.w(TAG, "energydata id recycled: id=${byd.id} start=${byd.startTimestamp * 1000L} " +
            "held by trip=${existing.id} start=${existing.startTs} -> old trip unlinked, record not a duplicate")
        tripDao.unlinkBydId(existing.id, byd.id)
    }

    /**
     * Detected recycling is remembered: after old trips are unlinked no signal is left, yet a legacy
     * tombstone must keep being ignored for the recreated DB. [records] is the whole source without
     * future rows. [onDetected] runs once, before the flag is stored, so a crash in between repeats it.
     */
    private suspend fun sourceRecycled(
        records: List<BydTripRecord>,
        watermarkSec: Long,
        onDetected: suspend () -> Unit = {},
    ): Boolean {
        if (settingsRepository.isEnergyIdsRecycled()) return true
        val linked = tripDao.getAllSnapshot().filter { it.bydId != null }
        if (!hasRecycledIds(records, linked) && !idsWentBack(records, linked, watermarkSec)) return false
        onDetected()
        settingsRepository.setEnergyIdsRecycled()
        return true
    }

    /**
     * Any recycled id proves the source DB was recreated: then a tombstone without a start time
     * (written before schema 24) belongs to an old trip. Checked before the import loop releases
     * the ids, so the order of records does not matter.
     */
    private fun hasRecycledIds(records: List<BydTripRecord>, linked: List<TripEntity>): Boolean {
        val byId = linked.associateBy { it.bydId }
        val recycled = records.count { byd -> byId[byd.id]?.let { isRecycledId(it, byd) } == true }
        if (recycled > 0) Log.w(TAG, "energydata source recycled: $recycled/${records.size} ids held by older trips")
        return recycled > 0
    }

    /**
     * energydata ids only grow (rows are soft-deleted, never removed), so a record newer than every
     * trip and tombstone we know, yet with an id not above the highest one we know, means the car
     * recreated its DB (#348). Unlike a collision it needs no trip still holding the id: the reused
     * id may belong only to a deleted trip. Records of an unchanged source are never newer than the
     * trips they became, and one never imported (too short) has a higher id. A row the clock wrote
     * in the future and read once the clock caught up can fire it: the source then still holds the
     * trip with the highest id at its own start, and that keeps the verdict false.
     *
     * [watermarkSec] stands in for the latest known start when no known trip or tombstone has one.
     */
    private suspend fun idsWentBack(records: List<BydTripRecord>, linked: List<TripEntity>, watermarkSec: Long): Boolean {
        val anchor = linked.maxByOrNull { it.bydId ?: Long.MIN_VALUE }
        val maxId = listOfNotNull(anchor?.bydId, tripTombstoneDao.maxBydId()).maxOrNull() ?: return false
        val latestStartMs = listOfNotNull(linked.maxOfOrNull { it.startTs }, tripTombstoneDao.maxStartTs()).maxOrNull()
        // The window: a live trip linked by time overlap may start that far from its record
        val newerThanSec = latestStartMs?.let { (it + DEDUP_WINDOW_MS) / 1000L }
            ?: watermarkSec.takeIf { it > 0L }
            ?: return false
        val record = records.firstOrNull { it.id <= maxId && it.startTimestamp > newerThanSec } ?: return false
        if (anchor != null && records.any { it.id == anchor.bydId && !isRecycledId(anchor, it) }) {
            Log.w(TAG, "energydata id went back: id=${record.id} start=${record.startTimestamp} newer than " +
                "$newerThanSec, but source still holds trip=${anchor.id} id=${anchor.bydId} -> not recycled")
            return false
        }
        Log.w(TAG, "energydata source recycled: id=${record.id} start=${record.startTimestamp} newer than " +
            "every known trip ($newerThanSec), ids known up to $maxId, source lost trip=${anchor?.id ?: "-"}")
        return true
    }

    /** Deleted by the user (issue #81): a tombstone hides only the trip it was left for (#348). */
    private suspend fun isTombstoned(byd: BydTripRecord, sourceRecycled: Boolean): Boolean {
        if (!tripTombstoneDao.exists(byd.id)) return false
        val startTsMs = byd.startTimestamp * 1000L
        if (tripTombstoneDao.isForAnotherTrip(byd.id, startTsMs, DEDUP_WINDOW_MS, sourceRecycled)) {
            Log.w(TAG, "tombstone for id=${byd.id} left by another trip, record start=$startTsMs " +
                "imported (source recycled=$sourceRecycled)")
            return false
        }
        return true
    }

    /**
     * Sanity gate for every energydata kWh copied into a trip: BYD sometimes writes a value
     * this battery cannot deliver, and one such record blows up the week/month statistics
     * (VadimV, 2026-09-12). Returns the value to store and logs each replacement.
     */
    private fun saneKwh(
        bydId: Long?,
        raw: Double,
        km: Double?,
        socStart: Int?,
        socEnd: Int?,
        capacity: Double,
    ): Double? {
        // DM-i: the engine charges the pack on the way, so a SOC delta is no trip figure.
        val sane = if (vehicleKind.kind.value == VehicleKind.HYBRID) {
            raw.takeIf { TripEnergySanity.isPlausible(it, km) }
        } else {
            TripEnergySanity.kwhFor(raw, km, socStart, socEnd, capacity)
        }
        if (sane != raw) {
            Log.w(TAG, "energydata kwh implausible: id=$bydId kwh=$raw km=$km " +
                "soc=$socStart->$socEnd -> ${sane ?: "null"}")
        }
        return sane
    }

    /**
     * One-time repair of trips stored before the plausibility bound existed: an impossible
     * kWh value becomes the SOC-delta estimate, or is cleared when SOC is unknown. Cost is
     * dropped so calculateMissingCosts() recomputes it. Idle drains keep their parking window
     * (the hours statistics live off the timestamps) and only lose the impossible kWh.
     * Runs once, sets the sanity flag.
     */
    suspend fun repairImplausibleKwh(): Int {
        if (settingsRepository.isEnergyKwhSanityDone()) return 0

        return try {
            val allTrips = tripDao.getAllSnapshot()
            val capacity = settingsRepository.getBatteryCapacity()
            var repaired = 0
            var examined = 0

            for (trip in allTrips) {
                // Only BYD's own records: a NATIVE_POLLING trip carries TripRecorder's own
                // counter delta, which this bound knows nothing about.
                if (trip.source != "energydata") continue
                examined++
                val raw = trip.kwhConsumed ?: continue
                val sane = saneKwh(
                    trip.bydId, raw, trip.distanceKm, trip.socStart, trip.socEnd, capacity)
                if (sane == raw) continue
                tripRepository.updateTrip(trip.copy(
                    kwhConsumed = sane,
                    kwhPer100km = TripEnergySanity.per100For(sane, trip.distanceKm),
                    cost = null // will be recalculated by calculateMissingCosts()
                ))
                repaired++
            }

            var repairedIdle = 0
            for (drain in idleDrainDao.getAll()) {
                val raw = drain.kwhConsumed ?: continue
                if (TripEnergySanity.isPlausible(raw, 0.0)) continue
                Log.w(TAG, "energydata kwh implausible: id=${drain.id} kwh=$raw km=0 -> cleared (idle)")
                idleDrainDao.update(drain.copy(kwhConsumed = null))
                repairedIdle++
            }

            settingsRepository.setEnergyKwhSanityDone()
            Log.i(TAG, "energydata kwh sanity: repaired $repaired/$examined trips, $repairedIdle idle")
            repaired
        } catch (e: Exception) {
            Log.e(TAG, "energydata kwh sanity failed", e)
            0
        }
    }

    /**
     * Fill the odometer of energydata trips imported before their marks were usable (an
     * earlier sync in the same window). Only trips inside the marks window qualify, so old
     * history is never backfilled; a known odometer is never overwritten.
     */
    suspend fun fillOdometerFromMarks() {
        try {
            val marks = odometerMarks.snapshot()
            val since = marks.minOfOrNull { it.processStartTs } ?: return
            var filled = 0
            val candidates = tripDao.getAllSnapshot().filter {
                it.source == "energydata" && (it.endTs ?: 0L) >= since &&
                    it.odometerStartKm == null && it.odometerEndKm == null
            }
            for (trip in candidates) {
                if (fillOdometer(trip, marks)) filled++
            }
            if (filled > 0) Log.i(TAG, "fillOdometerFromMarks: $filled trips")
        } catch (e: Exception) {
            Log.e(TAG, "fillOdometerFromMarks failed", e)
        }
    }

    /** True when the row was actually written. */
    private suspend fun fillOdometer(trip: TripEntity, marks: List<OdometerMarks.Mark>): Boolean {
        val endTs = trip.endTs ?: return false
        val odo = OdometerMarks.match(marks, trip.startTs, endTs, trip.distanceKm)
        if (odo.finishKm == null) return false
        // Conditional write: a sync or a cost recalculation since the snapshot is kept.
        if (tripDao.fillOdometerIfEmpty(trip.id, odo.startKm, odo.finishKm) == 0) return false
        Log.i(TAG, "fill odo: trip=${trip.id} end=$endTs mark=${odo.markChangeTs} " +
            "finish=${odo.finishKm} start=${odo.startKm ?: "-"} (${odo.reason})")
        return true
    }

    /**
     * Calculate cost for trips that have kWh but no cost yet. Each trip is priced by the
     * tariff period covering its own date, so a backfilled import lands at the rate that
     * was in force back then, not at today's one.
     */
    suspend fun calculateMissingCosts() {
        try {
            val hybrid = vehicleKind.kind.value == VehicleKind.HYBRID
            val trips = if (hybrid) tripDao.getHybridTripsWithoutCost() else tripDao.getTripsWithoutCost()
            val calculated = costCalculator.priceUncostedTrips(trips)
            Log.d(TAG, "calculateMissingCosts: $calculated trips")
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
     * DM-i only: gives energydata trips the fuel counters of a snapshot pair taken at their own
     * start and end (within ±[COUNTER_EDGE_WINDOW_MS]). No pair, no litres: a snapshot further
     * away is never used and litres are never spread between trips.
     */
    suspend fun attachCounters() {
        if (vehicleKind.kind.value != VehicleKind.HYBRID) return
        try {
            var attached = 0
            val since = System.currentTimeMillis() - COUNTER_ATTACH_LOOKBACK_MS
            for (trip in tripDao.getEnergyDataTripsWithoutCounters(since)) {
                val endTs = trip.endTs ?: continue
                val start = counterSnapshotDao.nearest(trip.startTs, COUNTER_EDGE_WINDOW_MS) ?: continue
                val end = counterSnapshotDao.nearest(endTs, COUNTER_EDGE_WINDOW_MS) ?: continue
                if (end.ts <= start.ts) continue
                tripDao.update(
                    trip.copy(
                        fuelLiters = TripFuel.liters(start.fuelTotal, end.fuelTotal),
                        hevKm = TripFuel.hevKm(start.mileageHev, end.mileageHev, trip.distanceKm),
                        fuelTotalStart = start.fuelTotal,
                        fuelTotalEnd = end.fuelTotal,
                        mileageHevStart = start.mileageHev,
                        mileageHevEnd = end.mileageHev,
                    )
                )
                attached++
            }
            Log.d(TAG, "attachCounters: $attached trips")
        } catch (e: Exception) {
            Log.e(TAG, "attachCounters failed", e)
        }
    }

    /**
     * Deduplicate existing v1.x live-tracked trips with energydata records.
     * Used during v1.x → v2.0 upgrade.
     */
    suspend fun deduplicateWithExisting() {
        try {
            // Future rows neither import nor set the watermark below (#348)
            val nowSec = System.currentTimeMillis() / 1000L
            val bydRecords = energyDataReader.readTrips().filterNot { isFromFuture(it.startTimestamp, nowSec) }
            val liveTrips = tripDao.getLiveTrips()
            val capacity = settingsRepository.getBatteryCapacity()
            var updated = 0
            var inserted = 0
            // A full read already, but it may not finish: the first detection resets the watermark
            // before the flag is stored, so the next sync still re-reads everything (#348)
            val sourceRecycled = sourceRecycled(bydRecords, 0L) { clearImportWatermark() }

            for (byd in bydRecords) {
                if (byd.duration < MIN_TRIP_DURATION_SEC) continue

                val startTsMs = byd.startTimestamp * 1000L
                val endTsMs = byd.endTimestamp * 1000L

                // Already imported? A recycled id is not (#348)
                val existingById = tripDao.getByBydId(byd.id)
                if (existingById != null && !isRecycledId(existingById, byd)) continue
                if (existingById != null) releaseRecycledId(existingById, byd)

                // Manually deleted by the user — never re-import (issue #81)
                if (isTombstoned(byd, sourceRecycled)) continue

                // Find matching live trip (startTs within ±5 min)
                val match = liveTrips.firstOrNull { live ->
                    kotlin.math.abs(live.startTs - startTsMs) < DEDUP_WINDOW_MS
                }

                if (match != null) {
                    // Update existing trip with energydata ID + authoritative BMS values
                    val kwh = saneKwh(
                        byd.id, byd.electricityKwh, byd.tripKm,
                        match.socStart, match.socEnd, capacity)
                    val per100 = TripEnergySanity.per100For(kwh, byd.tripKm)
                    tripRepository.updateTrip(match.copy(
                        source = "energydata",
                        bydId = byd.id,
                        distanceKm = byd.tripKm,
                        kwhConsumed = kwh,
                        kwhPer100km = per100
                    ))
                    updated++
                } else {
                    // Insert as new energydata trip
                    val kwh = saneKwh(
                        byd.id, byd.electricityKwh, byd.tripKm, null, null, capacity)
                    val kwhPer100km = TripEnergySanity.per100For(kwh, byd.tripKm)

                    tripRepository.insertTrip(TripEntity(
                        startTs = startTsMs,
                        endTs = endTsMs,
                        distanceKm = byd.tripKm,
                        kwhConsumed = kwh,
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
     * One-time recalculation of kwhConsumed/kwhPer100km for ALL existing trips
     * from energydata BMS electricity values.
     * Previous versions incorrectly overwrote BMS consumption with SOC-delta estimates.
     * Runs once, sets consumption_recalc_done flag.
     */
    suspend fun recalculateConsumptionFromEnergyData(): Int {
        if (settingsRepository.isConsumptionRecalcDone()) return 0
        // No folder (Song, Atto: #301): nothing to recalculate, not a failure. The flag stays unset,
        // so a Leopard 3 whose folder is only missing right now still gets its recalculation.
        if (!energyDataReader.energyDataDir().exists()) {
            Log.d(TAG, "recalculateConsumption: no energydata directory, skipped")
            return 0
        }

        return try {
            val bydRecords = energyDataReader.readTrips()
            if (bydRecords.isEmpty()) {
                Log.d(TAG, "recalculateConsumption: no energydata records")
                settingsRepository.setConsumptionRecalcDone()
                return 0
            }

            val allTrips = tripDao.getAllSnapshot()
            val capacity = settingsRepository.getBatteryCapacity()
            var updated = 0

            for (trip in allTrips) {
                // Match by bydId first; a recycled id carries another trip's kWh (#348)
                val match = if (trip.bydId != null) {
                    bydRecords.firstOrNull { it.id == trip.bydId && !isRecycledId(trip, it) }
                } else {
                    // Fallback: match by start_timestamp ±5 min
                    val startTsSec = trip.startTs / 1000L
                    bydRecords.firstOrNull { byd ->
                        kotlin.math.abs(byd.startTimestamp - startTsSec) < 300L
                    }
                } ?: continue

                val oldKwh = trip.kwhConsumed
                val newKwh = saneKwh(
                    match.id, match.electricityKwh, trip.distanceKm,
                    trip.socStart, trip.socEnd, capacity)
                val newPer100 = TripEnergySanity.per100For(newKwh, trip.distanceKm)

                // Only update if values actually differ
                if (oldKwh != newKwh) {
                    tripRepository.updateTrip(trip.copy(
                        kwhConsumed = newKwh,
                        kwhPer100km = newPer100,
                        cost = null // will be recalculated by calculateMissingCosts()
                    ))
                    updated++
                }
            }

            settingsRepository.setConsumptionRecalcDone()
            Log.i(TAG, "recalculateConsumption: updated $updated/${allTrips.size} trips from energydata BMS")
            updated
        } catch (e: Exception) {
            Log.e(TAG, "recalculateConsumption failed", e)
            0
        }
    }

    /**
     * Re-read energydata from the start on the next sync (Settings reset, #348). Rows already
     * imported stop at bydId, deleted ones at the tombstone.
     */
    suspend fun resetImportWatermark() = syncMutex.withLock { clearImportWatermark() }

    /** Import watermark to 0 and the source fingerprint forgotten, so the unchanged-file gate passes. */
    private suspend fun clearImportWatermark() {
        settingsRepository.setLastEnergyImportTs(0L)
        energyDataReader.resetWatermark(context)
    }

    /** One line for the log dump: watermark and the energydata rows behind it (#348). */
    suspend fun importStateLine(): String = syncMutex.withLock {
        importStateLine(
            settingsRepository.getLastEnergyImportTs(),
            energyDataReader.readTrips(),
            System.currentTimeMillis() / 1000L,
        )
    }

    /**
     * One-time cleanup: remove inflated idle_drain records from live power integration
     * and re-import from energydata BMS (accurate zero-km records).
     * Runs once, sets idle_drain_v2_cleanup flag.
     */
    suspend fun cleanupIdleDrainV2(): Int {
        if (settingsRepository.isIdleDrainV2CleanupDone()) return 0

        return try {
            // Delete all idle_drain records (inflated from live power integration)
            idleDrainDao.deleteAll()

            // Delete zero-km trips so they get re-imported with correct idle_drain records
            val deleted = tripDao.deleteZeroKmTrips()

            // Reset import timestamp so energydata records are re-processed.
            // Non-zero trips won't duplicate (bydId check), only deleted zero-km ones re-import.
            settingsRepository.setLastEnergyImportTs(0L)
            // Force change-detector to fire on next sync — otherwise the gate in
            // syncFromEnergyData() skips this run when the source file did not
            // change since the previous successful sync, leaving idle drains gone.
            energyDataReader.resetWatermark(context)

            settingsRepository.setIdleDrainV2CleanupDone()
            Log.i(TAG, "cleanupIdleDrainV2: cleared idle_drains, deleted $deleted zero-km trips, reset import ts")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "cleanupIdleDrainV2 failed", e)
            0
        }
    }

    /**
     * Unified sync entry point — always uses energydata pipeline.
     */
    suspend fun runSync(): ImportResult {
        // runSync starts at app and service start, before the poll loop has loaded the cached car
        // type: an UNKNOWN kind would SOC-repair a hybrid trip's kWh for good. Then (DM-i) the end
        // edge of the trip that ended at power-off, before attachCounters() looks for it.
        runCatching { vehicleKind.awaitCache() }
            .onFailure { Log.w(TAG, "vehicle kind cache load failed", it) }
        val kind = vehicleKind.kind.value
        if (kind == VehicleKind.HYBRID) {
            runCatching { counterSnapshotWriter.onColdStart(lastStateDao.getCurrent(), kind) }
                .onFailure { Log.w(TAG, "CounterSnapshotWriter.onColdStart failed", it) }
        }
        cleanupIdleDrainV2()
        val r = syncFromEnergyData()
        recalculateConsumptionFromEnergyData()
        repairImplausibleKwh()
        fillOdometerFromMarks()
        // Before the costs: a trip priced without its litres is never priced again.
        attachCounters()
        calculateMissingCosts()
        attachGpsPoints()
        return r
    }

}
