package com.bydmate.app.helper.dtc

import android.os.Parcel

/**
 * Tyre-sensor IDs in the left body block (LA_BCM, request 0x720, reply 0x728): DID 0x3305 FL,
 * 0x3306 FR, 0x3307 RL, 0x3308 RR. Pure: request shapes, parsing, the "answers exactly like
 * Leopard 3" gate, the write plan check and the security key. Design:
 * `.research/tpms-l3-2026-11-16/design-tpms-fw-2026-11-20.md` sections B and C.
 */
@Suppress("TooManyFunctions") // one builder per fixed request plus the parsers and checks
object TpmsWire {
    const val ADDR = 0x720
    const val REPLY = 0x728
    const val DID_FL = 0x3305
    const val DID_FR = 0x3306
    const val DID_RL = 0x3307
    const val DID_RR = 0x3308
    val DIDS = intArrayOf(DID_FL, DID_FR, DID_RL, DID_RR)
    const val KEY_CONST = 0x6500001D

    const val SERVICE_SESSION = 0x10
    const val SERVICE_SECURITY = 0x27
    const val SERVICE_WRITE_DID = 0x2E
    const val SESSION_DEFAULT = 0x01
    const val SESSION_EXTENDED = 0x03
    const val SECURITY_SEED = 0x01
    const val SECURITY_KEY = 0x02

    /** The exact session answers of the L3 block (P2 50 ms, P2* 5000 ms). */
    val EXTENDED_ANSWER: ByteArray = bytes(0x50, 0x03, 0x00, 0x32, 0x01, 0xF4)
    val DEFAULT_ANSWER: ByteArray = bytes(0x50, 0x01, 0x00, 0x32, 0x01, 0xF4)
    /** The only key answer that unlocks: exactly `67 02`. */
    val KEY_ANSWER: ByteArray = bytes(0x67, 0x02)

    private const val FRAME_PREFIX = 0x0A
    private const val POSITIVE_READ = 0x62
    private const val DID_HEAD = 3
    private const val SLOT_BYTES = 14
    private const val STATUS_INDEX = 7
    private const val F194_BYTES = 12
    private const val F194_TYPE_INDEX = 8
    private const val F194_TYPE_L3 = 0xF0
    private const val SUPPLIER_HI = 0x48
    private const val SUPPLIER_LO = 0x45
    private const val LIST_MASK_ALL = 0xFF
    private const val MAX_ENTRIES = 4
    private const val ID_ALL_SET = -1
    private const val SEED_BYTES = 4

    /** `27 02` key for a 4-byte `67 01` seed: ((((s>>>1)^s)<<3)^(s>>>2)) ^ KEY_CONST, unsigned shifts. */
    fun securityKey(seed: Int): Int = ((((seed ushr 1) xor seed) shl 3) xor (seed ushr 2)) xor KEY_CONST

    /** A `62 33xx` answer of exactly 14 bytes with status byte [7] 00 heard / 01 not heard; else null. */
    fun parseSlot(did: Int, uds: ByteArray?): TpmsSlot? {
        if (uds == null || uds.size != SLOT_BYTES || u(uds, 0) != POSITIVE_READ) return null
        if (((u(uds, 1) shl 8) or u(uds, 2)) != did) return null
        val status = u(uds, STATUS_INDEX)
        if (status > 1) return null
        return TpmsSlot(did, int32(uds, DID_HEAD), status == 0, uds.copyOfRange(STATUS_INDEX + 1, SLOT_BYTES))
    }

    /** F194 of the L3 left body block: 12 bytes, `62 F1 94 48 45 xx xx xx F0 …` (design C.1). */
    fun l3Identity(idUds: ByteArray?): Boolean =
        idUds != null && idUds.size == F194_BYTES &&
            u(idUds, 0) == POSITIVE_READ && u(idUds, 1) == (DtcWire.DID_IDENTITY shr 8) &&
            u(idUds, 2) == (DtcWire.DID_IDENTITY and 0xFF) &&
            u(idUds, DID_HEAD) == SUPPLIER_HI && u(idUds, DID_HEAD + 1) == SUPPLIER_LO &&
            u(idUds, F194_TYPE_INDEX) == F194_TYPE_L3

    /** True iff F194 and all four slots have the exact Leopard 3 shape (design section C). */
    fun l3Shape(idUds: ByteArray?, slots: Map<Int, ByteArray?>): Boolean =
        l3Identity(idUds) && DIDS.all { parseSlot(it, slots[it]) != null }

    /** Null when [plan] may start; else the refusal reason (n 1..4, distinct DIDs of [DIDS], newId != old, not 0/FFFFFFFF). */
    fun validatePlan(plan: List<TpmsPlanEntry>): String? = when {
        plan.size !in 1..MAX_ENTRIES -> "plan_size n=${plan.size}"
        plan.any { it.did !in DIDS } -> "plan_did"
        plan.map { it.did }.distinct().size != plan.size -> "plan_repeated_did"
        plan.any { it.newId == it.expectedOld } -> "plan_same_id"
        plan.any { it.newId == 0 || it.newId == ID_ALL_SET } -> "plan_bad_id"
        else -> null
    }

    /** Compare-and-set against the daemon's own fresh read [current] (did -> id), design B.3 step 4. */
    fun cas(plan: List<TpmsPlanEntry>, current: Map<Int, Int>): TpmsCas {
        val write = ArrayList<TpmsPlanEntry>()
        for (e in plan.sortedBy { it.did }) {
            when (current[e.did]) {
                e.expectedOld -> write += e
                e.newId -> Unit
                else -> return TpmsCas.Abort("car_changed")
            }
        }
        val final = current + plan.associate { it.did to it.newId }
        if (final.values.distinct().size != final.size) return TpmsCas.Abort("duplicate")
        return if (write.isEmpty()) TpmsCas.NothingToWrite else TpmsCas.Write(write)
    }

    /** `0A 07 20 len uds`: the frame of one request to 0x720. */
    fun frame(uds: ByteArray): ByteArray = bytes(FRAME_PREFIX, ADDR shr 8, ADDR and 0xFF, uds.size) + uds

    fun readRequest(did: Int): ByteArray = bytes(DtcWire.SERVICE_READ_DID, did shr 8, did and 0xFF)

    /** `19 02 FF`: every stored code of the block. */
    fun listRequest(): ByteArray = bytes(DtcWire.SERVICE_READ_DTC, DtcWire.SUB_LIST, LIST_MASK_ALL)

    fun sessionRequest(session: Int): ByteArray = bytes(SERVICE_SESSION, session)

    fun seedRequest(): ByteArray = bytes(SERVICE_SECURITY, SECURITY_SEED)

    fun keyRequest(key: Int): ByteArray = bytes(SERVICE_SECURITY, SECURITY_KEY) + int32Bytes(key)

    fun writeRequest(e: TpmsPlanEntry): ByteArray =
        bytes(SERVICE_WRITE_DID, e.did shr 8, e.did and 0xFF) + int32Bytes(e.newId)

    /** The 4 seed bytes of a `67 01 s0 s1 s2 s3` answer; null for any other length. */
    fun seedOf(uds: ByteArray): ByteArray? =
        if (uds.size == 2 + SEED_BYTES) uds.copyOfRange(2, uds.size) else null

    /** Big-endian 32 bits at [i]. */
    fun int32(b: ByteArray, i: Int): Int =
        (u(b, i) shl 24) or (u(b, i + 1) shl 16) or (u(b, i + 2) shl 8) or u(b, i + 3)

    private fun int32Bytes(v: Int): ByteArray = bytes(v ushr 24, (v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}

data class TpmsSlot(val did: Int, val id: Int, val heard: Boolean, val tail: ByteArray) {
    override fun equals(other: Any?) =
        other is TpmsSlot && did == other.did && id == other.id && heard == other.heard && tail.contentEquals(other.tail)
    override fun hashCode() = (did * 31 + id) * 31 + heard.hashCode()
}

/** One wheel to write: the ID the user saw ([expectedOld]) and the ID to put there ([newId]). */
data class TpmsPlanEntry(val did: Int, val expectedOld: Int, val newId: Int)

sealed class TpmsCas {
    /** Entries still to write, DID order; entries already equal to newId are dropped. */
    data class Write(val entries: List<TpmsPlanEntry>) : TpmsCas()
    object NothingToWrite : TpmsCas()
    /** `car_changed` (a slot is neither old nor new) or `duplicate` (the final four IDs repeat). */
    data class Abort(val reason: String) : TpmsCas()
}

enum class TpmsJobKind { READ, WRITE }

/**
 * The outgoing allowlist of one tyre job run (design B.1), checked right before every send.
 * Stateful: [onSeed] records the `67 01` seed of this run; `27 02` passes once and only with the
 * key of that seed; each `2E` entry passes once. Address 0x720 only. No `27` passes while
 * [latched] (read live, at the send) holds; [onKeySent] runs as the one `27 02` is let through.
 */
class TpmsGuard(
    private val kind: TpmsJobKind,
    private val plan: List<TpmsPlanEntry>,
    private val latched: () -> Boolean,
    private val onKeySent: () -> Unit = {},
) {
    constructor(kind: TpmsJobKind, plan: List<TpmsPlanEntry>, latched: Boolean) : this(kind, plan, { latched })

    private var key: ByteArray? = null
    private var keyUsed = false
    private val written = HashSet<Int>()

    fun onSeed(seed: ByteArray) {
        key = if (seed.size == SEED_BYTES) TpmsWire.keyRequest(TpmsWire.securityKey(TpmsWire.int32(seed, 0))) else null
    }

    fun allowed(addr: Int, uds: ByteArray): Boolean {
        if (addr != TpmsWire.ADDR || uds.isEmpty()) return false
        if (isRead(uds)) return true
        if (kind != TpmsJobKind.WRITE) return false
        return when (uds[0].toInt() and 0xFF) {
            TpmsWire.SERVICE_SESSION -> uds.contentEquals(TpmsWire.sessionRequest(TpmsWire.SESSION_EXTENDED)) ||
                uds.contentEquals(TpmsWire.sessionRequest(TpmsWire.SESSION_DEFAULT))
            TpmsWire.SERVICE_SECURITY -> !latched() && security(uds)
            TpmsWire.SERVICE_WRITE_DID -> write(uds)
            else -> false
        }
    }

    private fun isRead(uds: ByteArray): Boolean =
        uds.contentEquals(TpmsWire.listRequest()) ||
            uds.contentEquals(TpmsWire.readRequest(DtcWire.DID_IDENTITY)) ||
            TpmsWire.DIDS.any { uds.contentEquals(TpmsWire.readRequest(it)) }

    private fun security(uds: ByteArray): Boolean {
        if (uds.contentEquals(TpmsWire.seedRequest())) return true
        val k = key ?: return false
        if (keyUsed || !uds.contentEquals(k)) return false
        keyUsed = true
        onKeySent()
        return true
    }

    private fun write(uds: ByteArray): Boolean {
        val e = plan.firstOrNull { it.did !in written && uds.contentEquals(TpmsWire.writeRequest(it)) } ?: return false
        written += e.did
        return true
    }

    private companion object {
        const val SEED_BYTES = 4
    }
}

enum class TpmsVerdict { WRITTEN, NOT_WRITTEN, OTHER, UNVERIFIED, ALREADY, REFUSED, UNKNOWN, NOT_SENT, NOT_RUN }

/** One wheel in a finished tyre job: raw answers before and after and, for a write, its verdict. */
class TpmsSlotResult(val did: Int, val beforeUds: ByteArray?, val afterUds: ByteArray?, val verdict: TpmsVerdict?)

/** Status of the last tyre job ([mode] DtcWire.MODE_TPMS_READ / MODE_TPMS_WRITE). */
@Suppress("LongParameterList") // the parcel's fields, one to one
class TpmsStatus(
    val state: Int,
    val mode: Int,
    val reason: String,
    val idUds: ByteArray?,
    val dtcBefore: ByteArray?,
    val dtcAfter: ByteArray?,
    val slots: List<TpmsSlotResult>,
    val stoppedAt: String,
)

/** Upper bound of one UDS answer the parcel carries; a frame is at most 1024 bytes. */
private const val MAX_UDS_BYTES = 1024
private const val MAX_TEXT = 256
private const val INT_BYTES = 4

/** TX_DIAG_JOB_STATUS reply: see HelperBinderProtocol.TX_DIAG_JOB_STATUS. */
fun writeTpmsStatus(p: Parcel, status: TpmsStatus) {
    p.writeInt(status.state)
    p.writeInt(status.mode)
    p.writeString(status.reason.take(MAX_TEXT))
    p.writeByteArray(status.idUds)
    p.writeByteArray(status.dtcBefore)
    p.writeByteArray(status.dtcAfter)
    p.writeInt(status.slots.size)
    status.slots.forEach {
        p.writeInt(it.did)
        p.writeByteArray(it.beforeUds)
        p.writeByteArray(it.afterUds)
        p.writeString(it.verdict?.name.orEmpty())
    }
    p.writeString(status.stoppedAt.take(MAX_TEXT))
}

/** Null on a truncated, oversized or unknown-state reply (the daemon writes -1 when it failed). */
fun readTpmsStatus(p: Parcel): TpmsStatus? {
    if (p.dataAvail() < 2 * INT_BYTES) return null
    val state = p.readInt()
    if (state !in DtcWire.STATE_IDLE..DtcWire.STATE_ABORTED) return null
    val mode = p.readInt()
    if (mode !in DtcWire.MODE_SCAN..DtcWire.MODE_FIRMWARE) return null
    val reason = p.readString()?.takeIf { it.length <= MAX_TEXT } ?: return null
    val idUds = p.createByteArray()
    val dtcBefore = p.createByteArray()
    val dtcAfter = p.createByteArray()
    if (listOf(idUds, dtcBefore, dtcAfter).any { (it?.size ?: 0) > MAX_UDS_BYTES }) return null
    if (p.dataAvail() < INT_BYTES) return null
    val count = p.readInt()
    if (count !in 0..TpmsWire.DIDS.size) return null
    val slots = ArrayList<TpmsSlotResult>(count)
    repeat(count) { slots += readSlotResult(p) ?: return null }
    val stoppedAt = p.readString()?.takeIf { it.length <= MAX_TEXT } ?: return null
    return TpmsStatus(state, mode, reason, idUds, dtcBefore, dtcAfter, slots, stoppedAt)
}

private fun readSlotResult(p: Parcel): TpmsSlotResult? {
    if (p.dataAvail() < INT_BYTES) return null
    val did = p.readInt().takeIf { it in TpmsWire.DIDS } ?: return null
    val before = p.createByteArray()
    val after = p.createByteArray()
    if ((before?.size ?: 0) > MAX_UDS_BYTES || (after?.size ?: 0) > MAX_UDS_BYTES) return null
    val name = p.readString() ?: return null
    val verdict = if (name.isEmpty()) null else TpmsVerdict.entries.firstOrNull { it.name == name } ?: return null
    return TpmsSlotResult(did, before, after, verdict)
}
