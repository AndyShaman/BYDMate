package com.bydmate.app.data.nativestack

/**
 * Process-wide holder of the READ addresses in force right now.
 *
 * Starts on the compiled constants, so every reader behaves exactly as it did before
 * the catalog existed; [install] swaps in a resolved table once (and only once) the
 * firmware catalog has been read and its candidates probed. Readers ask by field name
 * on every use — they must not cache the address, or they would keep the constant a
 * later resolution replaced.
 *
 * A plain object rather than an injected singleton: the readers include objects and
 * companions (SeatsDiagnostics, WindowChannelRouter) that have no graph to inject into.
 */
object FidAddresses {

    @Volatile
    var table: ResolvedFidTable = FidResolver.constants(FidMap.all)
        private set

    fun install(resolved: ResolvedFidTable) {
        table = resolved
    }

    /** Back to the compiled constants. For tests. */
    fun resetToConstants() {
        table = FidResolver.constants(FidMap.all)
    }

    fun of(field: String): FidAddress = table.address(field)

    fun fid(field: String): Int = table.fid(field)

    fun device(field: String): Int = table.device(field)

    /** INT_SCALED scale in force for [field] with the address currently installed. */
    fun scale(field: String): Double = table.scale(field)
}
