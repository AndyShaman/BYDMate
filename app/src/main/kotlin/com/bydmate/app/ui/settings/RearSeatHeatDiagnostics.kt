package com.bydmate.app.ui.settings

import com.bydmate.app.data.nativestack.FidCatalog
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.RearSeat
import com.bydmate.app.data.vehicle.RearSeatAddresses
import com.bydmate.app.data.vehicle.RearSeatHeat

/**
 * READ-side state of the rear seat heat gate (#327) for the diagnostic dump: per seat the
 * fitted flag and the state this car's catalog points at, raw, and whether the editor offers
 * the seat's tile. Same gate as [com.bydmate.app.data.vehicle.RearSeatHeatChannel.equipped].
 */
internal object RearSeatHeatDiagnostics {

    /** The seats this car's catalog has addresses for. */
    fun addresses(catalog: FidCatalog?): Map<RearSeat, RearSeatAddresses> =
        RearSeat.entries.mapNotNull { seat -> RearSeatHeat.addresses(catalog, seat)?.let { seat to it } }.toMap()

    /** Fitted flag then state per seat of [addresses], in [RearSeat] order. */
    fun batchItems(addresses: Map<RearSeat, RearSeatAddresses>): List<BatchReadItem> =
        addresses.values.flatMap {
            listOf(BatchReadItem(TX_GET_INT, RearSeatHeat.READ_DEV, it.hasFid), BatchReadItem(TX_GET_INT, RearSeatHeat.READ_DEV, it.stateFid))
        }

    /**
     * One line per seat. [readings] are the raw (status, value) pairs of [batchItems]; null or a
     * length mismatch (daemon unreachable) prints every value as «err».
     */
    fun format(catalog: FidCatalog?, readings: List<Pair<Int, Int>>?): List<String> {
        val addresses = addresses(catalog)
        val values = readings?.takeIf { it.size == addresses.size * 2 }
            ?.map { (status, value) -> if (status == 0) value else null }
        var i = 0
        return RearSeat.entries.map { seat ->
            val label = seat.name.lowercase()
            val addr = addresses[seat]
                ?: return@map "$label: -> hidden ${if (catalog == null) "no catalog" else "not in catalog"}"
            val has = values?.get(i)
            val state = values?.get(i + 1)
            i += 2
            val verdict = RearSeatHeat.hiddenReason(has)?.let { "hidden $it" } ?: "shown"
            "$label: has[${addr.hasFid}]=${has ?: "err"} state[${addr.stateFid}]=${state ?: "err"} set=${addr.setFid} -> $verdict"
        }
    }

    private const val TX_GET_INT = 5
}
