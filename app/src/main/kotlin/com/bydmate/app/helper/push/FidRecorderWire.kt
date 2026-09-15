package com.bydmate.app.helper.push

import android.os.Parcel

/**
 * Wire types of the diagnostic fid recorder plus the catalog grouping it registers from, shared by
 * the daemon (which owns the listeners) and the app (which starts/stops it and prints the dump
 * section). Kept out of both so the marshalling and the grouping can be tested on their own.
 *
 * Unlike the push channel, the recorder resolves fids on the DAEMON side: it subscribes to whole
 * devices, and only the daemon's own reflection over BYDAutoFeatureIds knows which fids this
 * firmware has. The app never sends a fid, only device ids.
 */

/** One catalog entry: the fid this firmware computed and the `Prefix.FIELD` symbol it carries. */
data class FidRecSymbol(val fid: Int, val symbol: String)

/** Per-device outcome of a recorder run. [error] is "-" when the registration threw nothing. */
data class FidRecDeviceRow(
    val device: Int,
    val registered: Int,
    val total: Int,
    val filtered: Int,
    val events: Int,
    val error: String,
)

/** One of the most talkative fids of the run. */
data class FidRecTopRow(
    val fid: Int,
    val symbol: String,
    val events: Int,
    val lastIntValue: Int,
    val lastDoubleValue: Double,
)

/** Full TX_REC_STATUS reply. [filePath] is empty when no run has opened a file yet. */
data class FidRecStatus(
    val running: Boolean,
    val devices: List<FidRecDeviceRow>,
    val totalEvents: Int,
    val top: List<FidRecTopRow>,
    val filePath: String = "",
    val fileBytes: Long = 0L,
)

/**
 * TX_REC_START reply: how many devices were attempted and how many fids came back registered.
 * [error] is "-" when the run started; anything else means nothing was registered and the reason
 * belongs in front of the user (today: the recorder could not open its file).
 */
data class FidRecStart(val devices: Int, val registered: Int, val error: String = FID_REC_NO_ERROR)

/** Registration outcome carried in [FidRecDeviceRow.error] when nothing went wrong. */
const val FID_REC_NO_ERROR = "-"

/** Hard cap on devices per start request — the whole map is 48 today. */
const val MAX_REC_DEVICES = 64

/** How many fids the status reply carries, most talkative first. */
const val REC_TOP_FIDS = 20

/**
 * Groups the daemon's fid catalog dump by autoservice device: `Prefix.FIELD=<int>` lines land on
 * the device whose `BYDAutoConstants.BYDAUTO_DEVICE_<NAME>` line the prefix belongs to.
 *
 * The nested class names of BYDAutoFeatureIds and the device constant names are spelled
 * differently (`Door` vs `DOOR_LOCK`, `Rear` vs `REAR_VIEW_MIRROR`, `Func` vs `FUNCNOTICE`), so a
 * prefix matches a device when the underscore-free device name starts with the upper-cased prefix.
 * A prefix that matches two devices is dropped rather than guessed; an exact match always wins.
 *
 * The first symbol wins for a fid listed twice (`_SET` aliases share addresses on some devices):
 * the recorder only needs a name to print, and the registration is by fid anyway.
 */
fun groupCatalogByDevice(dump: String): Map<Int, List<FidRecSymbol>> {
    val devices = HashMap<String, Int>()
    val byPrefix = LinkedHashMap<String, MutableList<FidRecSymbol>>()
    for (raw in dump.lineSequence()) {
        when (val line = parseCatalogLine(raw)) {
            is CatalogLine.Device -> devices[line.name] = line.id
            is CatalogLine.Symbol -> byPrefix.getOrPut(line.prefix) { mutableListOf() } += line.symbol
            null -> Unit
        }
    }
    val grouped = LinkedHashMap<Int, MutableList<FidRecSymbol>>()
    val seen = HashMap<Int, MutableSet<Int>>()
    for ((prefix, symbols) in byPrefix) {
        val device = deviceForPrefix(prefix, devices) ?: continue
        val fids = seen.getOrPut(device) { mutableSetOf() }
        val target = grouped.getOrPut(device) { mutableListOf() }
        symbols.forEach { if (fids.add(it.fid)) target += it }
    }
    return grouped
}

/** One parsed dump line: a device constant, a namespaced symbol, or nothing we can use. */
private sealed interface CatalogLine {
    data class Device(val name: String, val id: Int) : CatalogLine
    data class Symbol(val prefix: String, val symbol: FidRecSymbol) : CatalogLine
}

/**
 * Parses `Prefix.FIELD=<int>` and `BYDAutoConstants.BYDAUTO_DEVICE_<NAME>=<int>`. The flat
 * `BYDAutoFeatureIds.FIELD` duplicates are dropped for the reason [com.bydmate.app.data.nativestack.FidCatalog]
 * drops them: the same field name lives under several nested classes, so the flat namespace
 * identifies no device. Values outside the Int range are not fids.
 */
private fun parseCatalogLine(raw: String): CatalogLine? {
    val line = raw.trim()
    val eq = line.lastIndexOf('=')
    if (eq <= 0 || eq == line.length - 1) return null
    val key = line.substring(0, eq)
    if (key.contains(' ')) return null
    val value = line.substring(eq + 1).toLongOrNull() ?: return null
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
    if (key.startsWith(DEVICE_PREFIX)) return CatalogLine.Device(key.removePrefix(DEVICE_PREFIX), value.toInt())
    if (key.startsWith("BYDAutoConstants.") || key.startsWith("BYDAutoFeatureIds.")) return null
    val dot = key.indexOf('.')
    if (dot <= 0 || dot == key.length - 1) return null
    return CatalogLine.Symbol(key.substring(0, dot), FidRecSymbol(value.toInt(), key))
}

/** The device id [prefix] belongs to, or null when no device matches it or several do. */
private fun deviceForPrefix(prefix: String, devices: Map<String, Int>): Int? {
    val wanted = prefix.uppercase()
    devices[wanted]?.let { return it }
    val matches = devices.entries.filter { it.key.replace("_", "").startsWith(wanted) }
    return matches.singleOrNull()?.value
}

private const val DEVICE_PREFIX = "BYDAutoConstants.BYDAUTO_DEVICE_"

/**
 * The recorder status as log/dump lines, shared by the daemon (broadcast answer) and the app
 * (`--- fid recorder ---` section), so both read the same way in a user log.
 */
fun fidRecorderStatusLines(status: FidRecStatus): List<String> = buildList {
    add("running=${status.running} devices=${status.devices.size} events=${status.totalEvents}")
    add("file=${status.filePath.ifEmpty { "-" }} bytes=${status.fileBytes}")
    status.devices.forEach {
        add(
            "dev=${it.device} registered=${it.registered}/${it.total} " +
                "filtered=${it.filtered} events=${it.events} err=${it.error}"
        )
    }
    status.top.forEach {
        add("top fid=${it.fid} ${it.symbol} events=${it.events} int=${it.lastIntValue} dbl=${it.lastDoubleValue}")
    }
}

fun writeRecStartRequest(p: Parcel, devices: IntArray) {
    p.writeInt(devices.size)
    devices.forEach { p.writeInt(it) }
}

/** Empty array when the count is missing, negative or above [MAX_REC_DEVICES]. */
fun readRecStartRequest(p: Parcel): IntArray {
    if (p.dataAvail() < INT_BYTES) return IntArray(0)
    val n = p.readInt()
    if (n !in 0..MAX_REC_DEVICES || p.dataAvail() < n * INT_BYTES) return IntArray(0)
    return IntArray(n) { p.readInt() }
}

fun writeRecStatus(p: Parcel, status: FidRecStatus) {
    p.writeInt(if (status.running) 1 else 0)
    p.writeInt(status.totalEvents)
    p.writeString(status.filePath)
    p.writeLong(status.fileBytes)
    p.writeInt(status.devices.size)
    status.devices.forEach { row ->
        p.writeInt(row.device)
        p.writeInt(row.registered)
        p.writeInt(row.total)
        p.writeInt(row.filtered)
        p.writeInt(row.events)
        p.writeString(row.error)
    }
    p.writeInt(status.top.size)
    status.top.forEach { row ->
        p.writeInt(row.fid)
        p.writeString(row.symbol)
        p.writeInt(row.events)
        p.writeInt(row.lastIntValue)
        p.writeDouble(row.lastDoubleValue)
    }
}

fun readRecStatus(p: Parcel): FidRecStatus {
    if (p.dataAvail() < INT_BYTES * 3) return FidRecStatus(false, emptyList(), 0, emptyList())
    val running = p.readInt() != 0
    val totalEvents = p.readInt()
    val filePath = p.readString().orEmpty()
    val fileBytes = p.readLong()
    if (p.dataAvail() < INT_BYTES) return FidRecStatus(running, emptyList(), totalEvents, emptyList(), filePath, fileBytes)
    val deviceCount = p.readInt()
    if (deviceCount !in 0..MAX_REC_DEVICES) {
        return FidRecStatus(running, emptyList(), totalEvents, emptyList(), filePath, fileBytes)
    }
    val devices = List(deviceCount) {
        FidRecDeviceRow(
            device = p.readInt(),
            registered = p.readInt(),
            total = p.readInt(),
            filtered = p.readInt(),
            events = p.readInt(),
            error = p.readString().orEmpty(),
        )
    }
    if (p.dataAvail() < INT_BYTES) return FidRecStatus(running, devices, totalEvents, emptyList(), filePath, fileBytes)
    val topCount = p.readInt()
    if (topCount !in 0..REC_TOP_FIDS) {
        return FidRecStatus(running, devices, totalEvents, emptyList(), filePath, fileBytes)
    }
    val top = List(topCount) {
        FidRecTopRow(
            fid = p.readInt(),
            symbol = p.readString().orEmpty(),
            events = p.readInt(),
            lastIntValue = p.readInt(),
            lastDoubleValue = p.readDouble(),
        )
    }
    return FidRecStatus(running, devices, totalEvents, top, filePath, fileBytes)
}

private const val INT_BYTES = 4
