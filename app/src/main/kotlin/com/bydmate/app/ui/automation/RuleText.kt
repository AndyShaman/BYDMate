@file:Suppress("TooManyFunctions") // one small wording function per rule part

package com.bydmate.app.ui.automation

import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.ActionValidationError
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.OneShotTrigger
import com.bydmate.app.data.automation.RuleDraftValidator
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.TriggerNumber
import com.bydmate.app.data.automation.minuteToHHmm
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.RuleLogEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.telegram.TELEGRAM_REPORT_KIND
import com.bydmate.app.util.appLocalizedContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Everything the Automation tab says about a rule in words: the card phrase, the last result,
// the journal lines and what the editor still misses. Pure (a context only for strings), so the
// wording is tested in every language without a UI.

/** The card phrase «условия: действия»; the two halves are drawn in different colours. */
internal data class RulePhrase(val conditions: String, val actions: String) {
    fun text(context: Context): String =
        if (actions.isEmpty()) conditions
        else context.appLocalizedContext().getString(R.string.auto_ui_phrase_rule, conditions, actions)
}

internal fun rulePhrase(rule: RuleEntity, context: Context, now: Long = System.currentTimeMillis()): RulePhrase =
    rulePhrase(TriggerDef.listFromJson(rule.triggers), rule.triggerLogic, ActionDef.listFromJson(rule.actions), context, now)

@Suppress("LongParameterList") // the rule's parts plus the clock, so tests pin the day
internal fun rulePhrase(
    triggers: List<TriggerDef>,
    logic: String,
    actions: List<ActionDef>,
    context: Context,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): RulePhrase {
    val lc = context.appLocalizedContext()
    val conditions = triggers.map { conditionText(it, lc, now, zone) }
    var joined = conditions.firstOrNull().orEmpty()
    for (i in 1 until conditions.size) {
        // «30 сентября в 07:30, один раз, и …»: a part that has a comma of its own closes with one.
        val comma = ',' in conditions[i - 1] || '，' in conditions[i - 1]
        val format = when {
            logic == "OR" && comma -> R.string.auto_ui_phrase_or_comma
            logic == "OR" -> R.string.auto_ui_phrase_or
            comma -> R.string.auto_ui_phrase_and_comma
            else -> R.string.auto_ui_phrase_and
        }
        joined = lc.getString(format, joined, decapitalize(conditions[i], lc))
    }
    return RulePhrase(joined, listText(actions.map { decapitalize(actionText(it, lc), lc) }, lc))
}

private fun listText(parts: List<String>, lc: Context): String =
    parts.reduceOrNull { acc, s -> lc.getString(R.string.auto_ui_phrase_list, acc, s) }.orEmpty()

private fun Context.locale(): Locale = resources.configuration.locales[0]

/**
 * [s] with a small first letter, for the middle of a sentence: «Скорость» → «скорость»,
 * «Авто AC» → «авто AC». Acronyms («SOC», «ECO», «P») and names written in title case
 * («Driver Door») stay as they are.
 */
internal fun decapitalize(s: String, lc: Context): String {
    val words = s.split(' ')
    val first = words.first()
    if (first.isEmpty() || (first.length < 2 && words.size == 1)) return s
    if (!first[0].isUpperCase() || first.drop(1).any { it.isUpperCase() }) return s
    // Title case: every later word that is not an acronym starts with a capital.
    val later = words.drop(1).filter { w -> w.any { it.isLetter() } && !w.all { !it.isLetter() || it.isUpperCase() } }
    val titleCase = later.isNotEmpty() && later.all { it[0].isUpperCase() }
    return if (titleCase) s else s.replaceFirstChar { it.lowercase(lc.locale()) }
}

private fun capitalize(s: String, lc: Context): String = s.replaceFirstChar { it.titlecase(lc.locale()) }

// --- Conditions ---

internal fun operatorWord(op: String, lc: Context): String = when (op) {
    ">" -> lc.getString(R.string.auto_ui_op_gt)
    "<" -> lc.getString(R.string.auto_ui_op_lt)
    ">=" -> lc.getString(R.string.auto_ui_op_ge)
    "<=" -> lc.getString(R.string.auto_ui_op_le)
    "==" -> lc.getString(R.string.auto_ui_op_eq)
    "!=" -> lc.getString(R.string.auto_ui_op_ne)
    else -> op
}

/** A number with its unit: «5 км/ч», «20%»; a hint in brackets («(0=сухо)») is left out. */
private fun withUnit(value: String, unit: String): String = when {
    unit.isEmpty() || unit.startsWith("(") -> value
    unit.startsWith("%") -> value + unit
    else -> "$value $unit"
}

@Suppress("CyclomaticComplexMethod") // one branch per trigger kind
internal fun conditionText(t: TriggerDef, lc: Context, now: Long, zone: ZoneId): String = when (t.kind) {
    "param" -> paramConditionText(t, lc)
    "place_enter" -> lc.getString(R.string.auto_trig_place_enter, t.placeName ?: "?")
    "place_exit" -> lc.getString(R.string.auto_ui_phrase_place_exit, t.placeName ?: "?")
    "time_of_day" -> when (t.value.uppercase()) {
        "DAY" -> lc.getString(R.string.automation_trigger_day)
        "DAWN" -> lc.getString(R.string.automation_trigger_dawn)
        "DUSK" -> lc.getString(R.string.automation_trigger_dusk)
        else -> lc.getString(R.string.automation_trigger_night)
    }
    "time_range" -> ScheduleSpec.fromJson(t.value)?.let { scheduleText(it, lc) } ?: t.displayName
    "service_start" -> lc.getString(R.string.automation_trigger_service_start)
    "network_available" -> lc.getString(R.string.automation_trigger_internet)
    "button_press" -> lc.getString(R.string.auto_ui_phrase_button, t.value.toIntOrNull() ?: 1)
    AutomationEngine.TRIGGER_KIND_STEERING_KEY -> {
        val code = t.value.toIntOrNull() ?: 0
        if (code > 0) lc.getString(R.string.auto_ui_phrase_steering, steeringKeyLabel(lc, code))
        else lc.getString(R.string.auto_ui_phrase_steering_none)
    }
    "voice" -> if (t.value.isBlank()) lc.getString(R.string.automation_trigger_type_voice)
        else lc.getString(R.string.auto_ui_phrase_voice, t.value.trim())
    OneShotTrigger.KIND -> OneShotTrigger.momentMs(t.value, zone)
        ?.let { lc.getString(R.string.auto_ui_phrase_once, whenText(it, lc, now, zone)) } ?: t.displayName
    else -> t.displayName
}

private fun paramConditionText(t: TriggerDef, lc: Context): String {
    val option = TRIGGER_PARAMS.firstOrNull { it.param == t.param }
        ?: return t.displayName.ifBlank { lc.getString(R.string.auto_ui_pick_param) }
    val name = lc.getString(option.nameRes)
    if (option.enumValues != null) {
        val label = decapitalize(option.localizedEnumLabel(t.value, lc), lc)
        return when (t.operator) {
            "==" -> lc.getString(R.string.auto_ui_phrase_is, name, label)
            "!=" -> lc.getString(R.string.auto_ui_phrase_is_not, name, label)
            else -> lc.getString(R.string.auto_ui_phrase_compare, name, operatorWord(t.operator, lc), label)
        }
    }
    val unit = option.unitRes?.let { lc.getString(it) }.orEmpty()
    return lc.getString(R.string.auto_ui_phrase_compare, name, operatorWord(t.operator, lc), withUnit(t.value.trim(), unit))
}

private val WEEKDAYS = setOf(1, 2, 3, 4, 5)
private val WEEKEND = setOf(6, 7)
private val DAY_NAMES = listOf(
    R.string.automation_day_mon, R.string.automation_day_tue, R.string.automation_day_wed,
    R.string.automation_day_thu, R.string.automation_day_fri, R.string.automation_day_sat,
    R.string.automation_day_sun,
)

/** «В 08:00», «По будням с 08:00 до 10:00», «Пн, Ср в 07:30». */
internal fun scheduleText(spec: ScheduleSpec, lc: Context): String {
    val time = if (spec.isExact) lc.getString(R.string.auto_ui_phrase_at, minuteToHHmm(spec.fromMinute))
        else lc.getString(R.string.auto_ui_phrase_between, minuteToHHmm(spec.fromMinute), minuteToHHmm(spec.toMinute))
    val days = when {
        spec.days.isEmpty() || spec.days.size == 7 -> return time
        spec.days == WEEKDAYS -> lc.getString(R.string.auto_ui_days_weekdays)
        spec.days == WEEKEND -> lc.getString(R.string.auto_ui_days_weekends)
        else -> listText(spec.days.sorted().map { lc.getString(DAY_NAMES[it - 1]) }, lc)
    }
    return lc.getString(R.string.auto_ui_phrase_days, days, decapitalize(time, lc))
}

/** «Сегодня в 08:12», «Вчера в 18:40», «Завтра в 07:30», «30 сентября в 07:30» (with the year when not this one). */
internal fun whenText(ms: Long, lc: Context, now: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    val date: LocalDate = at.toLocalDate()
    return when (date) {
        today -> lc.getString(R.string.auto_ui_when_today, time)
        today.minusDays(1) -> lc.getString(R.string.auto_ui_when_yesterday, time)
        today.plusDays(1) -> lc.getString(R.string.auto_ui_when_tomorrow, time)
        else -> {
            val pattern = if (date.year == today.year) R.string.auto_ui_date_pattern else R.string.auto_ui_date_year_pattern
            val day = date.format(DateTimeFormatter.ofPattern(lc.getString(pattern), lc.locale()))
            lc.getString(R.string.auto_ui_when_date, day, time)
        }
    }
}

/** The day of [now] in [zone], as a number: what the relative dates of [whenText] hang on. */
internal fun epochDay(now: Long, zone: ZoneId): Long = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()

/** Milliseconds from [now] to the next midnight in [zone]. */
internal fun msUntilNextDay(now: Long, zone: ZoneId): Long {
    val next = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return next - now
}

// --- Actions ---

private const val QUOTE_MAX = 40

private fun quoted(nameRes: Int, detail: String, lc: Context): String {
    val name = lc.getString(nameRes)
    val d = detail.trim().replace('\n', ' ')
    if (d.isEmpty()) return name
    val cut = if (d.length > QUOTE_MAX) d.take(QUOTE_MAX - 1).trimEnd() + "…" else d
    return lc.getString(R.string.auto_ui_act_quoted, name, cut)
}

/** «0,5 с», «30 с», «1 мин». */
internal fun durationText(ms: Long, lc: Context): String {
    if (ms >= 60_000 && ms % 60_000 == 0L) return lc.getString(R.string.auto_ui_minutes, (ms / 60_000).toString())
    val fmt = NumberFormat.getNumberInstance(lc.locale()).apply { maximumFractionDigits = 1 }
    return lc.getString(R.string.auto_ui_seconds, fmt.format(ms / 1000.0))
}

private fun onOffText(nameRes: Int, onRes: Int, offRes: Int, payload: String?, lc: Context): String {
    val name = lc.getString(nameRes)
    val state = when (payload) {
        "1" -> lc.getString(onRes)
        "0" -> lc.getString(offRes)
        else -> return name
    }
    return lc.getString(R.string.auto_ui_act_with, name, decapitalize(state, lc))
}

@Suppress("CyclomaticComplexMethod") // one branch per action kind
internal fun actionText(a: ActionDef, lc: Context): String = when (a.kind) {
    "param" -> ACTION_COMMANDS.firstOrNull { it.toggleTarget == null && it.command == a.command }
        ?.let { lc.getString(it.nameRes) } ?: a.displayName
    "toggle" -> a.payload?.let { ActionDispatcher.toggleTargetNameRes(it) }
        ?.let { lc.getString(R.string.auto_ui_act_toggle, lc.getString(it)) } ?: a.displayName
    "delay" -> lc.getString(R.string.auto_ui_act_wait, durationText(a.payload?.toLongOrNull() ?: 1000L, lc))
    "notification", "notification_silent", "notification_sound" ->
        quoted(R.string.auto_act_notification, a.notificationTitle(), lc)
    "speak" -> quoted(R.string.automation_action_speak, a.speakText(), lc)
    "agent_query" -> quoted(R.string.automation_action_agent_query, a.agentPrompt(), lc)
    "app_launch" -> quoted(R.string.automation_action_app_launch, a.appLaunchLabel(), lc)
    "call" -> quoted(R.string.automation_action_call, a.callName().ifBlank { a.callPhone() }, lc)
    "navigate" -> quoted(R.string.automation_action_navigate, a.navigateName(), lc)
    "url" -> quoted(R.string.automation_action_url, a.urlString(), lc)
    TELEGRAM_REPORT_KIND -> lc.getString(R.string.automation_action_tg_report)
    "sentry" -> onOffText(
        R.string.automation_action_sentry, R.string.automation_action_sentry_on, R.string.automation_action_sentry_off, a.payload, lc,
    )
    "hotspot" -> onOffText(
        R.string.automation_action_hotspot, R.string.automation_action_hotspot_on, R.string.automation_action_hotspot_off, a.payload, lc,
    )
    "cluster_projection" -> onOffText(
        R.string.automation_action_cluster_projection, R.string.automation_action_cluster_projection_on,
        R.string.automation_action_cluster_projection_off, a.payload, lc,
    )
    "media_volume" -> a.payload?.toIntOrNull()
        ?.let { lc.getString(R.string.auto_ui_act_volume, lc.getString(R.string.automation_action_media_volume), it) }
        ?: a.displayName
    else -> a.displayName
}

// --- Last result and journal ---

internal enum class RuleStatusKind { OK, CANCELLED, SKIPPED, ERROR, NEVER, PLANNED }

internal data class RuleStatus(val kind: RuleStatusKind, val text: String)

/** What a journal entry was: a run (by its success), or one of the RuleJournal non-runs. */
private fun entryResult(log: RuleLogEntity): String? = try {
    JSONArray(log.actionsResult).optJSONObject(0)?.optString("result")?.ifEmpty { null }
} catch (_: Exception) {
    null
}

private fun entryKind(log: RuleLogEntity): RuleStatusKind = when (entryResult(log)) {
    "cancelled", "timeout" -> RuleStatusKind.CANCELLED
    "skipped", "expired" -> RuleStatusKind.SKIPPED
    else -> if (log.success) RuleStatusKind.OK else RuleStatusKind.ERROR
}

/**
 * The line under the rule name: a one-shot rule still waiting says when it runs; otherwise the
 * newest journal entry [last] with its time, or «Ещё не срабатывало».
 */
internal fun ruleStatus(
    rule: RuleEntity,
    last: RuleLogEntity?,
    context: Context,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): RuleStatus {
    val lc = context.appLocalizedContext()
    val oneShot = TriggerDef.listFromJson(rule.triggers).firstOrNull { it.kind == OneShotTrigger.KIND }
    if (rule.enabled && oneShot != null) {
        val state = OneShotTrigger.state(oneShot.value, now, zone)
        val at = OneShotTrigger.momentMs(oneShot.value, zone)
        if (at != null && (state == OneShotTrigger.State.PENDING || state == OneShotTrigger.State.DUE)) {
            return RuleStatus(RuleStatusKind.PLANNED, lc.getString(R.string.auto_ui_status_planned, decapitalize(whenText(at, lc, now, zone), lc)))
        }
    }
    if (last == null) return RuleStatus(RuleStatusKind.NEVER, lc.getString(R.string.auto_ui_status_never))
    val kind = entryKind(last)
    val format = when (kind) {
        RuleStatusKind.OK -> R.string.auto_ui_status_ok
        RuleStatusKind.CANCELLED -> R.string.auto_ui_status_cancelled
        RuleStatusKind.SKIPPED -> R.string.auto_ui_status_skipped
        else -> R.string.auto_ui_status_error
    }
    return RuleStatus(kind, lc.getString(format, decapitalize(whenText(last.triggeredAt, lc, now, zone), lc)))
}

/** One journal entry as the journal shows it: verdict, time, what ran, why. */
internal data class JournalLine(
    val kind: RuleStatusKind,
    val status: String,
    val time: String,
    val what: String,
    val why: String?,
)

/** Parameter-free names of the kinds whose line reads the payload: an entry older than the recorded payload. */
private val PAYLOAD_KIND_NAMES = mapOf(
    "toggle" to R.string.automation_action_toggle,
    "delay" to R.string.automation_action_delay,
    "notification" to R.string.auto_act_notification,
    "notification_silent" to R.string.auto_act_notification,
    "notification_sound" to R.string.auto_act_notification,
    "speak" to R.string.automation_action_speak,
    "agent_query" to R.string.automation_action_agent_query,
    "app_launch" to R.string.automation_action_app_launch,
    "call" to R.string.automation_action_call,
    "navigate" to R.string.automation_action_navigate,
    "url" to R.string.automation_action_url,
    "sentry" to R.string.automation_action_sentry,
    "hotspot" to R.string.automation_action_hotspot,
    "cluster_projection" to R.string.automation_action_cluster_projection,
    "media_volume" to R.string.automation_action_media_volume,
)

/**
 * An action as the journal recorded it. An entry written before the journal kept the payload
 * shows the name it was recorded with, or the bare kind name: never a parameter it did not record.
 */
private fun recordedActionText(o: JSONObject, lc: Context): String {
    val kind = o.optString("kind", "param")
    val displayName = o.optString("displayName")
    val bareName = PAYLOAD_KIND_NAMES[kind]
    if (!o.has("payload") && bareName != null) return displayName.ifBlank { lc.getString(bareName) }
    val payload = if (o.isNull("payload")) null else o.optString("payload")
    return actionText(ActionDef(o.optString("command"), displayName, kind, payload), lc)
}

private fun recordedList(actions: List<JSONObject>, lc: Context): String =
    listText(actions.mapIndexed { i, o -> recordedActionText(o, lc).let { if (i == 0) it else decapitalize(it, lc) } }, lc)

/**
 * One journal entry in words, only from what the entry itself recorded: the rule may have been
 * edited or deleted since.
 */
internal fun journalLine(
    log: RuleLogEntity,
    context: Context,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): JournalLine {
    val lc = context.appLocalizedContext()
    val snapshot = try { JSONObject(log.triggersSnapshot) } catch (_: Exception) { JSONObject() }
    val steps = try { JSONArray(log.actionsResult) } catch (_: Exception) { JSONArray() }
    val oneShot = snapshot.has(OneShotTrigger.KIND)
    val time = whenText(log.triggeredAt, lc, now, zone)
    val result = entryResult(log)
    if (result != null) {
        val entry = steps.optJSONObject(0)
        val reason = entry?.optString("reason")?.ifEmpty { null }
        val (kind, status) = when (result) {
            "cancelled", "timeout" -> RuleStatusKind.CANCELLED to R.string.auto_ui_journal_cancelled
            "expired" -> RuleStatusKind.SKIPPED to R.string.auto_ui_journal_expired
            else -> RuleStatusKind.SKIPPED to R.string.auto_ui_journal_skipped
        }
        // An entry written before the journal kept the actions shows none.
        val recorded = entry?.optJSONArray("actions")
        val what = if (snapshot.optBoolean(TEST_RUN_KEY)) lc.getString(R.string.automation_test_run_button)
            else recordedList((0 until (recorded?.length() ?: 0)).mapNotNull { recorded?.optJSONObject(it) }, lc)
        return JournalLine(kind, lc.getString(status), time, what, reason)
    }
    val stepList = (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }
    if (log.success) {
        val what = recordedList(stepList, lc)
        val why = if (oneShot) lc.getString(R.string.auto_ui_journal_one_shot) else snapshotText(snapshot, lc)
        val status = if (oneShot) R.string.auto_ui_journal_done_off else R.string.auto_ui_journal_done
        return JournalLine(RuleStatusKind.OK, lc.getString(status), time, what, why)
    }
    val failed = stepList.firstOrNull { !it.optBoolean("success", false) }
    val what = failed?.let { lc.getString(R.string.auto_ui_journal_step_failed, recordedActionText(it, lc)) }
        ?: recordedList(stepList, lc)
    val why = failed?.optString("reason")?.ifEmpty { null }
    return JournalLine(RuleStatusKind.ERROR, lc.getString(R.string.auto_ui_journal_error), time, capitalize(what, lc), why)
}

/** Snapshot key of a journal entry written by «Тестовый запуск». */
internal const val TEST_RUN_KEY = "test_run"

/** The car's values a rule fired on: «Передача P, скорость 0 км/ч»; null when none were stored. */
private fun snapshotText(snapshot: JSONObject, lc: Context): String? {
    // In the order the rule stored them, which is the order of its conditions.
    val parts = snapshot.keys().asSequence().toList().mapNotNull { key ->
        val option = TRIGGER_PARAMS.firstOrNull { it.param == key } ?: return@mapNotNull null
        if (snapshot.isNull(option.param)) return@mapNotNull null
        val raw = snapshot.opt(option.param)
        val value = (raw as? Number)?.toDouble()?.let { d ->
            if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString()
            else NumberFormat.getNumberInstance(lc.locale()).apply { maximumFractionDigits = 2 }.format(d)
        } ?: raw.toString()
        val name = lc.getString(option.nameRes)
        val shown = if (option.enumValues != null) decapitalize(option.localizedEnumLabel(value, lc), lc)
            else withUnit(value, option.unitRes?.let { lc.getString(it) }.orEmpty())
        lc.getString(R.string.auto_ui_phrase_is, name, shown)
    }
    if (parts.isEmpty()) return null
    return listText(parts.mapIndexed { i, s -> if (i == 0) s else decapitalize(s, lc) }, lc)
}

// --- Editor: what is missing before «Сохранить» ---

/** One thing the draft still needs; [condition] and [action] are 1-based row numbers. */
internal sealed interface Missing {
    data object Name : Missing
    data object NoConditions : Missing
    data class Param(val condition: Int) : Missing
    data class Value(val condition: Int) : Missing
    data class Operator(val condition: Int) : Missing
    data class Number(val condition: Int) : Missing
    data class Key(val condition: Int) : Missing
    data object NoActions : Missing
    data class Action(val action: Int, val message: String) : Missing
}

internal fun missingParts(e: EditingRule, context: Context): List<Missing> {
    val lc = context.appLocalizedContext()
    val out = mutableListOf<Missing>()
    if (e.name.isBlank()) out += Missing.Name
    if (e.triggers.isEmpty()) out += Missing.NoConditions
    e.triggers.forEachIndexed { i, t ->
        val n = i + 1
        when (t.kind) {
            "param" -> {
                val option = TRIGGER_PARAMS.firstOrNull { it.param == t.param }
                when {
                    option == null -> out += Missing.Param(n)
                    option.enumValues != null -> {
                        if (hasListOperatorMissing(t)) out += Missing.Operator(n)
                        if (t.value.isBlank()) out += Missing.Value(n)
                    }
                    TriggerNumber.parse(t.value) == null -> out += Missing.Number(n)
                }
            }
            AutomationEngine.TRIGGER_KIND_STEERING_KEY -> if ((t.value.toIntOrNull() ?: 0) <= 0) out += Missing.Key(n)
        }
    }
    if (e.actions.isEmpty()) out += Missing.NoActions
    RuleDraftValidator.validateActions(e.actions)?.let { out += it.toMissing(lc) }
    return out
}

internal fun Missing.text(lc: Context): String = when (this) {
    Missing.Name -> lc.getString(R.string.auto_ui_miss_name)
    Missing.NoConditions -> lc.getString(R.string.auto_ui_miss_conditions)
    is Missing.Param -> lc.getString(R.string.auto_ui_miss_param, condition)
    is Missing.Value -> lc.getString(R.string.auto_ui_miss_value, condition)
    is Missing.Operator -> lc.getString(R.string.auto_ui_miss_operator, condition)
    is Missing.Number -> lc.getString(R.string.auto_ui_miss_number, condition)
    is Missing.Key -> lc.getString(R.string.auto_ui_miss_key, condition)
    Missing.NoActions -> lc.getString(R.string.auto_ui_miss_actions)
    is Missing.Action -> message
}

/** The line beside «Сохранить»: one thing, two joined, or the first and «ещё N»; null when nothing is missing. */
internal fun saveReason(missing: List<Missing>, context: Context): String? {
    val lc = context.appLocalizedContext()
    return when (missing.size) {
        0 -> null
        1 -> missing[0].text(lc)
        2 -> lc.getString(R.string.auto_ui_miss_two, missing[0].text(lc), decapitalize(missing[1].text(lc), lc))
        else -> lc.getString(R.string.auto_ui_miss_more, missing[0].text(lc), missing.size - 1)
    }
}

/** Whether the draft differs from how the editor opened; the save-in-flight flag does not count. */
internal fun hasUnsavedChanges(original: EditingRule, current: EditingRule): Boolean =
    original.copy(saving = false) != current.copy(saving = false)

/**
 * The trigger kind that starts the rule on its own, when the rule has other conditions too: a
 * widget button, a steering key or a voice phrase fire the actions at once and skip the rest.
 */
internal fun eventTriggerWithOthers(triggers: List<TriggerDef>): String? {
    if (triggers.size < 2) return null
    return triggers.firstOrNull { it.kind in EVENT_KINDS }?.kind
}

private val EVENT_KINDS = setOf("button_press", AutomationEngine.TRIGGER_KIND_STEERING_KEY, "voice")

/** The operators a parameter offers: «равно / не равно» for a list, all six for a number. */
internal fun operatorsFor(option: TriggerParamOption?): List<String> =
    if (option?.enumValues != null) listOf("==", "!=") else OPERATORS

/**
 * True for a list condition saved with an operator the editor no longer offers (an older build
 * allowed «Передача > P»): it opens with neither button chosen and must not be saved as it is.
 */
internal fun hasListOperatorMissing(t: TriggerDef): Boolean {
    if (t.kind != "param") return false
    val option = TRIGGER_PARAMS.firstOrNull { it.param == t.param } ?: return false
    return option.enumValues != null && t.operator !in operatorsFor(option)
}

/** [t] switched to [option]: a new parameter starts with a fresh operator and an empty value. */
internal fun withParam(t: TriggerDef, option: TriggerParamOption, context: Context): TriggerDef = t.copy(
    param = option.param,
    chineseName = option.chineseName,
    operator = if (option.enumValues != null) "==" else ">",
    value = "",
    displayName = option.localizedName(context),
)

/** A new param condition: nothing picked yet. */
internal fun newParamTrigger(): TriggerDef = TriggerDef(param = "", chineseName = "", operator = "==", value = "", displayName = "")

/** The first action the validator refuses, with its line. */
@Suppress("CyclomaticComplexMethod") // one branch per error type
internal fun ActionValidationError.toMissing(lc: Context): Missing.Action = when (this) {
    is ActionValidationError.CommandMissing -> Missing.Action(index, lc.getString(R.string.auto_msg_command_missing, index))
    is ActionValidationError.NotifTitleEmpty -> Missing.Action(index, lc.getString(R.string.auto_msg_notif_title_empty, index))
    is ActionValidationError.AppNotSelected -> Missing.Action(index, lc.getString(R.string.auto_msg_app_not_selected, index))
    is ActionValidationError.PhoneInvalid -> Missing.Action(index, lc.getString(R.string.auto_msg_phone_invalid, index))
    is ActionValidationError.NavDestMissing -> Missing.Action(index, lc.getString(R.string.auto_msg_nav_dest_missing, index))
    is ActionValidationError.UrlEmpty -> Missing.Action(index, lc.getString(R.string.auto_msg_url_empty, index))
    is ActionValidationError.UrlNoScheme -> Missing.Action(index, lc.getString(R.string.auto_msg_url_no_scheme, index))
    is ActionValidationError.YandexMusicModeMissing -> Missing.Action(index, lc.getString(R.string.auto_msg_ymusic_mode_missing, index))
    is ActionValidationError.MediaVolumeMissing -> Missing.Action(index, lc.getString(R.string.auto_msg_media_volume_missing, index))
    is ActionValidationError.SentryInvalid -> Missing.Action(index, lc.getString(R.string.auto_msg_sentry_invalid, index))
    is ActionValidationError.HotspotInvalid -> Missing.Action(index, lc.getString(R.string.auto_msg_hotspot_invalid, index))
    is ActionValidationError.SpeakTextEmpty -> Missing.Action(index, lc.getString(R.string.auto_msg_speak_text_empty, index))
    is ActionValidationError.AgentQueryPromptEmpty -> Missing.Action(index, lc.getString(R.string.auto_msg_agent_query_prompt_empty, index))
    is ActionValidationError.SplitScreenNarrowEmpty -> Missing.Action(index, lc.getString(R.string.auto_msg_split_narrow_empty, index))
    is ActionValidationError.SplitScreenWideEmpty -> Missing.Action(index, lc.getString(R.string.auto_msg_split_wide_empty, index))
    is ActionValidationError.SplitScreenSamePackage -> Missing.Action(index, lc.getString(R.string.auto_msg_split_same_package, index))
    is ActionValidationError.SplitScreenInvalidSide -> Missing.Action(index, lc.getString(R.string.auto_msg_split_invalid_side, index))
    is ActionValidationError.TelegramReportEmpty -> Missing.Action(index, lc.getString(R.string.auto_msg_tg_report_empty, index))
}
