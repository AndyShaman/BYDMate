package com.bydmate.app.agent

import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmConnectionResolverTest {

    private val settings = mockk<SettingsRepository>()
    private val resolver = LlmConnectionResolver(settings)

    private fun stub(vararg pairs: Pair<String, String>) {
        coEvery { settings.getString(any(), any()) } answers {
            pairs.toMap()[firstArg()] ?: secondArg()
        }
    }

    @Test
    fun `openrouter configured when key and model set`() = runTest {
        stub(
            SettingsRepository.KEY_OPENROUTER_API_KEY to "sk-or-1",
            SettingsRepository.KEY_OPENROUTER_MODEL to "z-ai/glm-4.6",
        )
        val c = resolver.get(LlmConnectionResolver.ID_OPENROUTER)!!
        assertEquals("OpenRouter", c.label)
        assertEquals("https://openrouter.ai/api/v1", c.baseUrl)
        assertEquals("z-ai/glm-4.6", c.model)
    }

    @Test
    fun `openrouter without model is not configured`() = runTest {
        stub(SettingsRepository.KEY_OPENROUTER_API_KEY to "sk-or-1")
        assertNull(resolver.get(LlmConnectionResolver.ID_OPENROUTER))
    }

    @Test
    fun `zai needs only a key and pins glm model`() = runTest {
        stub(SettingsRepository.KEY_ZAI_API_KEY to "zk")
        val c = resolver.get(LlmConnectionResolver.ID_ZAI)!!
        assertEquals("https://api.z.ai/api/paas/v4", c.baseUrl)
        assertEquals("glm-4.7-flash", c.model)
    }

    @Test
    fun `custom needs url key and model, trailing slash trimmed, blank name defaults`() = runTest {
        stub(
            SettingsRepository.KEY_CUSTOM_BASE_URL to "https://api.deepseek.com/v1/",
            SettingsRepository.KEY_CUSTOM_API_KEY to "dk",
            SettingsRepository.KEY_CUSTOM_MODEL to "deepseek-chat",
        )
        val c = resolver.get(LlmConnectionResolver.ID_CUSTOM)!!
        assertEquals("https://api.deepseek.com/v1", c.baseUrl)
        assertEquals("Своё", c.label)
    }

    @Test
    fun `custom without model is not configured`() = runTest {
        stub(
            SettingsRepository.KEY_CUSTOM_BASE_URL to "https://x/v1",
            SettingsRepository.KEY_CUSTOM_API_KEY to "k",
        )
        assertNull(resolver.get(LlmConnectionResolver.ID_CUSTOM))
    }

    @Test
    fun `primary defaults to openrouter when key unset`() = runTest {
        stub(
            SettingsRepository.KEY_OPENROUTER_API_KEY to "sk",
            SettingsRepository.KEY_OPENROUTER_MODEL to "m",
        )
        assertEquals(LlmConnectionResolver.ID_OPENROUTER, resolver.primary()!!.id)
    }

    @Test
    fun `fallback null when same as primary or unconfigured`() = runTest {
        stub(
            SettingsRepository.KEY_AGENT_PRIMARY_CONN to "zai",
            SettingsRepository.KEY_AGENT_FALLBACK_CONN to "zai",
            SettingsRepository.KEY_ZAI_API_KEY to "zk",
        )
        assertNull(resolver.fallback())
    }

    @Test
    fun `fallback resolves distinct configured connection`() = runTest {
        stub(
            SettingsRepository.KEY_AGENT_PRIMARY_CONN to "zai",
            SettingsRepository.KEY_AGENT_FALLBACK_CONN to "openrouter",
            SettingsRepository.KEY_ZAI_API_KEY to "zk",
            SettingsRepository.KEY_OPENROUTER_API_KEY to "sk",
            SettingsRepository.KEY_OPENROUTER_MODEL to "m",
        )
        assertEquals(LlmConnectionResolver.ID_OPENROUTER, resolver.fallback()!!.id)
    }

    @Test
    fun `configured lists only fully configured connections`() = runTest {
        stub(SettingsRepository.KEY_ZAI_API_KEY to "zk")
        assertEquals(listOf(LlmConnectionResolver.ID_ZAI), resolver.configured().map { it.id })
    }
}
