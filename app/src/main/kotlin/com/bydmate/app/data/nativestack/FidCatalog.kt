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
 * [flatLayout] marks a catalog built from a dump without nested classes (see [parse]).
 */
data class FidCatalog(
    val symbols: Map<String, Int>,
    val devices: Map<String, Int>,
    val totalSymbols: Int = symbols.size,
    val flatLayout: Boolean = false,
) {
    fun fidOf(symbol: String): Int? = symbols[symbol]

    fun deviceOf(name: String): Int? = devices[name]

    companion object {
        val EMPTY = FidCatalog(emptyMap(), emptyMap(), 0)

        private const val DEVICE_PREFIX = "BYDAutoConstants.BYDAUTO_DEVICE_"
        private const val FLAT_PREFIX = "BYDAutoFeatureIds."

        /**
         * Parses the dump text. Header lines, blank lines and anything that is not
         * `Prefix.FIELD=<int>` are skipped.
         *
         * `BYDAutoFeatureIds.FIELD` lines (the flat duplicates of the nested-class
         * constants) are ignored whenever the dump carries a single nested line, so every
         * firmware with nested classes parses as it always did. A dump with no nested line
         * at all (#443, Yuan Pro 2022 on DiLink 3.0) maps each wanted `Prefix.FIELD` of
         * [flatAliases] onto its flat `BYDAutoFeatureIds.FIELD` value: on every dump that has
         * both namespaces the flat value equals the nested one.
         *
         * Values outside the Int range (the SDK also exposes a few `long` constants)
         * are skipped — a fid is always an int. A symbol listed twice with different
         * values is dropped entirely: it cannot identify an address either.
         */
        fun parse(dump: String, flatAliases: Collection<String> = emptyList()): FidCatalog {
            val symbols = Symbols()
            val flat = Symbols()
            val devices = HashMap<String, Int>()
            for (raw in dump.lineSequence()) {
                val (key, value) = entry(raw) ?: continue
                when {
                    key.startsWith(DEVICE_PREFIX) -> devices[key.removePrefix(DEVICE_PREFIX)] = value
                    key.startsWith(FLAT_PREFIX) -> flat.put(key.removePrefix(FLAT_PREFIX), value)
                    key.startsWith("BYDAutoConstants.") -> Unit
                    isSymbol(key) -> symbols.put(key, value)
                }
            }
            if (symbols.lines > 0 || flat.lines == 0 || flatAliases.isEmpty()) {
                val nested = symbols.resolved()
                return FidCatalog(nested, devices, nested.size)
            }
            val byName = flat.resolved()
            val aliased = flatAliases.mapNotNull { alias -> byName[alias.substringAfter('.', "")]?.let { alias to it } }
            return FidCatalog(aliased.toMap(), devices, flat.lines, flatLayout = true)
        }

        /** `KEY=<int>` of one dump line, or null for anything else (headers, blanks, long constants). */
        private fun entry(raw: String): Pair<String, Int>? {
            val line = raw.trim()
            val eq = line.lastIndexOf('=')
            if (eq <= 0 || eq == line.length - 1) return null
            val value = line.substring(eq + 1).toLongOrNull() ?: return null
            if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
            return line.substring(0, eq) to value.toInt()
        }

        /** A `Prefix.FIELD` key: a dot with text on both sides, no spaces. */
        private fun isSymbol(key: String): Boolean {
            val dot = key.indexOf('.')
            return dot > 0 && dot < key.length - 1 && !key.contains(' ')
        }

        /** Name → value, where a name listed twice with different values is dropped. */
        private class Symbols {
            private val values = HashMap<String, Int>()
            private val ambiguous = HashSet<String>()
            var lines = 0
                private set

            fun put(name: String, value: Int) {
                if (name.isEmpty() || name.contains(' ')) return
                lines++
                val previous = values.put(name, value)
                if (previous != null && previous != value) ambiguous += name
            }

            fun resolved(): Map<String, Int> = values - ambiguous
        }
    }
}
