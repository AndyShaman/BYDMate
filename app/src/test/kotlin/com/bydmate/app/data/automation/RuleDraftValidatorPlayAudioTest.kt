package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Validator cases for kind="play_audio": a picked URI passes, an empty/blank one is rejected. */
class RuleDraftValidatorPlayAudioTest {

    private fun playAudio(uri: String = "content://media/external/audio/media/42", name: String = "chime.mp3") =
        ActionDef(
            command = "",
            displayName = "Play sound",
            kind = "play_audio",
            payload = """{"uri":"$uri","name":"$name"}""",
        )

    @Test fun `valid play_audio action passes validation`() {
        assertNull(RuleDraftValidator.validateActions(listOf(playAudio())))
    }

    @Test fun `empty uri returns PlayAudioUriEmpty`() {
        val err = RuleDraftValidator.validateActions(listOf(playAudio(uri = "")))
        assertEquals(ActionValidationError.PlayAudioUriEmpty(1), err)
    }

    @Test fun `blank uri returns PlayAudioUriEmpty`() {
        val err = RuleDraftValidator.validateActions(listOf(playAudio(uri = "   ")))
        assertEquals(ActionValidationError.PlayAudioUriEmpty(1), err)
    }

    @Test fun `null payload returns PlayAudioUriEmpty`() {
        val err = RuleDraftValidator.validateActions(
            listOf(ActionDef(command = "", displayName = "Play sound", kind = "play_audio", payload = null)),
        )
        assertEquals(ActionValidationError.PlayAudioUriEmpty(1), err)
    }

    @Test fun `second play_audio action failing reports index 2`() {
        val err = RuleDraftValidator.validateActions(listOf(playAudio(), playAudio(uri = "")))
        assertEquals(ActionValidationError.PlayAudioUriEmpty(2), err)
    }

    // The pure companion helper the validator and dispatcher share.
    @Test fun `playAudioUri trims and nulls blank`() {
        assertEquals("content://x", ActionDispatcher.playAudioUri("""{"uri":" content://x "}"""))
        assertNull(ActionDispatcher.playAudioUri("""{"uri":""}"""))
        assertNull(ActionDispatcher.playAudioUri(null))
        assertNull(ActionDispatcher.playAudioUri("not json"))
    }
}
