package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.telegram.ReportField
import com.bydmate.app.voice.VoicePhrase
import com.bydmate.app.voice.VoiceTriggerValidation
import org.json.JSONObject

/** Reason an action draft failed validation, with the 1-based index of the offending action. */
sealed class ActionValidationError {
    data class CommandMissing(val index: Int) : ActionValidationError()
    data class NotifTitleEmpty(val index: Int) : ActionValidationError()
    data class AppNotSelected(val index: Int) : ActionValidationError()
    data class PhoneInvalid(val index: Int) : ActionValidationError()
    data class NavDestMissing(val index: Int) : ActionValidationError()
    data class UrlEmpty(val index: Int) : ActionValidationError()
    data class UrlNoScheme(val index: Int) : ActionValidationError()
    data class YandexMusicModeMissing(val index: Int) : ActionValidationError()
    data class MediaVolumeMissing(val index: Int) : ActionValidationError()
    data class SentryInvalid(val index: Int) : ActionValidationError()
    data class HotspotInvalid(val index: Int) : ActionValidationError()
    data class MediaKeyInvalid(val index: Int) : ActionValidationError()
    data class SpeakTextEmpty(val index: Int) : ActionValidationError()
    data class AgentQueryPromptEmpty(val index: Int) : ActionValidationError()
    data class SplitScreenNarrowEmpty(val index: Int) : ActionValidationError()
    data class SplitScreenWideEmpty(val index: Int) : ActionValidationError()
    data class SplitScreenSamePackage(val index: Int) : ActionValidationError()
    data class SplitScreenInvalidSide(val index: Int) : ActionValidationError()
    /** A Telegram report with no item checked and no text of its own. */
    data class TelegramReportEmpty(val index: Int) : ActionValidationError()
}

/** Reason a trigger draft failed validation. */
sealed class TriggerValidationError {
    /** A condition on [param] whose value is empty or not a number («12,5» is one). */
    data class ValueNotNumber(val param: String) : TriggerValidationError()
    /** A steering-wheel key trigger with no key picked yet (code 0). */
    object SteeringKeyUnassigned : TriggerValidationError()
    /** A one-shot moment that is not a date and time. */
    object OneShotInvalid : TriggerValidationError()
    /** A one-shot rule may add conditions only with AND, never OR. */
    object OneShotWithOr : TriggerValidationError()
    /** A one-shot rule may add only param conditions: no events, places or schedules. */
    object OneShotWithEvent : TriggerValidationError()
    /** More than one one-shot moment in a rule. */
    object OneShotTwice : TriggerValidationError()
    object VoicePhraseEmpty : TriggerValidationError()
    /** The phrase is the user's own phrase for the built-in command [command]. */
    data class VoicePhraseBuiltin(val command: String) : TriggerValidationError()
    /** The phrase already triggers the automation [rule]. */
    data class VoicePhraseTaken(val rule: String) : TriggerValidationError()
}

/**
 * Rule-draft business validation shared by the automation editor (AutomationViewModel,
 * which maps these results to localized strings) and the voice agent's create_automation
 * tool (which maps them to fixed Russian strings). Pure Kotlin — no Android Context —
 * so both callers get identical validation without either depending on the other.
 */
object RuleDraftValidator {

    // payload is a JSON string with kind-specific fields; on any parse failure treat
    // as an empty object, matching the try/catch-to-"" behaviour of the original
    // ActionDef payload helpers this replaces.
    private fun payloadJson(payload: String?): JSONObject =
        try { JSONObject(payload ?: "{}") } catch (e: Exception) { JSONObject() }

    fun validateActions(actions: List<ActionDef>): ActionValidationError? {
        actions.forEachIndexed { idx, a ->
            val n = idx + 1
            when (a.kind) {
                "param" -> {
                    if (a.command.isBlank()) return ActionValidationError.CommandMissing(n)
                }
                "notification", "notification_silent", "notification_sound" -> {
                    val title = payloadJson(a.payload).optString("title")
                    if (title.isBlank()) return ActionValidationError.NotifTitleEmpty(n)
                }
                "app_launch", "app_close" -> {
                    val pkg = payloadJson(a.payload).optString("packageName")
                    if (pkg.isBlank()) return ActionValidationError.AppNotSelected(n)
                }
                "call" -> {
                    val phone = payloadJson(a.payload).optString("phone").trim()
                    if (phone.length !in 5..20) return ActionValidationError.PhoneInvalid(n)
                }
                "navigate" -> {
                    val json = payloadJson(a.payload)
                    val lat = json.optDouble("lat", Double.NaN).let { if (it.isNaN()) null else it }
                    val lon = json.optDouble("lon", Double.NaN).let { if (it.isNaN()) null else it }
                    if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) {
                        return ActionValidationError.NavDestMissing(n)
                    }
                }
                "url" -> {
                    val u = payloadJson(a.payload).optString("url").trim()
                    if (u.isEmpty()) return ActionValidationError.UrlEmpty(n)
                    // Allow any scheme: http(s)://, yandexmusic://, tel:, intent://, geo:, etc.
                    if (!u.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:.+"))) {
                        return ActionValidationError.UrlNoScheme(n)
                    }
                }
                "yandex_music" -> {
                    val mode = payloadJson(a.payload).optString("mode")
                    if (mode.isBlank()) return ActionValidationError.YandexMusicModeMissing(n)
                }
                "media_volume" -> {
                    // Mirrors ActionDispatcher.resolveVolumeOp exactly: "mute"/"unmute", or any
                    // parseable int (unsigned = absolute level, signed "+N"/"-N" = relative step).
                    val payload = a.payload
                    val valid = payload == "mute" || payload == "unmute" || payload?.toIntOrNull() != null
                    if (!valid) return ActionValidationError.MediaVolumeMissing(n)
                }
                "sentry" -> {
                    if (a.payload !in listOf("0", "1")) return ActionValidationError.SentryInvalid(n)
                }
                "hotspot" -> {
                    if (a.payload !in listOf("0", "1")) return ActionValidationError.HotspotInvalid(n)
                }
                "media_key" -> {
                    if (ActionDispatcher.mediaKeyCode(a.payload) == null) return ActionValidationError.MediaKeyInvalid(n)
                }
                "speak" -> {
                    if (payloadJson(a.payload).optString("text").isBlank()) {
                        return ActionValidationError.SpeakTextEmpty(n)
                    }
                }
                "agent_query" -> {
                    if (payloadJson(a.payload).optString("prompt").isBlank()) {
                        return ActionValidationError.AgentQueryPromptEmpty(n)
                    }
                }
                "split_screen" -> {
                    val json = payloadJson(a.payload)
                    val narrow = json.optString("narrow")
                    if (narrow.isBlank()) return ActionValidationError.SplitScreenNarrowEmpty(n)
                    val wide = json.optString("wide")
                    if (wide.isBlank()) return ActionValidationError.SplitScreenWideEmpty(n)
                    if (narrow == wide) return ActionValidationError.SplitScreenSamePackage(n)
                    val side = json.optString("side")
                    if (side !in listOf("left", "right")) return ActionValidationError.SplitScreenInvalidSide(n)
                }
                "telegram_report" -> {
                    val json = payloadJson(a.payload)
                    val fields = ReportField.fromJson(json.optJSONArray("fields"))
                    if (fields.isEmpty() && json.optString("text").isBlank()) {
                        return ActionValidationError.TelegramReportEmpty(n)
                    }
                }
            }
        }
        return null
    }

    /**
     * [userCommandPhrases]: normalized user phrases of built-in commands → command name.
     * [triggerLogic]: the rule's AND / OR, checked against a one-shot moment.
     * [TriggerValidationError.SteeringKeyUnassigned] comes last: an import accepts it (the key
     * codes differ per car, the driver picks the key after the import) and loses no other check.
     */
    fun validateTriggers(
        triggers: List<TriggerDef>,
        editingId: Long,
        existingRules: List<RuleEntity>,
        userCommandPhrases: Map<String, String> = emptyMap(),
        triggerLogic: String = "AND",
    ): TriggerValidationError? {
        triggers.firstOrNull { it.kind == "param" && TriggerNumber.parse(it.value) == null }
            ?.let { return TriggerValidationError.ValueNotNumber(it.param) }
        validateOneShot(triggers, triggerLogic)?.let { return it }
        validateVoice(triggers, editingId, existingRules, userCommandPhrases)?.let { return it }
        val unassignedKey = triggers.any {
            it.kind == AutomationEngine.TRIGGER_KIND_STEERING_KEY && (it.value.toIntOrNull() ?: 0) <= 0
        }
        return if (unassignedKey) TriggerValidationError.SteeringKeyUnassigned else null
    }

    private fun validateVoice(
        triggers: List<TriggerDef>,
        editingId: Long,
        existingRules: List<RuleEntity>,
        userCommandPhrases: Map<String, String>,
    ): TriggerValidationError? {
        val voiceTriggers = triggers.filter { it.kind == "voice" }
        if (voiceTriggers.isEmpty()) return null
        val otherPhrases = buildMap {
            for (rule in existingRules) {
                if (rule.id == editingId) continue
                TriggerDef.listFromJson(rule.triggers)
                    .filter { it.kind == "voice" && it.value.isNotBlank() }
                    .forEach { putIfAbsent(VoicePhrase.normalize(it.value), rule.name) }
            }
        }
        for (t in voiceTriggers) {
            when (val c = VoiceTriggerValidation.check(t.value, otherPhrases, userCommandPhrases)) {
                VoiceTriggerValidation.Collision.Empty -> return TriggerValidationError.VoicePhraseEmpty
                is VoiceTriggerValidation.Collision.UserCommandPhrase ->
                    return TriggerValidationError.VoicePhraseBuiltin(c.command)
                is VoiceTriggerValidation.Collision.OtherRule -> return TriggerValidationError.VoicePhraseTaken(c.rule)
                VoiceTriggerValidation.Collision.None -> {}
            }
        }
        return null
    }

    private fun validateOneShot(triggers: List<TriggerDef>, logic: String): TriggerValidationError? {
        val oneShots = triggers.filter { it.kind == OneShotTrigger.KIND }
        return when {
            oneShots.isEmpty() -> null
            oneShots.size > 1 -> TriggerValidationError.OneShotTwice
            OneShotTrigger.momentMs(oneShots[0].value) == null -> TriggerValidationError.OneShotInvalid
            triggers.any { it.kind != OneShotTrigger.KIND && it.kind != "param" } -> TriggerValidationError.OneShotWithEvent
            logic == "OR" && triggers.size > 1 -> TriggerValidationError.OneShotWithOr
            else -> null
        }
    }
}
