package com.bydmate.app.data.telegram

import com.bydmate.app.data.local.entity.ActionDef
import org.json.JSONException
import org.json.JSONObject

/** Automation action kind: payload `{"fields":["soc",…],"text":"…"}`. */
const val TELEGRAM_REPORT_KIND = "telegram_report"

/** Payload key the rule name is put under right before the dispatch; never saved with the rule. */
const val TELEGRAM_REPORT_RULE_KEY = "rule"

/**
 * The report header names the rule, and the dispatcher only sees the action: this hands the
 * current name over for one run, so a renamed rule reports under its new name. Other kinds pass as is.
 */
fun ActionDef.withReportRuleName(ruleName: String): ActionDef {
    if (kind != TELEGRAM_REPORT_KIND) return this
    val json = try { JSONObject(payload ?: "{}") } catch (e: JSONException) { JSONObject() }
    return copy(payload = json.put(TELEGRAM_REPORT_RULE_KEY, ruleName).toString())
}
