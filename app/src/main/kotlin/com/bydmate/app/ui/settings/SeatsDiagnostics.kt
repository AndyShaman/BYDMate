package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.BatchReadItem

/**
 * READ-side snapshot of the seat comfort channel for the diagnostic dump.
 *
 * On Song L / Leopard 5 / Han EV the autoservice answers status=1 to every seat write while
 * nothing actuates, and a fid dump does not help: the constants are identical to Leopard 3.
 * What differs is what the READ side and the trim config flags report, so the dump prints
 * them verbatim — sentinels included (65535 = fid/CAN link missing, -10011 = wrong
 * direction), because the sentinel itself is the diagnosis.
 */
internal object SeatsDiagnostics {

    data class SeatFid(val name: String, val dev: Int, val fid: Int)

    /** All tx=5 reads, in print order: live status, live level, then the trim config flags. */
    val FIDS: List<SeatFid> = listOf(
        SeatFid("vent_status_driver", 1000, 702545928),
        SeatFid("heat_status_driver", 1000, 702545932),
        SeatFid("vent_level_driver", 1000, 702545944),
        SeatFid("heat_level_driver", 1000, 702545948),
        SeatFid("vent_status_passenger", 1000, 711983128),
        SeatFid("heat_status_passenger", 1000, 711983132),
        SeatFid("config_vent_lf", 1000, 715132952),
        SeatFid("config_heat_lf", 1000, 715132955),
        SeatFid("has_driver_seat_ventilating", 1023, -811597816),
        SeatFid("has_driver_seat_heating", 1023, -811597813),
    )

    val batchItems: List<BatchReadItem> = FIDS.map { BatchReadItem(TX_GET_INT, it.dev, it.fid) }

    /**
     * Renders one line per fid from raw (status, value) pairs in [FIDS] order. A null
     * [readings] (daemon unreachable, timeout, old daemon without batch support) or a
     * length mismatch collapses to a single "(unavailable)" line.
     */
    fun format(readings: List<Pair<Int, Int>>?): List<String> {
        if (readings == null || readings.size != FIDS.size) return listOf("(unavailable)")
        return FIDS.mapIndexed { i, f ->
            val (status, value) = readings[i]
            val rendered = if (status == 0) value.toString() else "(status=$status)"
            "${f.name}[dev=${f.dev} fid=${f.fid}]=$rendered"
        }
    }

    private const val TX_GET_INT = 5
}
