package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A window unit for the percent tests. The target-position fid holds the last percent written
 * to it as a live request (255 = "no request", what BYD's own apps leave there); a percent the
 * unit does not see as a change is accepted with status=1 and moves nothing; the CTRL open/close
 * fid moves the glass without touching the held request; [wake] replays a held request the way
 * the Song L did at power-on. The glass reaches its target at once.
 */
internal class WindowCarModel(private val rule: Rule, private val now: () -> Long) {

    enum class Rule {
        /** Song L (user log 2026-09-29): a percent within one of the held request is no change. */
        SONG_L,
        /** Leopard 3 (2026-09-24): only a percent equal to the held request is ignored. */
        LEOPARD_3,
        /** Moves on every percent write. */
        ALWAYS,
    }

    data class Write(val fid: Int, val value: Int, val atMs: Long)

    /** Every write the unit received, refused ones included, in order. */
    val writes = mutableListOf<Write>()

    /** Transacts to refuse (status -1): nothing is applied. */
    var refuse: (fid: Int, value: Int) -> Boolean = { _, _ -> false }

    /** A glass that never moves, whatever the unit accepts. */
    var frozen = false

    /** The daemon client [api] writes through, for tests that change one answer. */
    val helper: HelperClient = mockk()

    private val held = mutableMapOf<Int, Int>()
    private val positions = mutableMapOf<Int, Int>()

    fun hold(posFid: Int, percent: Int) { held[posFid] = percent }
    fun held(posFid: Int): Int? = held[posFid]
    fun setPosition(readFid: Int, percent: Int) { positions[readFid] = percent }
    fun position(readFid: Int): Int = positions[readFid] ?: 0

    fun writesTo(fid: Int): List<Int> = writes.filter { it.fid == fid }.map { it.value }

    /** Status of one setInt transact. */
    fun write(fid: Int, value: Int): Int {
        writes += Write(fid, value, now())
        if (refuse(fid, value)) return -1
        POS_READ[fid]?.let { read -> applyPercent(fid, read, value) }
        CTRL_READ[fid]?.takeUnless { frozen }?.let { read ->
            when (value) {
                CTRL_OPEN -> positions[read] = 100
                CTRL_CLOSE -> positions[read] = 0
            }
        }
        return 1
    }

    /** The car wakes: every held request drives its glass again. */
    fun wake() {
        held.forEach { (fid, percent) -> positions[POS_READ.getValue(fid)] = percent }
    }

    private fun applyPercent(fid: Int, read: Int, value: Int) {
        if (value == RESET) {
            held.remove(fid)
            return
        }
        val last = held[fid]
        val ignored = when (rule) {
            Rule.SONG_L -> last != null && kotlin.math.abs(value - last) <= 1
            Rule.LEOPARD_3 -> last == value
            Rule.ALWAYS -> false
        }
        if (ignored) return
        held[fid] = value
        if (!frozen) positions[read] = value
    }

    /** A VehicleApiImpl wired to this unit, on the percent channel. */
    fun api(): VehicleApiImpl {
        coEvery { helper.write(any(), any(), any()) } coAnswers { write(secondArg(), thirdArg()) >= 0 }
        coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers { write(secondArg(), thirdArg()) }
        val autoservice = mockk<AutoserviceClient>(relaxed = true)
        coEvery { autoservice.getIntRaw(any(), any()) } coAnswers { position(secondArg()) }
        val store = object : WindowChannelStore {
            override fun winner() = WindowChannel.PERCENT
            override fun setWinner(channel: WindowChannel) = Unit
            override fun ctrlCandidateAtMs() = 0L
            override fun setCtrlCandidateAtMs(ts: Long) = Unit
        }
        val seatStore = object : SeatChannelStore {
            override fun winner() = SeatChannel.UNKNOWN
            override fun setWinner(channel: SeatChannel) = Unit
            override fun reprobeExhausted() = false
            override fun claimReprobe() = true
        }
        val allowlist = WriteAllowlist(
            (WriteAllowlist.LIVE_VALIDATED + WriteAllowlist.CANDIDATE_UNVALIDATED)
                .associateBy { it.actionName.lowercase() }
        )
        val parsReader = mockk<ParsReader>(relaxed = true)
        val dao = mockk<VehicleWriteLogDao>(relaxed = true)
        return VehicleApiImpl(parsReader, autoservice, helper, allowlist, dao, seatStore, store)
            .also { it.readbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
    }

    companion object {
        const val RESET = 255
        const val CTRL_OPEN = 1
        const val CTRL_CLOSE = 2

        const val DRIVER_POS = 1276219408
        const val PASSENGER_POS = 1276219424
        const val REAR_LEFT_POS = 1276219416
        const val REAR_RIGHT_POS = 1276219432
        const val DRIVER_CTRL = 1125122104
        const val DRIVER_READ = 947912728

        /** Percent fid -> the position read of the same pane. */
        val POS_READ: Map<Int, Int> = mapOf(
            DRIVER_POS to DRIVER_READ,
            PASSENGER_POS to 1267728400,
            REAR_LEFT_POS to 947912736,
            REAR_RIGHT_POS to 947912752,
        )

        /** CTRL fid -> the position read of the same pane. */
        val CTRL_READ: Map<Int, Int> = mapOf(
            DRIVER_CTRL to DRIVER_READ,
            1125122107 to 1267728400,
            1125122112 to 947912736,
            1125122115 to 947912752,
        )
    }
}
