package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
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

    // Atto 3 (DiLink 3.0): one press of the mic button sends 304 and 327, and the stock assistant
    // reacts to 327. The learned press keeps the first code as primary and the other as a companion.
    @Test fun primary_down_triggers_and_up_is_consumed_with_companions() {
        assertEquals(VoiceKeyDecision.TRIGGER, voiceDecision(304, isDown = true, voiceEnabled = true, voiceKeyCode = 304, companions = setOf(327)))
        assertEquals(VoiceKeyDecision.CONSUME, voiceDecision(304, isDown = false, voiceEnabled = true, voiceKeyCode = 304, companions = setOf(327)))
    }
    @Test fun companion_down_and_up_are_consumed_never_triggered() {
        assertEquals(VoiceKeyDecision.CONSUME, voiceDecision(327, isDown = true, voiceEnabled = true, voiceKeyCode = 304, companions = setOf(327)))
        assertEquals(VoiceKeyDecision.CONSUME, voiceDecision(327, isDown = false, voiceEnabled = true, voiceKeyCode = 304, companions = setOf(327)))
    }
    @Test fun reverse_roles_trigger_on_the_primary_only() {
        assertEquals(VoiceKeyDecision.TRIGGER, voiceDecision(327, isDown = true, voiceEnabled = true, voiceKeyCode = 327, companions = setOf(304)))
        assertEquals(VoiceKeyDecision.CONSUME, voiceDecision(327, isDown = false, voiceEnabled = true, voiceKeyCode = 327, companions = setOf(304)))
        assertEquals(VoiceKeyDecision.CONSUME, voiceDecision(304, isDown = true, voiceEnabled = true, voiceKeyCode = 327, companions = setOf(304)))
        assertEquals(VoiceKeyDecision.CONSUME, voiceDecision(304, isDown = false, voiceEnabled = true, voiceKeyCode = 327, companions = setOf(304)))
    }
    @Test fun voice_disabled_ignores_primary_and_companion() {
        for (code in listOf(304, 327)) for (down in listOf(true, false)) {
            assertEquals(VoiceKeyDecision.IGNORE, voiceDecision(code, isDown = down, voiceEnabled = false, voiceKeyCode = 304, companions = setOf(327)))
        }
    }
    @Test fun other_keys_are_ignored_with_companions() {
        assertEquals(VoiceKeyDecision.IGNORE, voiceDecision(351, isDown = true, voiceEnabled = true, voiceKeyCode = 304, companions = setOf(327)))
        assertEquals(VoiceKeyDecision.IGNORE, voiceDecision(351, isDown = false, voiceEnabled = true, voiceKeyCode = 304, companions = setOf(327)))
    }
    // Every car with a single-code button stores no companions: the results must stay the old ones.
    @Test fun empty_companions_keep_single_code_results() {
        val cases = listOf(
            Triple(320, true, true) to VoiceKeyDecision.TRIGGER,
            Triple(320, false, true) to VoiceKeyDecision.CONSUME,
            Triple(320, false, false) to VoiceKeyDecision.IGNORE,
            Triple(320, true, false) to VoiceKeyDecision.IGNORE,
            Triple(351, true, true) to VoiceKeyDecision.IGNORE,
            Triple(351, false, true) to VoiceKeyDecision.IGNORE,
        )
        for ((input, expected) in cases) {
            val (code, down, enabled) = input
            assertEquals(expected, voiceDecision(code, isDown = down, voiceEnabled = enabled, voiceKeyCode = 320, companions = emptySet()))
        }
    }

    @Test fun companions_csv_round_trip() {
        for (set in listOf(emptySet(), setOf(327), setOf(304, 327))) {
            assertEquals(set, voiceCompanionsFromCsv(voiceCompanionsToCsv(set)))
        }
        assertEquals("", voiceCompanionsToCsv(emptySet()))
        assertEquals("304,327", voiceCompanionsToCsv(setOf(327, 304)))
    }
    @Test fun companions_csv_garbage_reads_as_empty() {
        for (garbage in listOf(null, "", ",", "abc", "327,abc", "327,", "-5", "0")) {
            assertEquals("input=$garbage", emptySet<Int>(), voiceCompanionsFromCsv(garbage))
        }
    }
}
