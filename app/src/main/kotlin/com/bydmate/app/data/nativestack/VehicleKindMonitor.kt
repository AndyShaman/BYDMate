package com.bydmate.app.data.nativestack

import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the car type ([VehicleKindDetector]) for the whole app. [kind] starts UNKNOWN and turns
 * EV or HYBRID from the cache or from a confirmation in this run; UNKNOWN never overwrites it.
 *
 * Reads run in rounds: a round starts at the service start, then takes one read per poll tick
 * at most every [VehicleKindConfirmation.MIN_GAP_MS] until two identical results confirm the
 * type or [MAX_ATTEMPTS] reads are spent; the next round starts [RECHECK_MS] after the last one.
 */
@Singleton
class VehicleKindMonitor @Inject constructor(
    private val settings: SettingsRepository,
    private val helperClient: HelperClient,
) {
    private val _kind = MutableStateFlow(VehicleKind.UNKNOWN)
    val kind: StateFlow<VehicleKind> = _kind.asStateFlow()

    private val lock = Any()
    private val confirmation = VehicleKindConfirmation()
    @Volatile private var cacheLoaded = false
    @Volatile private var lastEvidence: String? = null
    private var attempts = 0
    private var lastAttemptAtMs = 0L
    private var roundStartedAtMs = 0L
    private var roundDone = false
    private var inFlight = false

    /** Loads the cache and opens a fresh round with an immediate read. */
    suspend fun onServiceStart(nowMs: Long) {
        loadCache()
        synchronized(lock) { startRound(nowMs) }
        if (claimProbe(nowMs)) probe(nowMs)
    }

    /**
     * Cheap and non-suspending, for the poll tick: true when a read is due now, and the read is
     * then reserved for the caller, who must run [probe].
     */
    fun claimProbe(nowMs: Long): Boolean = synchronized(lock) {
        if (inFlight) return false
        if (roundDone) {
            if (nowMs - roundStartedAtMs < RECHECK_MS) return false
            startRound(nowMs)
        }
        if (attempts > 0 && nowMs - lastAttemptAtMs < VehicleKindConfirmation.MIN_GAP_MS) return false
        attempts++
        lastAttemptAtMs = nowMs
        inFlight = true
        true
    }

    /** One batch read of the three fids; persists the type when it confirms. */
    @Suppress("TooGenericExceptionCaught") // runs on serviceScope, which has no exception handler
    suspend fun probe(nowMs: Long) {
        try {
            val reply = helperClient.readBatch(ITEMS)
            val hev = reply?.getOrNull(0)?.let { (s, v) -> VehicleKindDetector.word(s, v) }
            val ev = reply?.getOrNull(1)?.let { (s, v) -> VehicleKindDetector.word(s, v) }
            val fuel = reply?.getOrNull(2)?.let { (s, v) -> VehicleKindDetector.word(s, v) }
            val evidence = VehicleKindDetector.evidence(hev, ev, fuel)
            val read = VehicleKindDetector.classify(hev, ev, fuel)
            lastEvidence = evidence
            val confirmed = synchronized(lock) { confirmation.offer(read, nowMs) }
            Log.i(TAG, "probe attempt=$attempts read=$read $evidence batch=${reply != null} confirmed=$confirmed")
            if (confirmed != null) {
                settings.setStrings(
                    mapOf(
                        SettingsRepository.KEY_VEHICLE_KIND to confirmed.name,
                        SettingsRepository.KEY_VEHICLE_KIND_EVIDENCE to evidence,
                        SettingsRepository.KEY_VEHICLE_KIND_CONFIRMED_AT to nowMs.toString(),
                    )
                )
                _kind.value = confirmed
                synchronized(lock) { roundDone = true }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${e.message}")
        } finally {
            synchronized(lock) {
                inFlight = false
                if (attempts >= MAX_ATTEMPTS) roundDone = true
            }
        }
    }

    /** The diagnostic dump line ([VehicleKindDetector.dumpLine]). */
    suspend fun dumpLine(): String {
        loadCache()
        val kind = _kind.value
        val evidence = settings.getString(SettingsRepository.KEY_VEHICLE_KIND_EVIDENCE, "").ifEmpty { null }
        val at = settings.getString(SettingsRepository.KEY_VEHICLE_KIND_CONFIRMED_AT, "").toLongOrNull()
        return VehicleKindDetector.dumpLine(kind, evidence, at, lastEvidence)
    }

    /**
     * The cached car type, read before the cold-start steps of TrackingService: they decide by
     * [kind] whether the counters of the previous process become a trip's litres, and
     * [onServiceStart] runs concurrently with them.
     */
    suspend fun awaitCache() = loadCache()

    private suspend fun loadCache() {
        if (cacheLoaded) return
        val cached = settings.getString(SettingsRepository.KEY_VEHICLE_KIND, "")
        val kind = VehicleKind.entries.firstOrNull { it.name == cached && it != VehicleKind.UNKNOWN }
        // A confirmation of this run may have landed while the cache was being read.
        if (kind != null && _kind.value == VehicleKind.UNKNOWN) _kind.value = kind
        cacheLoaded = true
    }

    private fun startRound(nowMs: Long) {
        attempts = 0
        roundDone = false
        roundStartedAtMs = nowMs
        confirmation.reset()
    }

    companion object {
        private const val TAG = "VehicleKind"
        const val MAX_ATTEMPTS = 10
        const val RECHECK_MS = 24 * 60 * 60 * 1000L

        /** HEV odometer, EV odometer, fuel percent: dev 1014, tx 5, in this order. */
        val ITEMS: List<BatchReadItem> = listOf(
            BatchReadItem(TX_GET_INT, VehicleKindDetector.DEV, VehicleKindDetector.MILEAGE_HEV_FID),
            BatchReadItem(TX_GET_INT, VehicleKindDetector.DEV, VehicleKindDetector.MILEAGE_EV_FID),
            BatchReadItem(TX_GET_INT, VehicleKindDetector.DEV, VehicleKindDetector.FUEL_PERCENT_FID),
        )
        private const val TX_GET_INT = 5
    }
}
