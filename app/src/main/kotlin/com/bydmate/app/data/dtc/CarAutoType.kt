package com.bydmate.app.data.dtc

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.split.readSystemProperty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The model code the car reports, BYD "AutoType" (Leopard 3 = 260): fid BODYWORK_AUTO_TYPE (dev
 * 1001, getInt), else `persist.sys.AutoType`. It does not change, so the first known value is kept
 * for the process; an unknown read is tried again on the next call.
 */
object CarAutoType {
    private const val TAG = "CarAutoType"
    private const val DEV = 1001
    private const val FID = 1087373328
    private const val TX_GET_INT = 5
    private const val PROP = "persist.sys.AutoType"

    /** BYD's own DiCarServer skips these as "no model". */
    private val UNKNOWN = setOf(0, 255, 65535)

    private val lock = Mutex()
    @Volatile private var known: Int? = null
    @Volatile private var source: String = "none"

    /** A model code, or null for a failed read, a sentinel, 0, 255, 65535 or a negative value. */
    fun parse(raw: Int?): Int? = raw?.let { SentinelDecoder.decodeInt(it) }?.takeIf { it > 0 && it !in UNKNOWN }

    /** The cached code, else one read (fid, then the property), logged as `car_autotype=… source=…`. */
    @Suppress("TooGenericExceptionCaught") // a failed read is an unknown model, never a crash
    suspend fun read(helper: HelperClient): Int? = lock.withLock {
        known?.let { return it }
        val fidReply = try {
            helper.readBatch(listOf(BatchReadItem(TX_GET_INT, DEV, FID)))?.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fid read failed ${e.javaClass.simpleName}")
            null
        }
        val fid = fidReply?.takeIf { it.first == 0 }?.let { parse(it.second) }
        val prop = if (fid == null) readSystemProperty(PROP) else null
        val fromProp = parse(prop?.trim()?.toIntOrNull())
        val value = fid ?: fromProp
        source = when {
            fid != null -> "fid"
            fromProp != null -> "prop"
            else -> "none"
        }
        Log.i(
            TAG,
            "car_autotype=${value ?: "?"} source=$source fid_reply=${fidReply?.let { "${it.first}/${it.second}" } ?: "-"} " +
                "prop=${prop ?: "-"}",
        )
        known = value
        value
    }

    /** The dump line; reads once when nothing is cached yet. */
    suspend fun dumpLine(helper: HelperClient): String {
        val value = read(helper)
        return "car_autotype=${value ?: "?"} source=$source"
    }
}
