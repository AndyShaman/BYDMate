package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringWheelKeyDecisionVoiceTest {
    @Test fun triggers_on_configured_voice_key_down() {
        assertEquals(VoiceKeyDecision.TRIGGER, voiceDecision(320, isDown = true, voiceEnabled = true, voiceKeyCode = 320))
    }
    // The matching key's UP edge must be CONSUMEd, not IGNOREd — otherwise it falls through
    // to the native BYD assistant, which owns the same hardware keycode (320).
    @Test fun consumes_key_up_when_matched_and_enabled() {
        assertEquals(VoiceKeyDecision.CONSUME, voiceDecision(320, isDown = false, voiceEnabled = true, voiceKeyCode = 320))
    }
    @Test fun ignores_key_up_when_voice_disabled() {
        assertEquals(VoiceKeyDecision.IGNORE, voiceDecision(320, isDown = false, voiceEnabled = false, voiceKeyCode = 320))
    }
    @Test fun ignores_when_voice_disabled() {
        assertEquals(VoiceKeyDecision.IGNORE, voiceDecision(320, isDown = true, voiceEnabled = false, voiceKeyCode = 320))
    }
    @Test fun ignores_other_keys() {
        assertEquals(VoiceKeyDecision.IGNORE, voiceDecision(351, isDown = true, voiceEnabled = true, voiceKeyCode = 320))
    }
    @Test fun ignores_other_keys_key_up() {
        assertEquals(VoiceKeyDecision.IGNORE, voiceDecision(351, isDown = false, voiceEnabled = true, voiceKeyCode = 320))
    }

    @Test fun dilink3_304_triggers_selected_voice_provider_on_down() {
        assertEquals(VoiceKeyDecision.TRIGGER, diLink3VoiceDecision(304, isDown = true, voiceEnabled = true))
    }

    @Test fun dilink3_304_consumes_up_edge() {
        assertEquals(VoiceKeyDecision.CONSUME, diLink3VoiceDecision(304, isDown = false, voiceEnabled = true))
    }

    @Test fun dilink3_327_is_consumed_while_voice_enabled() {
        assertEquals(VoiceKeyDecision.CONSUME, diLink3VoiceDecision(327, isDown = true, voiceEnabled = true))
        assertEquals(VoiceKeyDecision.CONSUME, diLink3VoiceDecision(327, isDown = false, voiceEnabled = true))
    }

    @Test fun dilink3_special_codes_pass_through_when_voice_disabled() {
        assertEquals(VoiceKeyDecision.IGNORE, diLink3VoiceDecision(304, isDown = true, voiceEnabled = false))
        assertEquals(VoiceKeyDecision.IGNORE, diLink3VoiceDecision(327, isDown = true, voiceEnabled = false))
    }
    @Test fun local_voice_second_press_within_window_is_double_press() {
        assertTrue(isVoiceDoublePress(previousDownMs = 1_000L, nowMs = 1_250L))
    }

    @Test fun local_voice_press_after_window_is_normal_press() {
        assertFalse(isVoiceDoublePress(previousDownMs = 1_000L, nowMs = 1_251L))
    }

    @Test fun first_local_voice_press_is_never_double_press() {
        assertFalse(isVoiceDoublePress(previousDownMs = 0L, nowMs = 100L))
    }

    @Test fun yandex_context_routes_single_press_to_alice() {
        assertEquals(
            VoicePressRoute.ALICE,
            voicePressRoute(
                repeatCount = 0,
                aliceContextActive = true,
                keyCode = 304,
                previous = VoicePressMemory(-1, 0L),
                nowMs = 10_000L,
            ),
        )
    }

    @Test fun outside_yandex_context_first_press_routes_to_local() {
        assertEquals(
            VoicePressRoute.LOCAL,
            voicePressRoute(
                repeatCount = 0,
                aliceContextActive = false,
                keyCode = 304,
                previous = VoicePressMemory(-1, 0L),
                nowMs = 10_000L,
            ),
        )
    }

    @Test fun yandex_browser_and_navigator_are_alice_contexts() {
        assertTrue(isAliceContextPackage("com.yandex.browser"))
        assertTrue(isAliceContextPackage("ru.yandex.yandexnavi"))
        assertFalse(isAliceContextPackage("com.bydmate.app"))
    }

}
