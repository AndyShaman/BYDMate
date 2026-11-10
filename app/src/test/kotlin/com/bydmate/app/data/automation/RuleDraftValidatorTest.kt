package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleDraftValidatorTest {

    // --- validateActions ---

    private class StepCase(val label: String, val step: ActionDef, val expected: ActionValidationError?)

    private fun step(kind: String, payload: String?, command: String = "") =
        ActionDef(command = command, displayName = "x", kind = kind, payload = payload)

    private fun delay(payload: String?) = step("delay", payload)

    private val stepCases by lazy {
        listOf(
            StepCase("telegram_report without items or text", step("telegram_report", """{"fields":[],"text":"  "}""",
                "telegram_report"), ActionValidationError.TelegramReportEmpty(1)),
            StepCase("telegram_report with only own text", step("telegram_report", """{"fields":[],"text":"Я выехал"}""",
                "telegram_report"), null),
            StepCase("delay 300000", delay("300000"), null),
            StepCase("delay 86400000 (24 h)", delay("86400000"), null),
            StepCase("delay 0", delay("0"), null),
            StepCase("delay 86400001", delay("86400001"), ActionValidationError.DelayInvalid(1)),
            StepCase("delay empty", delay(""), ActionValidationError.DelayInvalid(1)),
            StepCase("delay null", delay(null), ActionValidationError.DelayInvalid(1)),
            StepCase("delay -5", delay("-5"), ActionValidationError.DelayInvalid(1)),
            StepCase("delay abc", delay("abc"), ActionValidationError.DelayInvalid(1)),
            StepCase("param blank command", step("param", null), ActionValidationError.CommandMissing(1)),
            StepCase("param with command", step("param", null, "车窗关闭"), null),
            StepCase("notification_silent blank title", step("notification_silent", """{"title":"","text":""}"""),
                ActionValidationError.NotifTitleEmpty(1)),
            StepCase("merged notification kind with blank title", step("notification", """{"title":"","text":"body"}"""),
                ActionValidationError.NotifTitleEmpty(1)),
            StepCase("app_launch blank package", step("app_launch", """{"packageName":"","appLabel":""}"""),
                ActionValidationError.AppNotSelected(1)),
            StepCase("app_close blank package", step("app_close", """{"packageName":"","appLabel":""}"""),
                ActionValidationError.AppNotSelected(1)),
            StepCase("media_key play", step("media_key", "play"), null),
            StepCase("media_key pause", step("media_key", "pause"), null),
            StepCase("media_key toggle", step("media_key", "toggle"), ActionValidationError.MediaKeyInvalid(1)),
            StepCase("call phone too short", step("call", """{"phone":"123"}"""), ActionValidationError.PhoneInvalid(1)),
            StepCase("call valid phone", step("call", """{"phone":"+79161234567"}"""), null),
            StepCase("navigate both zero", step("navigate", """{"lat":0,"lon":0}"""), ActionValidationError.NavDestMissing(1)),
            StepCase("navigate valid coords", step("navigate", """{"lat":55.7,"lon":37.6}"""), null),
            StepCase("url empty", step("url", """{"url":""}"""), ActionValidationError.UrlEmpty(1)),
            StepCase("url no scheme", step("url", """{"url":"example.com"}"""), ActionValidationError.UrlNoScheme(1)),
            StepCase("url with scheme", step("url", """{"url":"https://example.com"}"""), null),
            StepCase("yandex_music blank mode", step("yandex_music", """{"mode":""}"""),
                ActionValidationError.YandexMusicModeMissing(1)),
            // Signed "-N" is a relative volume step (ActionDispatcher.resolveVolumeOp), not an
            // absolute negative level, so it is valid — was previously (incorrectly) rejected.
            StepCase("media_volume signed negative is a relative step", step("media_volume", "-1", "media_volume"), null),
            StepCase("media_volume level", step("media_volume", "5", "media_volume"), null),
            StepCase("media_volume mute", step("media_volume", "mute", "media_volume"), null),
            StepCase("media_volume unmute", step("media_volume", "unmute", "media_volume"), null),
            StepCase("media_volume garbage", step("media_volume", "loud", "media_volume"),
                ActionValidationError.MediaVolumeMissing(1)),
            StepCase("sentry 2", step("sentry", "2", "sentry"), ActionValidationError.SentryInvalid(1)),
            StepCase("sentry 1", step("sentry", "1", "sentry"), null),
            StepCase("screen 0", step("screen", "0", "screen"), null),
            StepCase("screen 1", step("screen", "1", "screen"), null),
            StepCase("screen 2", step("screen", "2", "screen"), ActionValidationError.ScreenInvalid(1)),
            StepCase("screen null", step("screen", null, "screen"), ActionValidationError.ScreenInvalid(1)),
            StepCase("hotspot 2", step("hotspot", "2", "hotspot"), ActionValidationError.HotspotInvalid(1)),
            StepCase("hotspot on (1)", step("hotspot", "1", "hotspot"), null),
            StepCase("hotspot off (0)", step("hotspot", "0", "hotspot"), null),
            StepCase("hotspot garbage", step("hotspot", "on", "hotspot"), ActionValidationError.HotspotInvalid(1)),
            StepCase("speak blank text", step("speak", """{"text":""}"""), ActionValidationError.SpeakTextEmpty(1)),
            StepCase("speak with text", step("speak", """{"text":"Добро пожаловать"}"""), null),
            StepCase("agent_query blank prompt", step("agent_query", """{"prompt":""}"""),
                ActionValidationError.AgentQueryPromptEmpty(1)),
            StepCase("agent_query with prompt", step("agent_query", """{"prompt":"Какой заряд?"}"""), null),
        )
    }

    @Test fun `each kind of step with its payload is valid or names its error`() {
        for (c in stepCases) {
            assertEquals("case: ${c.label}", c.expected, RuleDraftValidator.validateActions(listOf(c.step)))
        }
    }

    @Test fun `an empty step list is valid and an error names its 1-based step`() {
        assertNull("no steps", RuleDraftValidator.validateActions(emptyList()))
        assertEquals("bad delay second", ActionValidationError.DelayInvalid(2),
            RuleDraftValidator.validateActions(listOf(step("param", null, "车窗关闭"), delay("86400001"))))
        assertEquals("bad command second", ActionValidationError.CommandMissing(2), RuleDraftValidator.validateActions(listOf(
            ActionDef(command = "车窗关闭", displayName = "ok", kind = "param"),
            ActionDef(command = "", displayName = "bad", kind = "param"),
        )))
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

    @Test fun phrase_the_builtin_parser_understands_is_valid() {
        // Automations resolve before built-in commands, so the rule takes the phrase over.
        val triggers = listOf(TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = "открой окна", displayName = "x", kind = "voice"))
        assertNull(RuleDraftValidator.validateTriggers(triggers, -1L, emptyList()))
    }

    @Test fun user_phrase_of_a_builtin_command_is_invalid_and_names_the_command() {
        val triggers = listOf(TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = "Проветрить!", displayName = "x", kind = "voice"))
        val userPhrases = mapOf(com.bydmate.app.voice.VoicePhrase.normalize("проветрить") to "все окна на проветривание")
        assertEquals(
            TriggerValidationError.VoicePhraseBuiltin("все окна на проветривание"),
            RuleDraftValidator.validateTriggers(triggers, -1L, emptyList(), userPhrases),
        )
    }

    @Test fun voice_phrase_taken_by_other_rule_is_invalid_and_names_the_rule() {
        val triggers = listOf(TriggerDef(param = "Voice", chineseName = "语音", operator = "==", value = "навигатор", displayName = "x", kind = "voice"))
        val existing = listOf(ruleWithVoice(id = 2, phrase = "навигатор"))
        assertEquals(TriggerValidationError.VoicePhraseTaken("r2"), RuleDraftValidator.validateTriggers(triggers, -1L, existing))
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
