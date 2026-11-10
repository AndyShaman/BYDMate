package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AgentPersonaTest {
    @Test fun `fromId maps known ids and defaults to navigator`() {
        assertEquals(AgentPersona.SNARKY, AgentPersona.fromId("snarky"))
        assertEquals(AgentPersona.ENGINEER, AgentPersona.fromId("engineer"))
        assertEquals(AgentPersona.NAVIGATOR, AgentPersona.fromId("navigator"))
        assertEquals(AgentPersona.NAVIGATOR, AgentPersona.fromId("unknown"))
        assertEquals(AgentPersona.NAVIGATOR, AgentPersona.fromId(null))
    }

    @Test fun `canonical outcomes map to persona pools deterministically`() {
        // Same seed -> same pick (deterministic), and the pick comes from the pool,
        // not a pass-through of the canonical word: every SNARKY pool phrase differs
        // from its canonical key, so a no-op spokenPhrase would fail here.
        val snarkyDone = listOf("Готово, блин.", "Сделал. Чудо, да?", "Есть. Не благодари.",
            "Ну сделал, сделал.", "Опа, сработало. Сам в шоке.")
        val phrase = AgentPersona.SNARKY.spokenPhrase("Готово", Random(1))
        assertTrue(phrase in snarkyDone)
        assertEquals(phrase, AgentPersona.SNARKY.spokenPhrase("Готово", Random(1)))
        val all = listOf("Готово", "Не получилось", "Не понял", "Ошибка")
        for (s in all) {
            assertFalse(s, AgentPersona.SNARKY.spokenPhrase(s, Random(7)) == s)
        }
        for (p in AgentPersona.entries) for (s in all) {
            assertTrue(p.spokenPhrase(s, Random(7)).isNotBlank())
        }
    }

    @Test fun `no em-dash anywhere in pools or prompt blocks`() {
        val all = listOf("Готово", "Не получилось", "Не понял", "Ошибка")
        for (p in AgentPersona.entries) {
            for (s in all) repeat(20) { i ->
                assertFalse(p.spokenPhrase(s, Random(i)).contains("—"))
            }
            assertFalse(AgentPersonaPrompt.block(AgentIdentity("Лео", p)).contains("—"))
        }
    }

    @Test fun `phrases includes both the outcome pools and the fillers`() {
        for (p in AgentPersona.entries) {
            val phrases = p.phrases()
            assertTrue(phrases.contains(p.spokenPhrase("Готово", Random(1))))
            assertTrue(phrases.contains(p.fillerPhrase(Random(1))))
        }
    }

    private val canonical = listOf("Готово", "Не получилось", "Не понял", "Ошибка")
    private val masculinePast = Regex("""(?<![А-Яа-яЁё])(Выполнил|Сделал|сделал|Не расслышал|Не понял|Не разобрал|Сам)(?![А-Яа-яЁё])""")

    private fun allSpoken(p: AgentPersona, g: TtsGender): Set<String> =
        canonical.flatMap { s -> (0 until 200).map { p.spokenPhrase(s, Random(it), g) } }.toSet()

    @Test fun `female gender never speaks a masculine past-tense form`() {
        for (p in AgentPersona.entries) for (phrase in allSpoken(p, TtsGender.FEMALE)) {
            assertFalse(phrase, masculinePast.containsMatchIn(phrase))
        }
        val snarky = allSpoken(AgentPersona.SNARKY, TtsGender.FEMALE)
        assertTrue(snarky.containsAll(listOf("Сделала. Чудо, да?", "Ну сделала, сделала.",
            "Опа, сработало. Сама в шоке.")))
        assertTrue("Выполнила. Хорошей дороги." in allSpoken(AgentPersona.NAVIGATOR, TtsGender.FEMALE))
        assertTrue("Не расслышала. Повтори, пожалуйста." in allSpoken(AgentPersona.NAVIGATOR, TtsGender.FEMALE))
    }

    @Test fun `phrases follows gender for the precache`() {
        assertTrue("Выполнила. Хорошей дороги." in AgentPersona.NAVIGATOR.phrases(TtsGender.FEMALE))
        assertFalse("Выполнил. Хорошей дороги." in AgentPersona.NAVIGATOR.phrases(TtsGender.FEMALE))
        assertTrue("Выполнил. Хорошей дороги." in AgentPersona.NAVIGATOR.phrases())
    }
}
