package com.bydmate.app.ui.automation

import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.SharedRule
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.util.appLocalizedContext
import org.json.JSONObject

/**
 * The lines of the import preview. Built from what runs (the kind and its parameters), never
 * from the display names in the file: a file can label a call with autodial «Уведомление».
 */
internal object RuleImportSummary {

    private val ACTION_KIND_LABELS = mapOf(
        "param" to R.string.automation_action_dplus_command,
        "notification" to R.string.automation_action_notification,
        "notification_silent" to R.string.automation_action_notification,
        "notification_sound" to R.string.automation_action_notification,
        "app_launch" to R.string.automation_action_app_launch,
        "call" to R.string.automation_action_call,
        "navigate" to R.string.automation_action_navigate,
        "url" to R.string.automation_action_url,
        "yandex_music" to R.string.automation_action_yandex_music,
        "youtube" to R.string.widget_button_icon_youtube,
        "go_home" to R.string.widget_button_icon_home,
        "delay" to R.string.automation_action_delay,
        "media_volume" to R.string.automation_action_media_volume,
        "sentry" to R.string.automation_action_sentry,
        "hotspot" to R.string.automation_action_hotspot,
        "cluster_projection" to R.string.automation_action_cluster_projection,
        "speak" to R.string.automation_action_speak,
        "agent_query" to R.string.automation_action_agent_query,
        "split_screen" to R.string.automation_action_split_screen,
        "split_screen_close" to R.string.automation_action_split_screen_close,
        "split_screen_toggle" to R.string.automation_action_split_screen_toggle,
    )

    /** Kinds whose decisive parameter is one text field of the payload. */
    private val ACTION_TEXT_FIELDS = mapOf(
        "url" to "url",
        "speak" to "text",
        "agent_query" to "prompt",
        "yandex_music" to "mode",
        "youtube" to "query",
    )

    private val TRIGGER_TYPE_LABELS = mapOf(
        "place_enter" to R.string.automation_trigger_type_place,
        "place_exit" to R.string.automation_trigger_type_place,
        "time_of_day" to R.string.automation_trigger_type_time_of_day,
        "time_range" to R.string.automation_trigger_type_schedule,
        "service_start" to R.string.automation_trigger_type_service_start,
        "network_available" to R.string.automation_trigger_type_internet,
        "button_press" to R.string.automation_trigger_type_button_press,
        "steering_key" to R.string.automation_trigger_type_steering_key,
        "voice" to R.string.automation_trigger_type_voice,
    )

    private val TIME_OF_DAY_LABELS = mapOf(
        "DAY" to R.string.automation_trigger_day,
        "NIGHT" to R.string.automation_trigger_night,
        "DAWN" to R.string.automation_trigger_dawn,
        "DUSK" to R.string.automation_trigger_dusk,
    )

    /** «Звонок: +375291234567, Звонить автоматически», «Команда автомобилю: Закрыть все окна (车窗关闭)». */
    fun action(action: ActionDef, context: Context): String {
        val lc = context.appLocalizedContext()
        if (action.kind == "toggle") return toggleDisplayName(context, action.payload.orEmpty())
        val label = ACTION_KIND_LABELS[action.kind]?.let { lc.getString(it) } ?: action.kind
        val detail = actionDetail(action, lc)
        return if (detail.isNullOrBlank()) label else "$label: $detail"
    }

    private fun actionDetail(action: ActionDef, lc: Context): String? {
        val json = payloadJson(action.payload)
        return when (action.kind) {
            "param" -> ACTION_COMMANDS.find { it.command == action.command }
                ?.let { "${lc.getString(it.nameRes)} (${action.command})" } ?: action.command
            "notification", "notification_silent", "notification_sound" ->
                listOf(json.optString("title"), json.optString("text")).filter { it.isNotBlank() }.joinToString(" / ")
            "app_launch" -> json.optString("packageName")
            "call" -> callDetail(json, lc)
            "navigate" -> "${json.optString("name")} ${json.optString("lat")}, ${json.optString("lon")}".trim()
            "delay", "media_volume" -> action.payload
            "sentry", "hotspot", "cluster_projection" ->
                lc.getString(if (action.payload == "1") R.string.auto_enum_on else R.string.auto_enum_off)
            "split_screen" -> "${json.optString("narrow")} / ${json.optString("wide")}"
            else -> ACTION_TEXT_FIELDS[action.kind]?.let { json.optString(it) }
        }
    }

    private fun callDetail(json: JSONObject, lc: Context): String {
        val phone = json.optString("phone").ifBlank { lc.getString(R.string.automation_import_contact_missing) }
        return if (json.optBoolean("autoDial", false)) "$phone, ${lc.getString(R.string.automation_call_auto_dial_label)}" else phone
    }

    /** The trigger type and its key value, e.g. «Скорость > 7 км/ч», «Место: Въезд в «Дача»». */
    fun trigger(trigger: TriggerDef, context: Context): String {
        val lc = context.appLocalizedContext()
        val typeRes = TRIGGER_TYPE_LABELS[trigger.kind] ?: return paramTrigger(trigger, lc)
        val value = triggerValue(trigger, lc)
        return if (value.isNullOrBlank()) lc.getString(typeRes) else "${lc.getString(typeRes)}: $value"
    }

    private fun triggerValue(trigger: TriggerDef, lc: Context): String? = when (trigger.kind) {
        "place_enter", "place_exit" -> placeValue(trigger, lc)
        "time_of_day" -> TIME_OF_DAY_LABELS[trigger.value]?.let { lc.getString(it) } ?: trigger.value
        "time_range" -> ScheduleSpec.fromJson(trigger.value)?.let { scheduleDisplayName(lc, it) } ?: trigger.value
        "button_press" -> trigger.value
        "steering_key" -> steeringKeyValue(trigger, lc)
        "voice" -> "«${trigger.value}»"
        else -> null
    }

    private fun placeValue(trigger: TriggerDef, lc: Context): String {
        val prefix = if (trigger.kind == "place_enter") {
            R.string.automation_trigger_place_enter_prefix
        } else {
            R.string.automation_trigger_place_exit_prefix
        }
        return "${lc.getString(prefix)} «${trigger.placeName.orEmpty()}»"
    }

    private fun steeringKeyValue(trigger: TriggerDef, lc: Context): String =
        trigger.value.toIntOrNull()?.takeIf { it > 0 }?.let { steeringKeyLabel(lc, it) }
            ?: lc.getString(R.string.automation_trigger_steering_key_unassigned)

    private fun paramTrigger(trigger: TriggerDef, lc: Context): String {
        val option = TRIGGER_PARAMS.find { it.param == trigger.param }
            ?: return "${trigger.param} ${trigger.operator} ${trigger.value}"
        val value = option.enumValues?.firstOrNull { it.first == trigger.value }?.let { lc.getString(it.second) }
            ?: listOf(trigger.value, option.unitRes?.let { lc.getString(it) }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
        return "${lc.getString(option.nameRes)} ${trigger.operator} $value"
    }

    /** The rule settings in one line: cooldown plus every switch that is on. */
    fun flags(rule: SharedRule, context: Context): String {
        val lc = context.appLocalizedContext()
        val on = listOfNotNull(
            lc.getString(R.string.automation_rule_cooldown, rule.cooldownSeconds),
            lc.getString(R.string.automation_setting_park_only).takeIf { rule.requirePark },
            lc.getString(R.string.automation_setting_confirm_before).takeIf { rule.confirmBeforeExecute },
            lc.getString(R.string.automation_setting_once_per_trip).takeIf { rule.fireOncePerTrip },
            lc.getString(R.string.automation_setting_play_sound).takeIf { rule.playSound },
        )
        return on.joinToString(" · ")
    }

    private fun payloadJson(payload: String?): JSONObject =
        runCatching { JSONObject(payload ?: "{}") }.getOrDefault(JSONObject())
}
