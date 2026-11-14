package com.bydmate.app.ui.settings

import com.bydmate.app.data.autoservice.FidRegistry.TX_GET_INT
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.WriteAllowlist

/**
 * READ-side state of the electric spoiler (#383) for the diagnostic dump, from the Setting device
 * (dev=1023): whether one is fitted, its status and position. Read only.
 */
internal object SpoilerDiagnostics {

    private const val DEV = WriteAllowlist.SPOILER_DEV

    /** Printed label to fid, all tx=5, in print order. */
    private val FIDS: List<Pair<String, Int>> = listOf(
        "config" to WriteAllowlist.SPOILER_CONFIG_FID,      // 2 = fitted
        "status" to WriteAllowlist.SPOILER_STATUS_FID,      // 2/5 up, 3/6 down, 4 anti-pinch
        "position" to WriteAllowlist.SPOILER_POSITION_FID,  // raw
    )

    fun batchItems(): List<BatchReadItem> = FIDS.map { (_, fid) -> BatchReadItem(TX_GET_INT, DEV, fid) }

    /** One `spoiler:` line from raw (status, value) pairs in [FIDS] order. */
    fun line(readings: List<Pair<Int, Int>>?): String {
        val rows = FIDS.withReadings(readings) ?: return "spoiler: (unavailable)"
        return "spoiler: " +
            rows.joinToString(" ") { (row, reading) -> fidDumpRow(row.first, DEV, row.second, reading) }
    }
}
