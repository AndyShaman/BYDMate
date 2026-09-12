package com.bydmate.app.data.nativestack

/** One WRITE address for the dump: which action(s) use it and where it writes. */
data class WriteFidRow(val action: String, val device: Int, val fid: Int)

/**
 * `--- fid resolve ---` section of the diagnostic dump: where the catalog came from,
 * every READ address the catalog moved (or tried to), and the WRITE addresses with the
 * value this car's catalog holds for the same symbol.
 *
 * The write half is evidence only — write fids stay hardcoded.
 */
object FidResolveDiagnostics {

    fun format(
        table: ResolvedFidTable,
        catalog: FidCatalog?,
        writeFids: List<WriteFidRow>,
        resolveStatus: String = "",
    ): List<String> {
        val lines = mutableListOf<String>()
        // How many attempts the resolution has spent and whether more are coming: a car that
        // stayed on constants looks the same in the lines below whether it gave up or never
        // reached the car at all.
        if (resolveStatus.isNotEmpty()) lines += resolveStatus
        lines += if (catalog == null) {
            "catalog: none (source=${table.source})"
        } else {
            "catalog: source=${table.source} symbols=${catalog.totalSymbols} devices=${catalog.devices.size}"
        }
        lines += table.summary()
        val moved = table.notes.filter { it.outcome != FidResolution.CONST }
        if (moved.isEmpty()) {
            lines += "(every entry on its constant)"
        } else {
            moved.forEach { lines += it.render() }
        }
        if (writeFids.isNotEmpty()) {
            lines += "write (manual):"
            writeFids
                .groupBy { it.device to it.fid }
                .toSortedMap(compareBy({ it.first }, { it.second }))
                .forEach { (address, rows) ->
                    val (device, fid) = address
                    val actions = rows.map { it.action }.sorted().joinToString("/")
                    val symbol = WriteFidSymbols.byFid[fid]
                    val here = symbol?.let { catalog?.fidOf(it) }
                    lines += "  ${actions.take(60)} dev=$device fid=$fid " +
                        "${symbol ?: "(no symbol)"} catalog=${here ?: "-"}"
                }
        }
        return lines
    }
}
