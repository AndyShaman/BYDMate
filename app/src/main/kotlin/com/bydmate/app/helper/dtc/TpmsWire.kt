package com.bydmate.app.helper.dtc

/**
 * Tyre-sensor IDs in the left body block (LA_BCM, request 0x720, reply 0x728): DID 0x3305 FL,
 * 0x3306 FR, 0x3307 RL, 0x3308 RR. Pure: request shapes, parsing, the "answers exactly like
 * Leopard 3" gate, the write plan check and the security key. Design:
 * `.research/tpms-l3-2026-11-16/design-tpms-fw-2026-11-20.md` sections B and C.
 */
object TpmsWire {
    const val ADDR = 0x720
    const val REPLY = 0x728
    const val DID_FL = 0x3305
    const val DID_FR = 0x3306
    const val DID_RL = 0x3307
    const val DID_RR = 0x3308
    val DIDS = intArrayOf(DID_FL, DID_FR, DID_RL, DID_RR)
    const val KEY_CONST = 0x6500001D

    /** `27 02` key for a 4-byte `67 01` seed: ((((s>>>1)^s)<<3)^(s>>>2)) ^ KEY_CONST, unsigned shifts. */
    fun securityKey(seed: Int): Int = TODO()

    /** A `62 33xx` answer of exactly 14 bytes with status byte [7] 00 heard / 01 not heard; else null. */
    fun parseSlot(did: Int, uds: ByteArray?): TpmsSlot? = TODO()

    /** True iff F194 and all four slots have the exact Leopard 3 shape (design section C). */
    fun l3Shape(idUds: ByteArray?, slots: Map<Int, ByteArray?>): Boolean = TODO()

    /** Null when [plan] may start; else the refusal reason (n 1..4, distinct DIDs of [DIDS], newId != old, not 0/FFFFFFFF). */
    fun validatePlan(plan: List<TpmsPlanEntry>): String? = TODO()

    /** Compare-and-set against the daemon's own fresh read [current] (did -> id), design B.3 step 4. */
    fun cas(plan: List<TpmsPlanEntry>, current: Map<Int, Int>): TpmsCas = TODO()
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
 * key of that seed; each `2E` entry passes once. Address 0x720 only.
 */
class TpmsGuard(private val kind: TpmsJobKind, private val plan: List<TpmsPlanEntry>, private val latched: Boolean) {
    fun onSeed(seed: ByteArray) { TODO() }
    fun allowed(addr: Int, uds: ByteArray): Boolean = TODO()
}

enum class TpmsVerdict { WRITTEN, NOT_WRITTEN, OTHER, UNVERIFIED, ALREADY, REFUSED, UNKNOWN, NOT_SENT, NOT_RUN }

/** One wheel in a finished tyre job: raw answers before and after and, for a write, its verdict. */
class TpmsSlotResult(val did: Int, val beforeUds: ByteArray?, val afterUds: ByteArray?, val verdict: TpmsVerdict?)

/** Status of the last tyre job ([mode] DtcWire.MODE_TPMS_READ / MODE_TPMS_WRITE). */
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
