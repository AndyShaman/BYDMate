package com.bydmate.app.data.nativestack

import android.content.Context
import android.os.Build
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.DumpFidsResult
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.HelperBinderProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the firmware's own fid catalog once per process and installs the resolved
 * READ addresses into [FidAddresses].
 *
 * The catalog comes from the helper daemon (TX_DUMP_FIDS). Because that depends on the
 * daemon being up, the parsed catalog is also cached in `filesDir`, keyed by
 * [Build.FINGERPRINT]: after the first successful dump every later process start resolves
 * from the file without waiting for the daemon. Until a catalog is in hand every reader
 * keeps using the compiled constants, so a failure here costs nothing.
 */
@Singleton
class FidCatalogManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helperClient: HelperClient,
    private val autoservice: AutoserviceClient,
) {

    private val lock = Mutex()

    /** True once a catalog has been resolved and installed; a failed attempt leaves it false. */
    @Volatile
    private var resolved = false

    /** Resolution attempts spent so far (diagnostic dump, retry budget). */
    @Volatile
    private var attempts = 0

    /**
     * True while a further attempt is worth making from the service's periodic tick: an attempt
     * was already spent, nothing is installed, and the tick budget is not exhausted.
     *
     * The daemon can be perfectly healthy while autoservice itself answers nothing yet (cold
     * start, #194 firmwares): the watchdog then never respawns anything, so the respawn path —
     * the only other re-trigger — never fires and the process would stay on compiled constants
     * for the whole session. The budget bounds the retries; a respawn still gets its own attempt
     * regardless, exactly as before.
     */
    val resolvePending: Boolean
        get() = !resolved && attempts in 1 until MAX_RESOLVE_ATTEMPTS

    /** Resolution state for the `--- fid resolve ---` dump section. */
    val resolveStatus: String
        get() = "resolve: attempts=$attempts/$MAX_RESOLVE_ATTEMPTS " + when {
            resolved -> "installed"
            resolvePending -> "pending"
            attempts == 0 -> "not attempted"
            else -> "gave up"
        }

    /** Catalog in force, for the diagnostic dump. Null until one has been read. */
    @Volatile
    var catalog: FidCatalog? = null
        private set

    /**
     * Loads the catalog, resolves the addresses and installs them. Safe to call from
     * several places: the work happens once, but a call that could not reach the daemon
     * leaves the door open for a later one (the watchdog respawns the daemon mid-session).
     */
    suspend fun ensureResolved() {
        if (resolved) return
        lock.withLock {
            if (resolved) return
            attempts++
            if (attempts > 1) {
                val budget = if (attempts <= MAX_RESOLVE_ATTEMPTS) "$attempts/$MAX_RESOLVE_ATTEMPTS"
                    else "$attempts (beyond the tick budget)"
                Log.i(TAG, "fid resolve: retry $budget")
            }
            try {
                resolveOnce()
            } catch (e: Exception) {
                Log.w(TAG, "fid catalog: resolution failed (${e.message}), staying on constants")
            }
            if (!resolved && attempts == MAX_RESOLVE_ATTEMPTS) {
                Log.w(TAG, "fid resolve: gave up after $MAX_RESOLVE_ATTEMPTS attempts, " +
                    "staying on constants until the daemon is respawned")
            }
        }
    }

    private suspend fun resolveOnce() {
        val startedAtMs = System.currentTimeMillis()
        val fingerprint = Build.FINGERPRINT ?: ""
        var source = "file"
        var catalog = readCached(fingerprint)
        if (catalog == null) {
            source = "daemon"
            catalog = when (val dump = helperClient.dumpFids()) {
                is DumpFidsResult.Success -> FidCatalog.parse(dump.dump)
                DumpFidsResult.BinderAbsent -> {
                    Log.i(TAG, "fid catalog: daemon unreachable, staying on constants")
                    return
                }
                is DumpFidsResult.ReadError -> {
                    Log.w(TAG, "fid catalog: dump failed (${dump.detail}), staying on constants")
                    return
                }
            }
            if (catalog.symbols.isEmpty()) {
                Log.w(TAG, "fid catalog: dump carried no symbols, staying on constants")
                return
            }
            writeCache(fingerprint, catalog)
        }
        this.catalog = catalog
        Log.i(
            TAG,
            "fid catalog: symbols=${catalog.totalSymbols} devices=${catalog.devices.size} " +
                "source=$source fingerprint=$fingerprint took=${System.currentTimeMillis() - startedAtMs}ms"
        )

        // A dead read transport says nothing about the candidates. Give the car a few
        // seconds to answer before giving up on this attempt.
        var outcome = FidResolver.resolve(FidMap.all, catalog, probe, "$source $fingerprint")
        var attempt = 1
        while (outcome.probeTransportDead && attempt < PROBE_ATTEMPTS) {
            Log.i(TAG, "fid resolve: probe transport silent, retry $attempt in ${PROBE_RETRY_DELAY_MS}ms")
            delay(PROBE_RETRY_DELAY_MS)
            attempt++
            outcome = FidResolver.resolve(FidMap.all, catalog, probe, "$source $fingerprint")
        }
        if (outcome.probeTransportDead) {
            // Every probe read failed, so the answer says nothing about the candidates.
            // Installing now would freeze this car on constants for the session; the catalog
            // stays in hand (the dump still shows it) and the next attempt re-probes.
            Log.w(
                TAG,
                "fid resolve: probe transport silent after $PROBE_ATTEMPTS attempts, " +
                    "staying on constants until the next attempt"
            )
            return
        }
        FidAddresses.install(outcome.table)
        resolved = true
        Log.i(TAG, "fid resolve: ${outcome.table.summary()}")
    }

    /**
     * Probe transport: one daemon batch for everything, falling back to single ADB reads.
     *
     * The fallback cannot tell a tx=7 sentinel from a failed read (the float read filters
     * sentinels itself), which only ever means one more candidate keeps its constant.
     */
    private val probe = FidProbe { requests ->
        if (requests.size <= HelperBinderProtocol.MAX_BATCH_ITEMS) {
            val batch = helperClient.readBatch(requests.map { BatchReadItem(it.transact, it.device, it.fid) })
            if (batch != null && batch.size == requests.size) {
                return@FidProbe batch.map { (status, word) -> if (status == 0) word else null }
            }
        }
        requests.map { request ->
            when (request.transact) {
                5 -> autoservice.getIntRaw(request.device, request.fid)
                7 -> autoservice.getFloat(request.device, request.fid)
                    ?.let { java.lang.Float.floatToRawIntBits(it) }
                else -> null
            }
        }
    }

    /** Parsed catalog for this firmware, or null when there is no usable cache. */
    private fun readCached(fingerprint: String): FidCatalog? {
        val file = cacheFile()
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            val header = text.lineSequence().firstOrNull().orEmpty()
            if (!FidCatalogCache.isCurrent(header, fingerprint, BuildConfig.VERSION_CODE)) {
                Log.i(TAG, "fid catalog: cache is stale (other firmware or app version), re-reading from the daemon")
                file.delete()
                return null
            }
            val parsed = FidCatalog.parse(text)
            val total = FidCatalogCache.totalSymbols(header) ?: parsed.symbols.size
            if (parsed.symbols.isEmpty()) null else parsed.copy(totalSymbols = total)
        } catch (e: Exception) {
            Log.w(TAG, "fid catalog: cache unreadable (${e.message})")
            null
        }
    }

    /**
     * Persists the symbols this app can use (READ entries plus the WRITE symbols the dump
     * prints) and the whole device table. Keeping only those turns a ~700 KB dump into a few
     * KB; the original symbol count travels in the header so the log stays honest about what
     * the firmware reported.
     */
    private fun writeCache(fingerprint: String, catalog: FidCatalog) {
        try {
            val wanted = (FidMap.all.mapNotNull { it.symbol } + WriteFidSymbols.byFid.values).toSortedSet()
            val body = buildString {
                append(FidCatalogCache.header(fingerprint, BuildConfig.VERSION_CODE, catalog.totalSymbols))
                append('\n')
                catalog.devices.toSortedMap().forEach { (name, id) ->
                    append("BYDAutoConstants.BYDAUTO_DEVICE_").append(name).append('=').append(id).append('\n')
                }
                wanted.forEach { symbol ->
                    catalog.symbols[symbol]?.let { append(symbol).append('=').append(it).append('\n') }
                }
            }
            cacheFile().writeText(body)
        } catch (e: Exception) {
            Log.w(TAG, "fid catalog: cache write failed (${e.message})")
        }
    }

    private fun cacheFile(): File = File(context.filesDir, CACHE_FILE)

    private companion object {
        const val TAG = "FidCatalog"
        const val CACHE_FILE = "fid-catalog.txt"
        const val PROBE_ATTEMPTS = 3
        const val PROBE_RETRY_DELAY_MS = 10_000L

        /** Attempts the service's periodic tick may spend on an unfinished resolution, counting
         *  the startup one. Five attempts spaced by the health-check tick cover the minutes a
         *  cold autoservice needs, without polling the car for the rest of the session. */
        const val MAX_RESOLVE_ATTEMPTS = 5
    }
}

/**
 * Header line of the persisted catalog: which firmware it was read from, which app build
 * trimmed it, and how many symbols the firmware actually reported.
 *
 * The app version is part of the key because the file keeps only the symbols the app can
 * use: a build that adds entries would otherwise read an older build's trimmed file and
 * silently resolve the new ones as "symbol absent" until the firmware changed.
 */
internal object FidCatalogCache {

    private const val FINGERPRINT_KEY = "fingerprint="
    private const val APP_KEY = "app="
    private const val TOTAL_KEY = "symbols_total="

    fun header(fingerprint: String, appVersion: Int, totalSymbols: Int): String =
        "$FINGERPRINT_KEY$fingerprint $APP_KEY$appVersion $TOTAL_KEY$totalSymbols"

    fun isCurrent(header: String, fingerprint: String, appVersion: Int): Boolean =
        header.startsWith(FINGERPRINT_KEY) &&
            value(header, FINGERPRINT_KEY) == fingerprint &&
            value(header, APP_KEY) == appVersion.toString()

    fun totalSymbols(header: String): Int? = value(header, TOTAL_KEY)?.toIntOrNull()

    /** Value of [key] in the header, up to the next space. Null when the key is absent. */
    private fun value(header: String, key: String): String? {
        val at = header.indexOf(key)
        if (at < 0) return null
        return header.substring(at + key.length).substringBefore(' ').trim()
    }
}
