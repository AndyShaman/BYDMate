package com.bydmate.app.voice

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUserPhrasesTest {
    private val close = "windows_close_all"
    private val open = "windows_open_all"

    /** SharedPreferences holding one string in memory, enough for the JSON round trip. */
    private fun memoryPrefs(): SharedPreferences {
        var stored: String? = null
        val value = slot<String>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(VoiceUserPhrases.KEY, capture(value)) } answers { stored = value.captured; editor }
        return mockk {
            every { getString(VoiceUserPhrases.KEY, any()) } answers { stored }
            every { edit() } returns editor
        }
    }

    @Test fun `catalog commands are the parser's fixed commands`() {
        val ids = VoiceUserPhrases.COMMANDS.map { it.id }
        assertTrue(ids.toString(), close in ids && open in ids)
        assertEquals("车窗关闭", VoiceUserPhrases.COMMANDS.first { it.id == close }.command)
    }

    @Test fun `equal phrase matches`() {
        val p = VoiceUserPhrases().apply { add(close, "задраить люки") }
        val cmd = VoiceUserPhrases.COMMANDS.first { it.id == close }
        assertEquals(VoiceUserMatch(cmd, exact = true), p.match("задраить люки"))
    }

    @Test fun `phrase contained in the utterance matches`() {
        val p = VoiceUserPhrases().apply { add(close, "задраить люки") }
        assertEquals(close, p.match("ну давай задраить люки быстро")?.command?.id)
        assertEquals(false, p.match("ну давай задраить люки быстро")?.exact)
    }

    @Test fun `fillers punctuation case and yo are ignored`() {
        val p = VoiceUserPhrases().apply { add(open, "Ёлки открыть") }
        assertEquals(open, p.match("Эй, слушай, елки открыть мне, пожалуйста!")?.command?.id)
        assertEquals(true, p.match("Эй, слушай, елки открыть мне, пожалуйста!")?.exact)
    }

    // Exactness is word for word: another inflection is only contained, so the parser keeps it.
    @Test fun `another inflection of the phrase is contained, not exact`() {
        val p = VoiceUserPhrases().apply { add(close, "открой окна") }
        assertEquals(VoiceUserMatch(VoiceUserPhrases.COMMANDS.first { it.id == close }, exact = false), p.match("открой окно"))
        assertEquals(true, p.match("слушай открой окна пожалуйста")?.exact)
    }

    @Test fun `no partial-word match`() {
        val p = VoiceUserPhrases().apply { add(close, "окно") }
        assertNull(p.match("окновать"))
    }

    @Test fun `longest phrase wins`() {
        val p = VoiceUserPhrases().apply {
            add(open, "проветрить")
            add(close, "хватит проветрить")
        }
        assertEquals(close, p.match("хватит проветрить")?.command?.id)
        assertEquals(open, p.match("проветрить")?.command?.id)
    }

    @Test fun `remove drops the phrase`() {
        val p = VoiceUserPhrases().apply { add(close, "задраить люки"); remove(close, "задраить люки") }
        assertNull(p.match("задраить люки"))
        assertTrue(p.phrases.value.isEmpty())
    }

    @Test fun `phrases persist through prefs`() {
        val prefs = memoryPrefs()
        VoiceUserPhrases(prefs).apply { add(close, "задраить люки"); add(open, "проветрить") }

        val reloaded = VoiceUserPhrases(prefs)
        assertEquals(mapOf(close to listOf("задраить люки"), open to listOf("проветрить")), reloaded.phrases.value)
        assertEquals(close, reloaded.match("задраить люки")?.command?.id)
    }

    @Test fun `check refuses empty, too long and too many`() {
        val p = VoiceUserPhrases()
        assertEquals(VoiceUserPhrases.Check.Empty, p.check(close, " ,! ", emptyMap()))
        assertEquals(VoiceUserPhrases.Check.TooLong, p.check(close, "а".repeat(VoiceUserPhrases.MAX_CHARS + 1), emptyMap()))
        repeat(VoiceUserPhrases.MAX_PHRASES) { p.add(close, "фраза номер $it") }
        assertEquals(VoiceUserPhrases.Check.TooMany, p.check(close, "ещё одна", emptyMap()))
        assertEquals(VoiceUserPhrases.Check.Ok, p.check(open, "ещё одна", emptyMap()))
    }

    @Test fun `check refuses only exact duplicates of user and automation phrases`() {
        val p = VoiceUserPhrases().apply { add(close, "задраить люки") }
        val closeName = VoiceUserPhrases.COMMANDS.first { it.id == close }.name

        assertEquals(VoiceUserPhrases.Check.InUse(closeName), p.check(open, "Задраить, люки!", emptyMap()))
        assertEquals(
            VoiceUserPhrases.Check.InUse("Дача"),
            p.check(open, "поехали на дачу", mapOf(VoicePhrase.normalize("поехали на дачу") to "Дача")),
        )
        // A phrase that merely contains another one, or one the built-in parser knows, is fine.
        assertEquals(VoiceUserPhrases.Check.Ok, p.check(open, "задраить люки сейчас", emptyMap()))
        assertEquals(VoiceUserPhrases.Check.Ok, p.check(open, "открой окна", emptyMap()))
    }

    @Test fun `owners maps normalized phrases to command names`() {
        val p = VoiceUserPhrases().apply { add(close, "Задраить люки") }
        val name = VoiceUserPhrases.COMMANDS.first { it.id == close }.name
        assertEquals(mapOf(VoicePhrase.normalize("задраить люки") to name), p.owners())
    }
}
