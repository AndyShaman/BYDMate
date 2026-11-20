package com.bydmate.app.data.tpms

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.helper.dtc.DtcScanner
import com.bydmate.app.helper.dtc.DtcWire
import com.bydmate.app.helper.dtc.TpmsPlanEntry
import com.bydmate.app.helper.dtc.TpmsSlotResult
import com.bydmate.app.helper.dtc.TpmsStatus
import com.bydmate.app.helper.dtc.TpmsVerdict
import com.bydmate.app.helper.dtc.TpmsWire
import com.bydmate.app.ui.automation.isSampleFresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * The last read and the last write are kept in prefs `tpms` (this car only, not in the backup);
 * the sets live in [SettingsRepository] and so go into the backup.
 */
@Suppress("LongParameterList", "TooManyFunctions") // seams for tests; read and write share the poll and the saved read
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
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<TpmsUiState> get() = _state

    /** What the running write was for: the set update needs it when the result comes. */
    private class PendingWrite(val plan: List<TpmsPlanEntry>, val written: TyreSetName?, val wheel: Int?, val undo: Boolean)

    @Volatile private var pending: PendingWrite? = null
    /** An edit before the saved sets came back from the database wins over them. */
    @Volatile private var setsTouched = false
    private val saveLock = Mutex()

    init {
        scope.launch {
            val loaded = TyreSetsCodec.fromJson(runCatching { settings.getTyreSets() }.getOrNull())
            Log.i(TAG, "sets loaded touched=$setsTouched ${setsText(loaded)}")
            _state.update { if (setsTouched) it else it.copy(sets = loaded) }
        }
    }

    /** The window opened: on a fresh P sample start a read; otherwise send nothing and keep [TpmsUiState.lastRead]. */
    fun onWindowOpened() {
        val current = _state.value
        Log.i(TAG, "window opened phase=${current.phase} last_read_at=${current.lastReadAt ?: "-"} can_write=${current.canWrite}")
        if (current.phase != TpmsPhase.IDLE) return
        _state.update { it.copy(error = null) }
        if (parkGear() == null) return
        _state.update { it.copy(phase = TpmsPhase.READING) }
        scope.launch { runRead() }
    }

    /** «Взять с машины»: [name] := the IDs of the last read, in wheel order. No car request. */
    fun takeFromCar(name: TyreSetName) {
        val ids = idsOf(_state.value.lastRead)
        if (ids.any { it == null }) {
            Log.i(TAG, "take from car $name refused not_read")
            return
        }
        changeSets("take from car $name") { replace(it, name, TyreSet(ids.map { id -> tyreHex(id!!) })) }
    }

    /** A cell edited in the table (wheel 0..3); stored normalised. */
    fun editSet(name: TyreSetName, wheel: Int, raw: String?) {
        if (wheel !in 0 until WHEELS) return
        changeSets("edit $name ${WHEEL_NAMES[wheel]}") { sets ->
            val ids = sets.of(name).ids.toMutableList().also { it[wheel] = TyrePlans.normalize(raw) }
            replace(sets, name, TyreSet(ids))
        }
    }

    /**
     * After both confirmations: builds the plan with [TyrePlans.build] against the last read and
     * starts the daemon write; [written] = the set (null for a single wheel, then [wheel] is set).
     * Not on P, no read, not L3, invalid plan or nothing to write → nothing goes to the daemon.
     * On a complete result the sets follow [TyreSetUpdate.afterWrite].
     */
    fun write(target: List<String?>, written: TyreSetName?, wheel: Int?) {
        Log.i(TAG, "write tap set=${written ?: "-"} wheel=${wheel?.let { WHEEL_NAMES.getOrNull(it) } ?: "-"} target=${target.joinToString(",") { it ?: "-" }}")
        startWrite(idsOf(_state.value.lastRead), target, PendingWrite(emptyList(), written, wheel, undo = false))
    }

    /** «Вернуть как было»: writes back the `before` IDs of the last write, as a new write. */
    fun undoLastWrite() {
        val last = _state.value.lastWrite
        Log.i(TAG, "undo tap last_write=${last != null}")
        if (last == null) return
        val before = slotIds(last) { it.beforeUds }
        val readBack = slotIds(last) { it.afterUds }
        // The set that took the last write's order follows the undo too (order = what is in the car).
        val set = TyreSetName.entries.singleOrNull { n -> _state.value.sets.of(n).ids == readBack.map { it?.let(::tyreHex) } }
        startWrite(readBack, before.map { it?.let(::tyreHex) }, PendingWrite(emptyList(), set, null, undo = true))
    }

    /** The write gates, then the plan of [target] against [current]; one start, no retry. */
    private fun startWrite(current: List<Int?>, target: List<String?>, intent: PendingWrite) {
        val st = _state.value
        if (st.phase != TpmsPhase.IDLE) {
            Log.i(TAG, "write ignored phase=${st.phase}")
            return
        }
        _state.update { it.copy(error = null) }
        if (parkGear() == null) {
            _state.update { it.copy(error = TpmsError.NOT_PARK) }
            return
        }
        if (!st.canWrite) {
            Log.i(TAG, "write refused not_l3 last_read=${st.lastRead != null}")
            _state.update { it.copy(error = TpmsError.NOT_L3) }
            return
        }
        val plan = when (val p = TyrePlans.build(current, target)) {
            is TyrePlan.Ok -> p.entries
            TyrePlan.NothingToWrite -> {
                Log.i(TAG, "write skipped nothing_to_write")
                return
            }
            is TyrePlan.Invalid -> {
                Log.i(TAG, "write refused plan=${p.error} wheels=${p.wheels.joinToString(",") { WHEEL_NAMES[it] }}")
                return
            }
        }
        Log.i(
            TAG,
            "write plan undo=${intent.undo} " + (0 until WHEELS).joinToString(" ") { i ->
                val e = plan.firstOrNull { it.did == TpmsWire.DIDS[i] }
                "${WHEEL_NAMES[i]}=" + if (e == null) "same" else "${tyreHex(e.expectedOld)}>${tyreHex(e.newId)}"
            },
        )
        pending = PendingWrite(plan, intent.written, intent.wheel, intent.undo)
        _state.update { it.copy(phase = TpmsPhase.WRITING) }
        scope.launch { runWrite(plan) }
    }

    /** The live gear when the sample is fresh and in P, null otherwise; a refusal is logged. */
    private fun parkGear(): Int? {
        val sample = liveSample()
        val fresh = sample != null && isSampleFresh(sample, serviceRunning(), elapsedNow())
        val gear = sample?.data?.gear
        if (!fresh || gear != GEAR_PARK) {
            Log.i(TAG, "refused not_park gear=$gear fresh=$fresh")
            return null
        }
        return gear
    }

    private suspend fun runRead() {
        val gear = gearAddress()
        val reply = runCatching { helper.tpmsReadStart(gear.device, gear.fid) }.getOrNull()
        Log.i(TAG, "read start reply=${replyName(reply)}")
        if (reply != DtcScanner.START_OK) return fail(startError(reply))
        val status = poll(DtcWire.MODE_TPMS_READ) ?: return
        if (status.state != DtcWire.STATE_DONE) {
            Log.w(TAG, "read end state=${stateName(status.state)} reason=${status.reason.ifEmpty { "-" }}")
            return fail(errorOf(status))
        }
        saveRead(status, "read")
        _state.update { it.copy(phase = TpmsPhase.IDLE) }
    }

    private suspend fun runWrite(plan: List<TpmsPlanEntry>) {
        val gear = gearAddress()
        val speed = speedAddress()
        val reply = runCatching { helper.tpmsWriteStart(gear.device, gear.fid, speed.device, speed.fid, plan) }.getOrNull()
        Log.i(TAG, "write start reply=${replyName(reply)} gear=${gear.device}/${gear.fid} speed=${speed.device}/${speed.fid}")
        if (reply != DtcScanner.START_OK) {
            pending = null
            return fail(startError(reply))
        }
        val status = poll(DtcWire.MODE_TPMS_WRITE) ?: run { pending = null; return }
        finishWrite(status)
    }

    /**
     * Once a second until the daemon reports a terminal state of [mode]; null after the failure
     * is set (three lost polls, or the daemon no longer holds this job).
     */
    private suspend fun poll(mode: Int): TpmsStatus? {
        var lost = 0
        while (true) {
            delay(POLL_MS)
            val status = runCatching { helper.tpmsStatus() }.getOrNull()
            if (status == null) {
                if (++lost >= MAX_LOST_POLLS) {
                    Log.w(TAG, "status lost polls=$lost mode=${modeName(mode)}")
                    fail(TpmsError.STATUS_LOST)
                    return null
                }
                continue
            }
            lost = 0
            if (status.mode != mode || status.state == DtcWire.STATE_IDLE) {
                Log.w(TAG, "poll saw mode=${modeName(status.mode)} state=${stateName(status.state)} waiting for mode=${modeName(mode)}, job lost")
                fail(TpmsError.STATUS_LOST)
                return null
            }
            if (status.state != DtcWire.STATE_RUNNING) return status
        }
    }

    /**
     * A write that ended in any terminal state may have written something: its result is kept for
     * the result screen and «Вернуть как было», its read-back becomes the last read, and on a
     * complete result the sets follow it.
     */
    private fun finishWrite(status: TpmsStatus) {
        val intent = pending
        pending = null
        val planned = intent?.plan.orEmpty()
        val readBack = slotIds(status) { it.afterUds }
        val complete = writeComplete(status, planned.map { it.did })
        status.slots.forEach { slot ->
            Log.i(
                TAG,
                "write result ${wheelName(slot.did)} verdict=${slot.verdict ?: "-"} " +
                    "before=${parsedId(slot.did, slot.beforeUds)} after=${parsedId(slot.did, slot.afterUds)}",
            )
        }
        Log.i(TAG, "write end state=${stateName(status.state)} reason=${status.reason.ifEmpty { "-" }} stopped_at=${status.stoppedAt.ifEmpty { "-" }} complete=$complete undo=${intent?.undo}")
        val at = wallNow()
        prefs.edit().putString(KEY_LAST_WRITE, statusToJson(status, at)).apply()
        _state.update { it.copy(lastWrite = status) }
        if (readBack.none { it == null }) saveRead(readBackAsRead(status), "write read-back")
        if (intent != null) {
            changeSets("after write set=${intent.written ?: "-"} wheel=${intent.wheel ?: "-"} complete=$complete") {
                TyreSetUpdate.afterWrite(it, intent.written, intent.wheel, readBack, complete)
            }
        }
        val error = if (status.state == DtcWire.STATE_DONE) null else errorOf(status)
        _state.update { it.copy(phase = TpmsPhase.IDLE, error = error) }
    }

    private fun saveRead(status: TpmsStatus, source: String) {
        val at = wallNow()
        val canWrite = l3(status)
        val statuses = TyreWheelStatuses.of(slots(status), status.dtcBefore)
        Log.i(
            TAG,
            "$source saved at=$at l3=$canWrite " + statuses.mapIndexed { i, w ->
                "${WHEEL_NAMES[i]}=${w.id?.let(::tyreHex) ?: "-"}/heard=${w.heard ?: "-"}/bat=${w.battery}"
            }.joinToString(" ") + " id=${hex(status.idUds) ?: "-"} dtc=${hex(status.dtcBefore) ?: "-"}",
        )
        prefs.edit().putString(KEY_LAST_READ, statusToJson(status, at)).apply()
        _state.update { it.copy(lastRead = status, lastReadAt = at, canWrite = canWrite) }
    }

    private fun fail(error: TpmsError) {
        Log.i(TAG, "end error=$error")
        _state.update { it.copy(phase = TpmsPhase.IDLE, error = error) }
    }

    /** Applies [change] to the sets; a real change is logged before → after and saved. */
    private fun changeSets(why: String, change: (TyreSets) -> TyreSets) {
        setsTouched = true
        val before = _state.value.sets
        val after = change(before)
        if (after == before) {
            Log.i(TAG, "sets unchanged ($why)")
            return
        }
        _state.update { it.copy(sets = after) }
        Log.i(TAG, "sets changed ($why) before ${setsText(before)} after ${setsText(after)}")
        scope.launch {
            saveLock.withLock {
                runCatching { settings.setTyreSets(TyreSetsCodec.toJson(_state.value.sets)) }
                    .onFailure { Log.w(TAG, "sets save failed ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun initialState(): TpmsUiState {
        val read = load(KEY_LAST_READ)
        val write = load(KEY_LAST_WRITE)
        return TpmsUiState(
            lastRead = read?.second,
            lastReadAt = read?.first,
            canWrite = read?.second?.let(::l3) == true,
            lastWrite = write?.second,
        )
    }

    private fun load(key: String): Pair<Long, TpmsStatus>? {
        val json = prefs.getString(key, null) ?: return null
        return runCatching { statusFromJson(json) }
            .onFailure { Log.w(TAG, "load $key failed ${it.javaClass.simpleName} (prefs ignored)") }
            .getOrNull()
    }

    companion object {
        const val PREFS_NAME = "tpms"
        private const val KEY_LAST_READ = "last_read"
        private const val KEY_LAST_WRITE = "last_write"
        private const val TAG = "Tpms"
        private const val GEAR_PARK = 1
        private const val POLL_MS = 1_000L
        private const val MAX_LOST_POLLS = 3
        private const val START_OUTDATED = -2
        private val WHEEL_NAMES = listOf("FL", "FR", "RL", "RR")

        /** The IDs of [status]'s slots (as read before any write), FL..RR; null = not read. */
        fun idsOf(status: TpmsStatus?): List<Int?> = slots(status).map { it?.id }

        /** The slots of [status] as parsed from the answers read before any write, FL..RR. */
        fun slots(status: TpmsStatus?) = TpmsWire.DIDS.map { did ->
            TpmsWire.parseSlot(did, status?.slots?.firstOrNull { it.did == did }?.beforeUds)
        }

        /** Every one of [plannedDids] came back WRITTEN and all four slots were read back. */
        fun writeComplete(status: TpmsStatus, plannedDids: Collection<Int>): Boolean =
            plannedDids.isNotEmpty() && slotIds(status) { it.afterUds }.none { it == null } &&
                plannedDids.all { did -> status.slots.firstOrNull { it.did == did }?.verdict == TpmsVerdict.WRITTEN }

        /** The `--- tpms ---` dump section: last read, sets, last write and the daemon's job state. Never starts a job. */
        fun dumpLines(prefs: SharedPreferences, setsJson: String?, daemon: TpmsStatus?): List<String> {
            val lines = mutableListOf<String>()
            for ((name, key) in listOf("last read" to KEY_LAST_READ, "last write" to KEY_LAST_WRITE)) {
                val saved = prefs.getString(key, null)?.let { runCatching { statusFromJson(it) }.getOrNull() }
                if (saved == null) {
                    lines += "$name: none"
                    continue
                }
                val (at, st) = saved
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at))
                lines += "$name: at=$stamp state=${stateName(st.state)} reason=${st.reason.ifEmpty { "-" }} l3=${l3(st)} " +
                    "stopped_at=${st.stoppedAt.ifEmpty { "-" }} id=${hex(st.idUds) ?: "-"}"
                lines += "  dtc before=${hex(st.dtcBefore) ?: "-"} after=${hex(st.dtcAfter) ?: "-"}"
                st.slots.forEach {
                    lines += "  ${wheelName(it.did)} verdict=${it.verdict ?: "-"} before=${hex(it.beforeUds) ?: "-"} after=${hex(it.afterUds) ?: "-"}"
                }
            }
            lines += "sets: " + setsText(TyreSetsCodec.fromJson(setsJson))
            lines += if (daemon == null) "daemon: unreachable" else
                "daemon: state=${stateName(daemon.state)} mode=${modeName(daemon.mode)} reason=${daemon.reason.ifEmpty { "-" }}"
            return lines
        }

        private fun l3(status: TpmsStatus): Boolean =
            TpmsWire.l3Shape(status.idUds, status.slots.associate { it.did to it.beforeUds })

        private fun slotIds(status: TpmsStatus, uds: (TpmsSlotResult) -> ByteArray?): List<Int?> =
            TpmsWire.DIDS.map { did -> TpmsWire.parseSlot(did, status.slots.firstOrNull { it.did == did }?.let(uds))?.id }

        /** The re-read at the end of a write, shaped as a read: the newest state of the block. */
        private fun readBackAsRead(w: TpmsStatus) = TpmsStatus(
            DtcWire.STATE_DONE, DtcWire.MODE_TPMS_READ, "", w.idUds, w.dtcAfter, null,
            w.slots.map { TpmsSlotResult(it.did, it.afterUds, null, null) }, "",
        )

        private fun startError(reply: Int?): TpmsError = when (reply) {
            null -> TpmsError.DAEMON_UNREACHABLE
            DtcScanner.START_BUSY -> TpmsError.BUSY
            START_OUTDATED -> TpmsError.DAEMON_OUTDATED
            else -> TpmsError.REFUSED
        }

        private fun errorOf(status: TpmsStatus): TpmsError = when {
            status.reason.startsWith("not_park") || status.reason.startsWith("speed") -> TpmsError.NOT_PARK
            status.reason.startsWith("not_l3") -> TpmsError.NOT_L3
            else -> TpmsError.ABORTED
        }

        private fun stateName(state: Int): String = when (state) {
            DtcWire.STATE_IDLE -> "IDLE"
            DtcWire.STATE_RUNNING -> "RUNNING"
            DtcWire.STATE_DONE -> "DONE"
            DtcWire.STATE_UNAVAILABLE -> "UNAVAILABLE"
            DtcWire.STATE_ABORTED -> "ABORTED"
            else -> state.toString()
        }

        private fun modeName(mode: Int): String = when (mode) {
            DtcWire.MODE_SCAN -> "SCAN"
            DtcWire.MODE_ERASE -> "ERASE"
            DtcWire.MODE_TPMS_READ -> "TPMS_READ"
            DtcWire.MODE_TPMS_WRITE -> "TPMS_WRITE"
            else -> mode.toString()
        }

        private fun replyName(reply: Int?): String = when (reply) {
            null -> "null(unreachable)"
            DtcScanner.START_BUSY -> "1(busy)"
            DtcScanner.START_REFUSED -> "-1(refused)"
            START_OUTDATED -> "-2(outdated)"
            else -> reply.toString()
        }

        private fun wheelName(did: Int): String = WHEEL_NAMES.getOrNull(TpmsWire.DIDS.indexOf(did)) ?: "0x%X".format(did)

        private fun parsedId(did: Int, uds: ByteArray?): String = TpmsWire.parseSlot(did, uds)?.let {
            "${tyreHex(it.id)}/heard=${it.heard}"
        } ?: "-"

        private fun setsText(sets: TyreSets): String = TyreSetName.entries.joinToString(" ") { n ->
            "$n=" + sets.of(n).ids.joinToString(",") { it ?: "-" }
        }

        private fun replace(sets: TyreSets, name: TyreSetName, set: TyreSet) =
            if (name == TyreSetName.WINTER) sets.copy(winter = set) else sets.copy(summer = set)

        private fun statusToJson(s: TpmsStatus, at: Long): String {
            val slots = JSONArray()
            s.slots.forEach {
                slots.put(
                    JSONObject().put("d", it.did).putOpt("b", hex(it.beforeUds)).putOpt("a", hex(it.afterUds))
                        .putOpt("v", it.verdict?.name),
                )
            }
            return JSONObject().put("v", 1).put("at", at).put("state", s.state).put("mode", s.mode).put("reason", s.reason)
                .putOpt("id", hex(s.idUds)).putOpt("db", hex(s.dtcBefore)).putOpt("da", hex(s.dtcAfter))
                .put("slots", slots).put("stop", s.stoppedAt).toString()
        }

        private fun statusFromJson(text: String): Pair<Long, TpmsStatus> {
            val o = JSONObject(text)
            val a = o.getJSONArray("slots")
            val slots = (0 until a.length()).map { i ->
                val e = a.getJSONObject(i)
                TpmsSlotResult(
                    e.getInt("d"), unhex(e.optString("b")), unhex(e.optString("a")),
                    e.optString("v").takeIf { it.isNotEmpty() }?.let { TpmsVerdict.valueOf(it) },
                )
            }
            return o.getLong("at") to TpmsStatus(
                o.getInt("state"), o.getInt("mode"), o.getString("reason"),
                unhex(o.optString("id")), unhex(o.optString("db")), unhex(o.optString("da")), slots, o.optString("stop"),
            )
        }

        private fun hex(b: ByteArray?): String? = b?.joinToString("") { "%02X".format(it) }

        private fun unhex(s: String): ByteArray? =
            if (s.isEmpty()) null else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
