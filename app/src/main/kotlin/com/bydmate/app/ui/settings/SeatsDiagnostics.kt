package com.bydmate.app.ui.settings

import android.content.Context
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.SeatCommandJournal

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
        // Passenger side: these two are LEVELS (0=off, 1..5), not the 1=on/2=off status enum
        // the driver labels above carry — the catalog names them AC_PASSENGER_SEAT_*_LEVEL.
        SeatFid("vent_level_passenger", 1000, 711983128),
        SeatFid("heat_level_passenger", 1000, 711983132),
        SeatFid("config_vent_lf", 1000, 715132952),
        SeatFid("config_heat_lf", 1000, 715132955),
        SeatFid("has_driver_seat_ventilating", 1023, -811597816),
        SeatFid("has_driver_seat_heating", 1023, -811597813),
        // Candidate status families, read-only. The 0x29e0/0x2a70 family above is dead on
        // Song L / Han EV (reads 0 with ventilation physically running), so the dump also
        // samples the two other seat families in the catalog: the 3CE hal_only block and the
        // LRSE rear-seat block. Whichever answers on the next field dump is the live one
        // there. Rear seats are read only — nothing actuates them.
        // The passenger status enum (counterpart of vent_status_driver/heat_status_driver).
        // Unvalidated on a live car, so the write-side readback does not use it yet — the
        // dump decides whether it answers at all.
        SeatFid("cand_passenger_vent_status", 1000, 711983112),
        SeatFid("cand_passenger_heat_status", 1000, 711983116),
        SeatFid("cand_3ce_vent_status_driver", 1000, 1021313032),
        SeatFid("cand_3ce_heat_status_driver", 1000, 1021313034),
        SeatFid("cand_3ce_vent_level_driver", 1000, 1021313044),
        SeatFid("cand_3ce_heat_level_driver", 1000, 1021313048),
        SeatFid("cand_lrse_vent_status_rl", 1000, 412180522),
        SeatFid("cand_lrse_heat_status_rl", 1000, 412180526),
        SeatFid("cand_lrse_vent_status_rr", 1000, 412180528),
        SeatFid("cand_lrse_heat_status_rr", 1000, 412180532),
        // The four LRSE level fids are printed by address: which one belongs to which
        // seat/function is not established, and guessing it in the label would mislead
        // whoever reads the dump.
        SeatFid("cand_lrse_level_1", 1000, 412180536),
        SeatFid("cand_lrse_level_2", 1000, 412180540),
        SeatFid("cand_lrse_level_3", 1000, 412180544),
        SeatFid("cand_lrse_level_4", 1000, 412180548),
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

    /**
     * Command-journal ring for the dump, oldest first, "(none)" while nothing was actuated.
     * Opens the journal's own prefs file read-only so the dump needs no extra injection.
     */
    fun journalLines(context: Context): List<String> {
        val lines = SeatCommandJournal(
            context.getSharedPreferences(SeatCommandJournal.PREFS_NAME, Context.MODE_PRIVATE)
        ).lines()
        return lines.ifEmpty { listOf("(none)") }
    }

    private const val TX_GET_INT = 5
}
