package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleDraftValidatorTest {

    // --- validateActions ---

    @Test fun empty_actions_list_is_valid() {
        assertNull(RuleDraftValidator.validateActions(emptyList()))
    }

    @Test fun param_action_blank_command_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "param")))
        assertEquals(ActionValidationError.CommandMissing(1), err)
    }

    @Test fun param_action_with_command_is_valid() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "车窗关闭", displayName = "x", kind = "param"))))
    }

    @Test fun notification_action_blank_title_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "notification_silent",
                payload = """{"title":"","text":""}""")))
        assertEquals(ActionValidationError.NotifTitleEmpty(1), err)
    }

    @Test fun app_launch_action_blank_package_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "app_launch",
                payload = """{"packageName":"","appLabel":""}""")))
        assertEquals(ActionValidationError.AppNotSelected(1), err)
    }

    @Test fun call_action_phone_too_short_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "call", payload = """{"phone":"123"}""")))
        assertEquals(ActionValidationError.PhoneInvalid(1), err)
    }

    @Test fun call_action_valid_phone_is_valid() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "call", payload = """{"phone":"+79161234567"}"""))))
    }

    @Test fun navigate_action_both_zero_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "navigate", payload = """{"lat":0,"lon":0}""")))
        assertEquals(ActionValidationError.NavDestMissing(1), err)
    }

    @Test fun navigate_action_valid_coords_is_valid() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "navigate", payload = """{"lat":55.7,"lon":37.6}"""))))
    }

    @Test fun url_action_empty_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "url", payload = """{"url":""}""")))
        assertEquals(ActionValidationError.UrlEmpty(1), err)
    }

    @Test fun url_action_no_scheme_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "url", payload = """{"url":"example.com"}""")))
        assertEquals(ActionValidationError.UrlNoScheme(1), err)
    }

    @Test fun url_action_with_scheme_is_valid() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "url", payload = """{"url":"https://example.com"}"""))))
    }

    @Test fun yandex_music_action_blank_mode_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "", displayName = "x", kind = "yandex_music", payload = """{"mode":""}""")))
        assertEquals(ActionValidationError.YandexMusicModeMissing(1), err)
    }

    // Signed "-N" is a relative volume step (ActionDispatcher.resolveVolumeOp), not an
    // absolute negative level, so it is valid — was previously (incorrectly) rejected.
    @Test fun media_volume_action_signed_negative_is_valid_relative_step() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "media_volume", displayName = "x", kind = "media_volume", payload = "-1"))))
    }

    @Test fun media_volume_action_valid_level_is_valid() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "media_volume", displayName = "x", kind = "media_volume", payload = "5"))))
    }

    @Test fun media_volume_action_mute_is_valid() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "media_volume", displayName = "x", kind = "media_volume", payload = "mute"))))
    }

    @Test fun media_volume_action_unmute_is_valid() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "media_volume", displayName = "x", kind = "media_volume", payload = "unmute"))))
    }

    @Test fun media_volume_action_garbage_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "media_volume", displayName = "x", kind = "media_volume", payload = "loud")))
        assertEquals(ActionValidationError.MediaVolumeMissing(1), err)
    }

    @Test fun sentry_action_invalid_payload_is_invalid() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "sentry", displayName = "x", kind = "sentry", payload = "2")))
        assertEquals(ActionValidationError.SentryInvalid(1), err)
    }

    @Test fun sentry_action_valid_payload_is_valid() {
        assertNull(RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "sentry", displayName = "x", kind = "sentry", payload = "1"))))
    }

    @Test fun second_action_failing_reports_index_2() {
        val err = RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "车窗关闭", displayName = "ok", kind = "param"),
            ActionDef(command = "", displayName = "bad", kind = "param"),
        ))
        assertEquals(ActionValidationError.CommandMissing(2), err)
    }

    // --- validateTriggers ---

    private fun ruleWithVoice(id: Long, phrase: String) = RuleEntity(
        id = id, name = "r$id",
        triggers = TriggerDef.listToJson(listOf(
            TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = phrase, displayName = phrase, kind = "voice"))),
        actions = "[]",
    )

    @Test fun no_voice_triggers_is_valid() {
        val triggers = listOf(TriggerDef(param = "SOC", chineseName = "电量百分比", operator = "<", value = "20", displayName = "x"))
        assertNull(RuleDraftValidator.validateTriggers(triggers, -1L, emptyList()))
    }

    @Test fun blank_voice_phrase_is_invalid() {
        val triggers = listOf(TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = "  ", displayName = "x", kind = "voice"))
        assertEquals(TriggerValidationError.VoicePhraseEmpty, RuleDraftValidator.validateTriggers(triggers, -1L, emptyList()))
    }

    @Test fun builtin_voice_phrase_is_invalid() {
        // "открой окна" -> NluParser -> OPEN+WINDOW_ALL -> built-in command (see VoiceTriggerValidationTest).
        val triggers = listOf(TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = "открой окна", displayName = "x", kind = "voice"))
        assertEquals(TriggerValidationError.VoicePhraseBuiltin, RuleDraftValidator.validateTriggers(triggers, -1L, emptyList()))
    }

    @Test fun voice_phrase_taken_by_other_rule_is_invalid() {
        val triggers = listOf(TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = "навигатор", displayName = "x", kind = "voice"))
        val existing = listOf(ruleWithVoice(id = 2, phrase = "навигатор"))
        assertEquals(TriggerValidationError.VoicePhraseTaken, RuleDraftValidator.validateTriggers(triggers, -1L, existing))
    }

    @Test fun voice_phrase_taken_by_the_rule_being_edited_is_not_a_collision() {
        val triggers = listOf(TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = "навигатор", displayName = "x", kind = "voice"))
        val existing = listOf(ruleWithVoice(id = 7, phrase = "навигатор"))
        assertNull(RuleDraftValidator.validateTriggers(triggers, editingId = 7L, existingRules = existing))
    }

    @Test fun fresh_unique_voice_phrase_is_valid() {
        val triggers = listOf(TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = "поехали", displayName = "x", kind = "voice"))
        assertNull(RuleDraftValidator.validateTriggers(triggers, -1L, emptyList()))
    }
}
