package com.bydmate.app.data.nativestack

/**
 * The running firmware's own fid catalog, as produced by the helper daemon's
 * TX_DUMP_FIDS (reflection over `android.hardware.bydauto.BYDAutoFeatureIds` and
 * `BYDAutoConstants` plus their nested classes).
 *
 * [symbols] maps `Prefix.FIELD` (e.g. `Ac.AC_TEMP_MAIN`) to the value this firmware
 * computed for it — the BYD SDK derives fid numbers per platform at class-init time,
 * so the same symbol carries different numbers on different head units.
 * [devices] maps the bare device name (`AC`, `BODYWORK`, ...) to its device id.
 * [totalSymbols] is the number of symbol lines the dump carried, which stays honest
 * even when only the symbols this app needs were persisted.
 */
data class FidCatalog(
    val symbols: Map<String, Int>,
    val devices: Map<String, Int>,
    val totalSymbols: Int = symbols.size,
) {
    fun fidOf(symbol: String): Int? = symbols[symbol]

    fun deviceOf(name: String): Int? = devices[name]

    companion object {
        val EMPTY = FidCatalog(emptyMap(), emptyMap(), 0)

        private const val DEVICE_PREFIX = "BYDAutoConstants.BYDAUTO_DEVICE_"

        /**
         * Parses the dump text. Header lines, blank lines and anything that is not
         * `Prefix.FIELD=<int>` are skipped.
         *
         * `BYDAutoFeatureIds.FIELD` lines (the flat duplicates of the nested-class
         * constants) are ignored: the same field name appears under several nested
         * classes, so the flat namespace cannot identify an address.
         *
         * Values outside the Int range (the SDK also exposes a few `long` constants)
         * are skipped — a fid is always an int. A symbol listed twice with different
         * values is dropped entirely: it cannot identify an address either.
         */
        fun parse(dump: String): FidCatalog {
            val symbols = HashMap<String, Int>()
            val ambiguous = HashSet<String>()
            val devices = HashMap<String, Int>()
            for (raw in dump.lineSequence()) {
                val line = raw.trim()
                val eq = line.lastIndexOf('=')
                if (eq <= 0 || eq == line.length - 1) continue
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1).toLongOrNull() ?: continue
                if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) continue
                val intValue = value.toInt()
                if (key.startsWith(DEVICE_PREFIX)) {
                    devices[key.removePrefix(DEVICE_PREFIX)] = intValue
                    continue
                }
                if (key.startsWith("BYDAutoConstants.") || key.startsWith("BYDAutoFeatureIds.")) continue
                val dot = key.indexOf('.')
                if (dot <= 0 || dot == key.length - 1) continue
                if (key.contains(' ')) continue
                val previous = symbols.put(key, intValue)
                if (previous != null && previous != intValue) ambiguous += key
            }
            ambiguous.forEach { symbols.remove(it) }
            return FidCatalog(symbols, devices, symbols.size)
        }
    }
}
