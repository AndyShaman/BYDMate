package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteeringKeyOwnerTest {
    private val none = SteeringKeyOwners(
        projectionEnabled = false, projectionKey = DEFAULT_TRIGGER_KEYCODE,
        voiceEnabled = false, voiceKey = DEFAULT_VOICE_KEYCODE, voiceCompanions = emptySet(),
        knobEnabled = false, splitEnabled = false, splitKey = 0,
    )

    @Test fun `with every feature off each key is free`() {
        for (code in listOf(DEFAULT_TRIGGER_KEYCODE, DEFAULT_VOICE_KEYCODE, VOLUME_KNOB_PRESS_KEYCODE, 383)) {
            assertNull(steeringKeyOwner(code, none))
        }
    }
    @Test fun `projection owns its trigger only while on`() {
        val on = none.copy(projectionEnabled = true)
        assertEquals(SteeringKeyOwner.PROJECTION, steeringKeyOwner(DEFAULT_TRIGGER_KEYCODE, on))
        assertNull(steeringKeyOwner(383, on))
    }
    @Test fun `voice owns its key and the companion codes of its press`() {
        val on = none.copy(voiceEnabled = true, voiceKey = 304, voiceCompanions = setOf(327))
        assertEquals(SteeringKeyOwner.VOICE, steeringKeyOwner(304, on))
        assertEquals(SteeringKeyOwner.VOICE, steeringKeyOwner(327, on))
        assertNull(steeringKeyOwner(320, on))
    }
    @Test fun `voice off frees its key and companions`() {
        val off = none.copy(voiceKey = 304, voiceCompanions = setOf(327))
        assertNull(steeringKeyOwner(304, off))
        assertNull(steeringKeyOwner(327, off))
    }
    @Test fun `knob owns the knob press only while on`() {
        assertEquals(SteeringKeyOwner.KNOB, steeringKeyOwner(VOLUME_KNOB_PRESS_KEYCODE, none.copy(knobEnabled = true)))
        assertNull(steeringKeyOwner(VOLUME_KNOB_PRESS_KEYCODE, none))
    }
    @Test fun `split owns its key only while the feature is on`() {
        assertEquals(SteeringKeyOwner.SPLIT, steeringKeyOwner(383, none.copy(splitEnabled = true, splitKey = 383)))
        assertNull(steeringKeyOwner(383, none.copy(splitEnabled = false, splitKey = 383)))
    }
    @Test fun `an unassigned split key owns nothing`() {
        assertNull(steeringKeyOwner(0, none.copy(splitEnabled = true, splitKey = 0)))
    }
    @Test fun `on a shared key the earlier filter stage is reported`() {
        val both = none.copy(voiceEnabled = true, voiceKey = 383, splitEnabled = true, splitKey = 383)
        assertEquals(SteeringKeyOwner.VOICE, steeringKeyOwner(383, both))
    }

    // Decision 6: the voice and projection learn dialogs ask the same owner function, with their
    // own feature switched off so re-learning their own key is not "occupied by itself".
    @Test fun `voice learn dialog sees the split key as occupied`() {
        val owners = none.copy(voiceEnabled = true, splitEnabled = true, splitKey = 383).copy(voiceEnabled = false)
        assertEquals(SteeringKeyOwner.SPLIT, steeringKeyOwner(383, owners))
    }
    @Test fun `projection learn dialog sees the split key as occupied`() {
        val owners = none.copy(projectionEnabled = true, splitEnabled = true, splitKey = 383).copy(projectionEnabled = false)
        assertEquals(SteeringKeyOwner.SPLIT, steeringKeyOwner(383, owners))
    }
    @Test fun `learn dialogs do not see an inactive split key as occupied`() {
        assertNull(steeringKeyOwner(383, none.copy(splitEnabled = false, splitKey = 383)))
        assertNull(steeringKeyOwner(383, none.copy(splitEnabled = true, splitKey = 0)))
    }
    @Test fun `the split chooser ignores its own key when relearning`() {
        val owners = none.copy(splitEnabled = true, splitKey = 383)
        assertNull(steeringKeyOwner(383, owners.copy(splitEnabled = false)))
    }
}
