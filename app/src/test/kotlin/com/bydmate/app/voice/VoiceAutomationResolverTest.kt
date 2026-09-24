package com.bydmate.app.voice

import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.RuleEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAutomationResolverTest {
    private fun voiceRule(id: Long, phrase: String) = RuleEntity(
        id = id, name = "r$id", enabled = true, triggerLogic = "AND",
        triggers = """[{"param":"Voice","chineseName":"语音","operator":"==","value":"$phrase","displayName":"$phrase","kind":"voice"}]""",
        actions = """[{"command":"","displayName":"x","kind":"app_launch","payload":"{}"}]""",
    )

    private fun resolver(rules: List<RuleEntity>): VoiceAutomationResolver {
        val dao = mockk<RuleDao> { coEvery { getEnabled() } returns rules }
        return VoiceAutomationResolver(dao)
    }

    @Test fun `match returns the rule on normalized equality`() = runBlocking {
        assertEquals(VoiceAutomationMatch(1L, "r1"), resolver(listOf(voiceRule(1, "навигатор"))).match("Навигатор"))
    }

    @Test fun `match returns null when nothing matches`() = runBlocking {
        assertNull(resolver(listOf(voiceRule(1, "навигатор"))).match("музыка"))
    }

    @Test fun `only the whole utterance matches, fillers and yo ignored`() = runBlocking {
        val r = resolver(listOf(voiceRule(1, "режим ёлка")))
        assertNull(r.match("Эй, включи мне режим елка, пожалуйста!"))
        assertEquals(VoiceAutomationMatch(1L, "r1"), r.match("Эй, режим елка, пожалуйста!"))
    }

    @Test fun `no partial-word match`() = runBlocking {
        assertNull(resolver(listOf(voiceRule(1, "окно"))).match("окновать"))
    }

    @Test fun `each phrase matches only itself`() = runBlocking {
        val r = resolver(listOf(voiceRule(1, "окна"), voiceRule(2, "окна дома")))
        assertEquals(2L, r.match("окна дома")?.ruleId)
        assertEquals(1L, r.match("окна")?.ruleId)
        assertNull(r.match("открой окна дома"))
    }

    @Test fun `on a tie the first rule wins`() = runBlocking {
        val r = resolver(listOf(voiceRule(1, "сцена"), voiceRule(2, "сцена")))
        assertEquals(1L, r.match("сцена")?.ruleId)
    }
}
