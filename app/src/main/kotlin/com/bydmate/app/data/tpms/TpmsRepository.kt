package com.bydmate.app.data.tpms

import android.content.SharedPreferences
import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.helper.dtc.TpmsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

enum class TpmsPhase { IDLE, READING, WRITING }

enum class TpmsError { NOT_PARK, DAEMON_UNREACHABLE, DAEMON_OUTDATED, BUSY, REFUSED, ABORTED, STATUS_LOST, NOT_L3 }

/**
 * The tyre window's state. [lastRead] = the last finished read (kept in prefs, shown while
 * driving); [canWrite] = that read has the exact Leopard 3 shape; [lastWrite] = the last write
 * result (for «Вернуть как было» and the result screen).
 */
data class TpmsUiState(
    val phase: TpmsPhase = TpmsPhase.IDLE,
    val error: TpmsError? = null,
    val lastRead: TpmsStatus? = null,
    val lastReadAt: Long? = null,
    val canWrite: Boolean = false,
    val lastWrite: TpmsStatus? = null,
    val sets: TyreSets = TyreSets(),
)

/**
 * Reads and writes the tyre-sensor IDs through the helper daemon, on P only (app-side check as
 * DtcScanRepository, the daemon checks again). Polls the job status once a second like the scan.
 */
class TpmsRepository(
    private val helper: HelperClient,
    private val prefs: SharedPreferences,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val liveSample: () -> TimedSnapshot?,
    private val serviceRunning: () -> Boolean,
    private val elapsedNow: () -> Long,
    private val wallNow: () -> Long,
    private val gearAddress: () -> FidAddress = { FidAddresses.of("gear") },
    private val speedAddress: () -> FidAddress = { FidAddresses.of("speed") },
) {
    val state: StateFlow<TpmsUiState> get() = TODO()

    /** The window opened: on a fresh P sample start a read; otherwise send nothing and keep [TpmsUiState.lastRead]. */
    fun onWindowOpened() { TODO() }

    /** «Взять с машины»: [name] := the IDs of the last read, in wheel order. No car request. */
    fun takeFromCar(name: TyreSetName) { TODO() }

    /** A cell edited in the table (wheel 0..3); stored normalised. */
    fun editSet(name: TyreSetName, wheel: Int, raw: String?) { TODO() }

    /**
     * After both confirmations: builds the plan with [TyrePlans.build] against the last read and
     * starts the daemon write; [written] = the set (null for a single wheel, then [wheel] is set).
     * Not on P, no read, not L3, invalid plan or nothing to write → nothing goes to the daemon.
     * On a complete result the sets follow [TyreSetUpdate.afterWrite].
     */
    fun write(target: List<String?>, written: TyreSetName?, wheel: Int?) { TODO() }

    /** «Вернуть как было»: writes back the `before` IDs of the last write, as a new write. */
    fun undoLastWrite() { TODO() }
}
