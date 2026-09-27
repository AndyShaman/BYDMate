package com.bydmate.app.data.telegram

import android.content.Context
import android.util.Log
import com.bydmate.app.helper.offreport.OffReportOutcome
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A power-off report the app handed to the daemon, kept on disk: what the next start re-sends when
 * the daemon could not. [createdMs] is when this text was built. toString never shows the chat or
 * the text.
 */
data class ArmedRecord(val id: String, val chatId: Long, val text: String, val createdMs: Long) {
    override fun toString(): String = "ArmedRecord(id=$id, len=${text.length})"
}

/**
 * What the power-off report keeps on disk between app processes: the last [MAX_RECORDS] reports
 * (the daemon may still hold the previous one when the car is switched off during a re-arm), the
 * heartbeat of the arming loop (when the app was last seen alive) and the outcome read from an old
 * daemon right before the bootstrap replaced it.
 */
interface PowerOffStore {
    /** Newest first. */
    fun records(): List<ArmedRecord>
    fun pushRecord(record: ArmedRecord)
    /** Synchronous: the caller has just queued the report, a second queueing must not follow. */
    fun clearRecords()
    fun heartbeat(): Long
    fun setHeartbeat(ms: Long)
    fun putCaptured(outcome: OffReportOutcome)
    /** The captured outcome, removed on read. */
    fun takeCaptured(): OffReportOutcome?

    companion object {
        const val MAX_RECORDS = 2
    }
}

internal class PrefsPowerOffStore(context: Context) : PowerOffStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun records(): List<ArmedRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ArmedRecord(o.getString("id"), o.getLong("chat"), o.getString("text"), o.getLong("created"))
            }
        } catch (e: JSONException) {
            Log.w(TAG, "offreport stored reports unreadable, cleared: ${e.javaClass.simpleName}")
            clearRecords()
            emptyList()
        }
    }

    override fun pushRecord(record: ArmedRecord) {
        val kept = (listOf(record) + records().filter { it.id != record.id }).take(PowerOffStore.MAX_RECORDS)
        val array = JSONArray()
        kept.forEach {
            array.put(JSONObject().put("id", it.id).put("chat", it.chatId).put("text", it.text).put("created", it.createdMs))
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    override fun clearRecords() {
        prefs.edit().remove(KEY_RECORDS).commit()
    }

    override fun heartbeat(): Long = prefs.getLong(KEY_HEARTBEAT, 0L)

    override fun setHeartbeat(ms: Long) {
        prefs.edit().putLong(KEY_HEARTBEAT, ms).apply()
    }

    override fun putCaptured(outcome: OffReportOutcome) {
        val json = JSONObject().put("id", outcome.id).put("state", outcome.state)
            .put("off", outcome.powerOffMs).put("sent", outcome.sentAtMs)
            .put("attempts", outcome.attempts).put("rc", outcome.rc)
        prefs.edit().putString(KEY_CAPTURED, json.toString()).commit()
    }

    override fun takeCaptured(): OffReportOutcome? {
        val raw = prefs.getString(KEY_CAPTURED, null) ?: return null
        prefs.edit().remove(KEY_CAPTURED).commit()
        return try {
            val o = JSONObject(raw)
            OffReportOutcome(
                o.getString("id"), o.getInt("state"), o.getLong("off"), o.getLong("sent"),
                o.getInt("attempts"), o.getString("rc"),
            )
        } catch (e: JSONException) {
            Log.w(TAG, "offreport captured outcome unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "TgReport"
        const val PREFS = "tg_offreport"
        const val KEY_RECORDS = "reports"
        const val KEY_HEARTBEAT = "heartbeat"
        const val KEY_CAPTURED = "captured"
    }
}
