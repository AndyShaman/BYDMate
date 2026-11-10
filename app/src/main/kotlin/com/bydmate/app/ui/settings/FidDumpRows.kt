package com.bydmate.app.ui.settings

/**
 * Shared shape of the diagnostic dump's fid rows: `name[dev=D fid=F]=value`, `(status=N)` instead
 * of the value for an autoservice error. [tx] is printed only by the blocks that mix transacts.
 */
internal fun fidDumpRow(name: String, dev: Int, fid: Int, reading: Pair<Int, Int>, tx: Int? = null): String {
    val (status, value) = reading
    val rendered = if (status == 0) value.toString() else "(status=$status)"
    val address = if (tx == null) "dev=$dev fid=$fid" else "dev=$dev fid=$fid tx=$tx"
    return "$name[$address]=$rendered"
}

/**
 * [readings] paired with this table's rows in order; null when there is no answer (daemon
 * unreachable, timeout, old daemon without batch support) or its length does not match.
 */
internal fun <T> List<T>.withReadings(readings: List<Pair<Int, Int>>?): List<Pair<T, Pair<Int, Int>>>? =
    if (readings == null || readings.size != size) null else zip(readings)
