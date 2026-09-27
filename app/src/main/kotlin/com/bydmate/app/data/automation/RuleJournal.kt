package com.bydmate.app.data.automation

import android.util.Log
import com.bydmate.app.R
import com.bydmate.app.data.autoservice.LogThrottle
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.util.AppStrings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The rule journal entries that are not a run of the actions: a confirmation cancelled or left
 * unanswered, a manual start refused by «Только на парковке», a one-shot rule that expired. Each
 * carries a `reason` in the app language (the journal shows the first one). Every attempt gets
 * its journal row; the log line goes through [skipLog], one per rule and reason per minute, the
 * same throttle as the engine's other «skipped» lines. Also keeps the journal bounded and prints
 * it for the diagnostic dump.
 */
internal class RuleJournal(
    private val ruleLogDao: RuleLogDao,
    private val appStrings: AppStrings,
    private val skipLog: LogThrottle = LogThrottle(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun cancelled(rule: RuleEntity, snapshot: String, at: Long) {
        logSkip(rule, "confirm cancelled")
        insert(rule, snapshot, at, "cancelled", R.string.auto_log_confirm_cancelled)
    }

    suspend fun timeout(rule: RuleEntity, snapshot: String, at: Long) {
        logSkip(rule, "confirm timed out")
        insert(rule, snapshot, at, "timeout", R.string.auto_log_confirm_timeout)
    }

    suspend fun parkRequired(rule: RuleEntity, snapshot: String, gear: Int?) {
        logSkip(rule, "park only", ", gear=$gear")
        insert(rule, snapshot, System.currentTimeMillis(), "skipped", R.string.auto_log_park_required)
    }

    private fun logSkip(rule: RuleEntity, reason: String, detail: String = "") {
        if (skipLog.shouldLog("${rule.id}:$reason", nowMs())) {
            Log.i(TAG, "rule ${rule.id} '${rule.name}' skipped: $reason$detail")
        }
    }

    suspend fun oneShotExpired(rule: RuleEntity, value: String, now: Long) {
        Log.i(TAG, "one-shot rule ${rule.id} '${rule.name}' expired (at=$value), switched off")
        val snapshot = JSONObject().put(OneShotTrigger.KIND, value).toString()
        insert(rule, snapshot, now, "expired", R.string.auto_log_one_shot_expired)
    }

    private suspend fun insert(rule: RuleEntity, snapshot: String, at: Long, result: String, reason: Int) {
        val entry = JSONObject().put("result", result).put("reason", appStrings.get(reason))
        ruleLogDao.insert(
            RuleLogEntity(
                ruleId = rule.id,
                ruleName = rule.name,
                triggeredAt = at,
                triggersSnapshot = snapshot,
                actionsResult = JSONArray().put(entry).toString(),
                success = false,
            )
        )
    }

    /** Drops entries older than [MAX_AGE_MS] and all but the newest [MAX_ROWS]. */
    suspend fun prune(now: Long) {
        val old = ruleLogDao.deleteOlderThan(now - MAX_AGE_MS)
        val extra = ruleLogDao.trimToNewest(MAX_ROWS)
        Log.i(TAG, "journal pruned: older than 30 days=$old, over $MAX_ROWS rows=$extra")
    }

    /**
     * The newest [DUMP_ENTRIES] entries, one line each: time, rule, verdict, then every step as
     * `kind:ok` or `kind:fail(reason)`. Commands and payloads stay out (numbers, addresses), and
     * so does any link a stored reason still carries: see [redactUris].
     */
    suspend fun dumpLines(): List<String> {
        val entries = ruleLogDao.getRecentList(DUMP_ENTRIES)
        if (entries.isEmpty()) return listOf("(journal empty)")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return entries.map { e ->
            "${fmt.format(Date(e.triggeredAt))} rule=${e.ruleId} \"${e.ruleName}\" ok=${e.success} " +
                "steps=[${steps(e.actionsResult)}] trig=${e.triggersSnapshot}"
        }
    }

    private fun steps(actionsResult: String): String = try {
        val arr = JSONArray(actionsResult)
        (0 until arr.length()).joinToString(", ") { i ->
            val o = arr.getJSONObject(i)
            val kind = o.optString("kind").ifEmpty { o.optString("result", "?") }
            val reason = o.optString("reason")
            val ok = o.optBoolean("success", false)
            if (ok) "$kind:ok" else "$kind:fail" + (if (reason.isNotEmpty()) "(${redactUris(reason)})" else "")
        }
    } catch (_: Exception) {
        "?"
    }

    companion object {
        private const val TAG = "AutomationEngine"
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ROWS = 2000
        const val DUMP_ENTRIES = 50

        // A link (scheme://…), a bare navigator or map URI (geo:, yandexnavi:…) or an intent's
        // data (dat=…): older builds put the exception message, the whole intent, in the reason.
        private val URI_LIKE = Regex(
            """\bdat=\S+|[A-Za-z][A-Za-z0-9+.\-]*://\S+|""" +
                """\b(?:geo|yandexnavi|yandexmaps|dgis|tel|sms|smsto|mailto|intent|content|file):\S+""",
            RegexOption.IGNORE_CASE,
        )

        /** [text] with every URI-like run replaced by `<uri>`: tokens, numbers, coordinates. */
        internal fun redactUris(text: String): String = URI_LIKE.replace(text, "<uri>")
    }
}
