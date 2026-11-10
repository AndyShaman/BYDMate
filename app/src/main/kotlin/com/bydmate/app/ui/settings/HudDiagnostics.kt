package com.bydmate.app.ui.settings

import com.bydmate.app.data.autoservice.FidRegistry.TX_GET_INT
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.WriteAllowlist

/**
 * READ-side HUD content gates for the diagnostic dump (#269), from the Setting device (dev=1023):
 * the car's own «Опц. содержимое → Навигация» switch and its status. Read only.
 */
internal object HudDiagnostics {

    private const val DEV = 1023

    /** Printed label to fid, all tx=5, in print order. */
    private val FIDS: List<Pair<String, Int>> = listOf(
        "dynamic_navi_function_set" to 1276174394,    // SET_DYNAMIC_NAVI_FUNCTION_STATUS_SET (1=on, 2=off)
        "dynamic_navi_function_status" to 951058472,  // its status feedback (==1 means on)
    )

    fun batchItems(): List<BatchReadItem> = FIDS.map { (_, fid) -> BatchReadItem(TX_GET_INT, DEV, fid) }

    /** Same shape as [WindowDiagnostics.format]. */
    fun format(readings: List<Pair<Int, Int>>?): List<String> =
        FIDS.withReadings(readings)?.map { (row, reading) -> fidDumpRow(row.first, DEV, row.second, reading) }
            ?: listOf("(unavailable)")

    /** The trace form of the same read: `<set>/<status>`, each the value, `e<status>` for an
     *  autoservice error, `na` without an answer. */
    fun gate(readings: List<Pair<Int, Int>>?): String = FIDS.indices.joinToString("/") { i ->
        val (status, value) = readings?.takeIf { it.size == FIDS.size }?.get(i) ?: return@joinToString "na"
        if (status == 0) value.toString() else "e$status"
    }

    /** The HUD master switch (#292): its status feedback and whether a HUD is fitted, read only. */
    private val SWITCH_FIDS: List<Pair<String, Int>> = listOf(
        "status" to WriteAllowlist.HUD_SWITCH_STATUS_FID,  // 1 = on, 2 = off
        "config" to WriteAllowlist.HUD_CONFIG_FID,         // 1 = W-HUD, 2 = AR-HUD, else no HUD
    )

    fun switchBatchItems(): List<BatchReadItem> = SWITCH_FIDS.map { (_, fid) -> BatchReadItem(TX_GET_INT, DEV, fid) }

    /** One `hud_switch:` line from raw (status, value) pairs in [SWITCH_FIDS] order. */
    fun switchLine(readings: List<Pair<Int, Int>>?): String {
        val rows = SWITCH_FIDS.withReadings(readings) ?: return "hud_switch: (unavailable)"
        return "hud_switch: " +
            rows.joinToString(" ") { (row, reading) -> fidDumpRow(row.first, DEV, row.second, reading) }
    }
}
