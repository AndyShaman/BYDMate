package com.bydmate.app.ui.settings

import com.bydmate.app.data.autoservice.FidRegistry.TX_GET_FLOAT
import com.bydmate.app.data.autoservice.FidRegistry.TX_GET_INT
import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.nativestack.ResolvedFidTable
import com.bydmate.app.data.vehicle.BatchReadItem

/**
 * READ-side probe of the ICE-side parameters for the diagnostic dump (#184).
 *
 * Nothing in the app reads these yet: on a full EV they are expected to answer sentinels, and
 * which of them carry live values on a DM-i hybrid is exactly the open question. The dump
 * prints the raw word every address returned, sentinels included (65535 = fid/CAN link
 * missing, -10011 = wrong direction, -10013 = wrong transact), so an owner running the engine
 * can send one dump and settle it. The tx=7 rows carry IEEE-754 float bits, not a plain
 * number — the address is printed with its transact so the reader knows which is which.
 */
internal object HybridProbeDiagnostics {

    /** One printed row: the dump label plus the address it reads verbatim. */
    data class ProbeFid(val name: String, val dev: Int, val fid: Int, val tx: Int)

    val FIDS: List<ProbeFid> = listOf(
        ProbeFid("engine_rpm", 1012, 339738642, TX_GET_INT),
        ProbeFid("engine_power", 1012, 339738656, TX_GET_INT),
        ProbeFid("coolant_temp", 1012, 1320181824, TX_GET_INT),
        ProbeFid("oil_level", 1012, 89129016, TX_GET_INT),
        ProbeFid("fuel_percent", 1014, 1246785600, TX_GET_INT),
        ProbeFid("fuel_range_km", 1014, 1246773304, TX_GET_INT),
        ProbeFid("fuel_instant", 1014, 1246760996, TX_GET_FLOAT),
        ProbeFid("fuel_total", 1014, 1246760976, TX_GET_FLOAT),
        ProbeFid("fuel_avg_phm", 1014, 1246785552, TX_GET_FLOAT),
        ProbeFid("mileage_hev", 1014, 1246773264, TX_GET_INT),
        ProbeFid("mileage_ev", 1014, 1246773284, TX_GET_INT),
        ProbeFid("fuel_tank_cap", 1001, 1336934422, TX_GET_INT),
        ProbeFid("ev_range_km", 1014, 1246765118, TX_GET_INT),
        ProbeFid("elec_phm", 1014, 1032863794, TX_GET_FLOAT),
    )

    fun batchItems(): List<BatchReadItem> = FIDS.map { BatchReadItem(it.tx, it.dev, it.fid) }

    /**
     * Renders one line per address from raw (status, value) pairs in [FIDS] order. A null
     * [readings] (daemon unreachable, timeout, old daemon without batch support) or a length
     * mismatch collapses to a single "(unavailable)" line — same contract as the seats block.
     */
    fun format(readings: List<Pair<Int, Int>>?): List<String> =
        FIDS.withReadings(readings)?.map { (f, reading) -> fidDumpRow(f.name, f.dev, f.fid, reading, f.tx) }
            ?: listOf("(unavailable)")

    /** The polled hybrid fields at the addresses [table] puts them on, in [FidMap.hybrid] order. */
    fun tableItems(table: ResolvedFidTable): List<BatchReadItem> = FidMap.hybrid.map {
        BatchReadItem(it.transact, table.device(it.field), table.fid(it.field))
    }

    /**
     * The hybrid addresses in force (#394): one line per polled field with its resolution note
     * (const, catalog, or rejected with the probe value and reason) and the raw word that
     * address answers now, read through [tableItems]; no `now=` when the read failed.
     */
    fun formatTable(table: ResolvedFidTable, readings: List<Pair<Int, Int>>?): List<String> {
        val notes = table.notes.associateBy { it.field }
        val now = FidMap.hybrid.withReadings(readings)?.toMap()
        return listOf("hybrid table: source=${table.source} ${table.summary()}") + FidMap.hybrid.map { entry ->
            val address = table.address(entry.field)
            val note = notes[entry.field]?.render() ?: "${entry.field} dev=${address.device} fid=${address.fid}"
            val reading = now?.get(entry)?.let { (status, word) -> if (status == 0) " now=$word" else " now=(status=$status)" }
            "  $note${reading.orEmpty()}"
        }
    }
}
