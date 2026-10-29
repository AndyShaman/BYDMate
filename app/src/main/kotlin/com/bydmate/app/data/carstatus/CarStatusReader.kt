package com.bydmate.app.data.carstatus

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the stock `content://com.byd.carStatusProvider/car_status` table, read-only: the app never
 * writes a key there (the `set_*` keys are the owner's own service interval). Exported without a
 * permission on DiLink 5.0 and DiLink 3; on a car without it every read is null and the rows hide.
 */
@Singleton
class CarStatusReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var lastLine: String? = null

    /** One query on the IO dispatcher; null when the provider is missing or refuses. */
    suspend fun read(): CarServiceStatus? {
        val (status, line) = query()
        if (line != lastLine) {
            lastLine = line
            Log.i(TAG, line)
        }
        return status
    }

    /** The same read line for the diagnostic dump, without touching the on-change log state. */
    suspend fun dumpLine(): String = query().second

    /**
     * Emits when one of the watched keys changes (per-key observers, debounced). Never the bare
     * URI: the provider also keeps `travel_points_*`, which change all the time while driving.
     */
    @OptIn(FlowPreview::class)
    @Suppress("TooGenericExceptionCaught") // a firmware without the provider throws on register
    fun changes(): Flow<Unit> = callbackFlow {
        val resolver = context.contentResolver
        var failure: String? = null
        val observers = WATCHED_KEYS.mapNotNull { key ->
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    Log.i(TAG, "observer: change key=$key")
                    trySend(Unit)
                }
            }
            try {
                resolver.registerContentObserver(Uri.withAppendedPath(CONTENT_URI, key), false, observer)
                observer
            } catch (e: Exception) {
                failure = "${e.javaClass.simpleName}: ${e.message}"
                null
            }
        }
        Log.i(TAG, "observer: watching ${observers.size}/${WATCHED_KEYS.size} keys" +
            (failure?.let { " reason=$it" } ?: ""))
        awaitClose { observers.forEach { resolver.unregisterContentObserver(it) } }
    }.debounce(DEBOUNCE_MS)

    @Suppress("TooGenericExceptionCaught") // whatever a foreign provider throws means "no rows"
    private suspend fun query(): Pair<CarServiceStatus?, String> = withContext(Dispatchers.IO) {
        val rows: Map<String, String?> = try {
            context.contentResolver.query(CONTENT_URI, arrayOf(COL_KEY, COL_VALUE), null, null, null)
                ?.use { c ->
                    val k = c.getColumnIndex(COL_KEY)
                    val v = c.getColumnIndex(COL_VALUE)
                    buildMap<String, String?> {
                        while (c.moveToNext()) {
                            val key = if (k >= 0) c.getString(k) else null
                            if (key != null) put(key, if (v >= 0) c.getString(v) else null)
                        }
                    }
                }
        } catch (e: Exception) {
            return@withContext null to "read: unavailable reason=${e.javaClass.simpleName}: ${e.message}"
        } ?: return@withContext null to "read: unavailable reason=null cursor"
        val status = CarStatusParser.parse(rows)
        status to okLine(rows, status)
    }

    private fun okLine(rows: Map<String, String?>, s: CarServiceStatus): String {
        fun raw(key: String) = rows[key] ?: "-"
        val issue = rows[CarStatusParser.ISSUE]?.take(ISSUE_LOG_CHARS) ?: "-"
        return "read: ok rows=${rows.size} issueNum=${raw(CarStatusParser.ISSUE_NUM)} issue=\"$issue\" " +
            "timeSw=${raw(CarStatusParser.TIME_SWITCH)} time=${raw(CarStatusParser.TIME)} " +
            "setTime=${raw(CarStatusParser.SET_TIME)} mileSw=${raw(CarStatusParser.MILE_SWITCH)} " +
            "mile=${raw(CarStatusParser.MILE)} hevMile=${raw(CarStatusParser.HEV_MILE)} " +
            "setMile=${raw(CarStatusParser.SET_MILE)} -> faults=${s.faults} days=${s.daysLeft} km=${s.kmLeft}"
    }

    private companion object {
        const val TAG = "CarStatus"
        // Lazy: parsing in the static initialiser breaks every plain-JVM test that mocks this class.
        val CONTENT_URI: Uri by lazy { Uri.parse("content://com.byd.carStatusProvider/car_status") }
        const val COL_KEY = "key"
        const val COL_VALUE = "value"
        const val DEBOUNCE_MS = 1_000L
        const val ISSUE_LOG_CHARS = 120
        val WATCHED_KEYS = listOf(
            CarStatusParser.ISSUE_NUM,
            CarStatusParser.TIME,
            CarStatusParser.MILE,
            CarStatusParser.HEV_MILE,
            CarStatusParser.TIME_SWITCH,
            CarStatusParser.MILE_SWITCH,
            CarStatusParser.ISSUE,
            CarStatusParser.SET_MILE,
            CarStatusParser.SET_HEV_MILE,
        )
    }
}
