package com.bydmate.app.data.telegram

import com.bydmate.app.R
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.ui.settings.telegramErrorText
import com.bydmate.app.util.AppStrings
import org.json.JSONObject

/** Automation action kind: payload `{"fields":["soc",…],"text":"…"}`. */
const val TELEGRAM_REPORT_KIND = "telegram_report"

/** Payload key the rule name is put under right before the dispatch; never saved with the rule. */
const val TELEGRAM_REPORT_RULE_KEY = "rule"

private fun reportPayload(payload: String?): JSONObject = runCatching { JSONObject(payload ?: "{}") }.getOrElse { JSONObject() }

/**
 * The report header names the rule, and the dispatcher only sees the action: this hands the
 * current name over for one run, so a renamed rule reports under its new name. Other kinds pass as is.
 */
fun ActionDef.withReportRuleName(ruleName: String): ActionDef {
    if (kind != TELEGRAM_REPORT_KIND) return this
    return copy(payload = reportPayload(payload).put(TELEGRAM_REPORT_RULE_KEY, ruleName).toString())
}

/**
 * The "telegram_report" step: the chosen items to the backup bot's chat. A report that met no network
 * waits in the outbox and the step counts as done; a bot that is gone fails the step with a reason
 * the user can read.
 */
suspend fun TelegramReporter.runReportAction(action: ActionDef, appStrings: AppStrings): DispatchResult {
    val payload = reportPayload(action.payload)
    val fields = ReportField.fromJson(payload.optJSONArray("fields"))
    val text = payload.optString("text")
    val ruleName = payload.optString(TELEGRAM_REPORT_RULE_KEY)
    return when (val result = sendRuleReport(ruleName, fields, text)) {
        TelegramReporter.SendResult.Sent -> DispatchResult(true)
        TelegramReporter.SendResult.Queued -> DispatchResult(true, appStrings.get(R.string.dispatch_tg_report_queued))
        TelegramReporter.SendResult.NotConnected -> DispatchResult(false, appStrings.get(R.string.dispatch_tg_report_no_bot))
        is TelegramReporter.SendResult.Failed -> DispatchResult(false, telegramErrorText(appStrings.context, result.key))
    }
}
