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

    @Test fun `phrases lists enabled voice phrases`() = runBlocking {
        assertEquals(listOf("навигатор"), resolver(listOf(voiceRule(1, "навигатор"))).phrases())
    }
    @Test fun `match returns rule id on normalized equality`() = runBlocking {
        assertEquals(1L, resolver(listOf(voiceRule(1, "навигатор"))).match("Навигатор"))
    }
    @Test fun `match returns null when nothing matches`() = runBlocking {
        assertNull(resolver(listOf(voiceRule(1, "навигатор"))).match("музыка"))
    }
}
