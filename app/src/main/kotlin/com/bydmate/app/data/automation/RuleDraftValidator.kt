package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
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
}

/** Reason a trigger draft failed validation (currently: voice-phrase collisions only). */
sealed class TriggerValidationError {
    object VoicePhraseEmpty : TriggerValidationError()
    object VoicePhraseBuiltin : TriggerValidationError()
    object VoicePhraseTaken : TriggerValidationError()
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
                "notification_silent", "notification_sound" -> {
                    val title = payloadJson(a.payload).optString("title")
                    if (title.isBlank()) return ActionValidationError.NotifTitleEmpty(n)
                }
                "app_launch" -> {
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
            }
        }
        return null
    }

    fun validateTriggers(triggers: List<TriggerDef>, editingId: Long, existingRules: List<RuleEntity>): TriggerValidationError? {
        val voiceTriggers = triggers.filter { it.kind == "voice" }
        if (voiceTriggers.isEmpty()) return null
        val otherPhrases = existingRules
            .filter { it.id != editingId }
            .flatMap { TriggerDef.listFromJson(it.triggers) }
            .filter { it.kind == "voice" && it.value.isNotBlank() }
            .map { VoicePhrase.normalize(it.value) }
            .toSet()
        for (t in voiceTriggers) {
            when (VoiceTriggerValidation.check(t.value, otherPhrases)) {
                VoiceTriggerValidation.Collision.Empty -> return TriggerValidationError.VoicePhraseEmpty
                VoiceTriggerValidation.Collision.BuiltIn -> return TriggerValidationError.VoicePhraseBuiltin
                VoiceTriggerValidation.Collision.OtherRule -> return TriggerValidationError.VoicePhraseTaken
                VoiceTriggerValidation.Collision.None -> {}
            }
        }
        return null
    }
}
