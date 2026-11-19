package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.FidRegistry.TX_GET_FLOAT
import com.bydmate.app.data.autoservice.FidRegistry.TX_GET_INT
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.BatchReadItem

/**
 * #429: on some DiLink 5 cars the float read of the battery current answers -1.0 while its push
 * carries an int around 5000, so «Техника» shows 0 W. While «Запись логов» records, this reads
 * the battery current fid as an int and three candidate fids both ways, at most once per
 * [MIN_GAP_MS], into one `battery current probe:` line next to voltage, current, front motor
 * current and speed, so the next log shows which address carries the current. Read-only, outside
 * the poll batch and the resolver.
 */
internal class BatteryCurrentProbe {

    /** One candidate read: the dump label, its catalog symbol and the Leopard 3 address. */
    private data class Candidate(val name: String, val symbol: String, val device: Int, val l3Fid: Int, val tx: Int)

    private var lastReadAt: Long? = null

    /** True when a read is due at [now] (elapsed ms); a true answer starts the next gap. */
    @Synchronized
    fun shouldRead(now: Long): Boolean {
        val last = lastReadAt
        if (last != null && now - last < MIN_GAP_MS) return false
        lastReadAt = now
        return true
    }

    /**
     * The battery current at its resolved [hvCurrent] address read as an int, then each candidate
     * as int and float; a candidate sits at the catalog number where [catalog] knows its symbol.
     */
    fun items(catalog: FidCatalog?, hvCurrent: FidAddress): List<BatchReadItem> =
        listOf(BatchReadItem(TX_GET_INT, hvCurrent.device, hvCurrent.fid)) + CANDIDATES.map {
            BatchReadItem(it.tx, it.device, catalog?.fidOf(it.symbol) ?: it.l3Fid)
        }

    /**
     * One line from the snapshot [data] and the raw (status, word) [readings] in [items] order: an
     * int read prints its word, a float read its float value, a failed read `?`. No answer or a
     * length mismatch is a single `(unavailable)` line.
     */
    fun line(data: DiParsData?, readings: List<Pair<Int, Int>>?): String {
        if (readings == null || readings.size != CANDIDATES.size + 1) return "$PREFIX (unavailable)"
        val rows = listOf("chgInt" to TX_GET_INT) + CANDIDATES.map { it.name to it.tx }
        val values = rows.zip(readings).joinToString(" ") { (row, reading) ->
            val (name, tx) = row
            val (status, word) = reading
            val value = when {
                status != 0 -> "?"
                tx == TX_GET_FLOAT -> java.lang.Float.intBitsToFloat(word).toString()
                else -> word.toString()
            }
            "$name=$value"
        }
        return "$PREFIX V=${data?.hvVoltage ?: "?"} I=${data?.hvCurrent ?: "?"} " +
            "cF=${data?.motorCurrentFront ?: "?"} spd=${data?.speed ?: "?"} $values"
    }

    companion object {
        const val MIN_GAP_MS = 10_000L
        private const val PREFIX = "battery current probe:"

        private const val NOT_CONVERT = "Charging.CHARGING_CHARGE_CURRENT_NOT_CONVERT"
        private const val GB_CURRENT = "Gb.GB_BATTERY_CCURRENT"
        private const val PSPK_CURRENT = "Instrument.INSTRUMENT_PSPK_BATTERY_CURRENT"

        private val CANDIDATES = listOf(
            Candidate("notConv5", NOT_CONVERT, 1009, -1002438632, TX_GET_INT),
            Candidate("notConv7", NOT_CONVERT, 1009, -1002438632, TX_GET_FLOAT),
            Candidate("gb5", GB_CURRENT, 1039, 677380120, TX_GET_INT),
            Candidate("gb7", GB_CURRENT, 1039, 677380120, TX_GET_FLOAT),
            Candidate("pspk5", PSPK_CURRENT, 1007, 1298153504, TX_GET_INT),
            Candidate("pspk7", PSPK_CURRENT, 1007, 1298153504, TX_GET_FLOAT),
        )

        /** The candidate symbols, kept by the catalog cache so a cached catalog still knows them. */
        val SYMBOLS: List<String> = listOf(NOT_CONVERT, GB_CURRENT, PSPK_CURRENT)
    }
}
