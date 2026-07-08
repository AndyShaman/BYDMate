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

    @Test fun `non-canonical spoken passes through unchanged`() {
        assertEquals("Голосовая модель не загружена",
            AgentPersona.SNARKY.spokenPhrase("Голосовая модель не загружена"))
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

    @Test fun `prompt block carries persona style, small talk rules and name`() {
        val named = AgentPersonaPrompt.block(AgentIdentity("Лео", AgentPersona.SNARKY))
        assertTrue(named.contains("ХАРАКТЕР:"))
        assertTrue(named.contains("Тебя зовут Лео"))
        assertTrue(named.contains("шутку или анекдот"))
        assertTrue(named.contains("сначала выполни"))
        val unnamed = AgentPersonaPrompt.block(AgentIdentity("", AgentPersona.NAVIGATOR))
        assertFalse(unnamed.contains("Тебя зовут"))
        assertTrue(unnamed.contains("ХАРАКТЕР:"))
    }

    @Test fun `prompt block carries gender line matching identity gender`() {
        val male = AgentPersonaPrompt.block(AgentIdentity("Лео", AgentPersona.NAVIGATOR, TtsGender.MALE))
        assertTrue(male.contains("\nТы говоришь о себе в мужском роде (сделал, включил)."))
        val female = AgentPersonaPrompt.block(AgentIdentity("Лео", AgentPersona.NAVIGATOR, TtsGender.FEMALE))
        assertTrue(female.contains("\nТы говоришь о себе в женском роде (сделала, включила)."))
    }

    @Test fun `prompt block gender line does not disturb the start of the block`() {
        val default = AgentPersonaPrompt.block(AgentIdentity("Лео", AgentPersona.NAVIGATOR))
        val explicitMale = AgentPersonaPrompt.block(AgentIdentity("Лео", AgentPersona.NAVIGATOR, TtsGender.MALE))
        assertEquals(default, explicitMale)
        assertTrue(default.startsWith("\nХАРАКТЕР: "))
    }
}
