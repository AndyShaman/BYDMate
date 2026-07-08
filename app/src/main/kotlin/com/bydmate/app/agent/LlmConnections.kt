package com.bydmate.app.agent

import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** One OpenAI-compatible LLM connection resolved from Settings. */
data class LlmConnection(
    val id: String,
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/**
 * Resolves the three connection slots (OpenRouter / z.ai / custom) from the settings
 * table. A connection is "configured" when every field it needs is non-blank; the
 * z.ai slot pins its model (free flash tier) so only the key is required.
 */
@Singleton
class LlmConnectionResolver @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    /** Returns the connection if fully configured, else null. */
    suspend fun get(id: String): LlmConnection? = when (id) {
        ID_OPENROUTER -> {
            val key = str(SettingsRepository.KEY_OPENROUTER_API_KEY)
            val model = str(SettingsRepository.KEY_OPENROUTER_MODEL)
            if (key.isBlank() || model.isBlank()) null
            else LlmConnection(ID_OPENROUTER, "OpenRouter", OPENROUTER_BASE_URL, key, model)
        }
        ID_ZAI -> {
            val key = str(SettingsRepository.KEY_ZAI_API_KEY)
            if (key.isBlank()) null
            else LlmConnection(ID_ZAI, "z.ai", ZAI_BASE_URL, key, ZAI_MODEL)
        }
        ID_CUSTOM -> {
            val url = str(SettingsRepository.KEY_CUSTOM_BASE_URL).trim().trimEnd('/')
            val key = str(SettingsRepository.KEY_CUSTOM_API_KEY)
            val model = str(SettingsRepository.KEY_CUSTOM_MODEL)
            if (url.isBlank() || key.isBlank() || model.isBlank()) null
            else LlmConnection(
                ID_CUSTOM,
                str(SettingsRepository.KEY_CUSTOM_NAME).ifBlank { DEFAULT_CUSTOM_LABEL },
                url, key, model,
            )
        }
        else -> null
    }

    suspend fun configured(): List<LlmConnection> =
        listOf(ID_OPENROUTER, ID_ZAI, ID_CUSTOM).mapNotNull { get(it) }

    suspend fun primary(): LlmConnection? = get(primaryId())

    /** Fallback connection, or null when unset, unconfigured, or same as primary. */
    suspend fun fallback(): LlmConnection? {
        val id = str(SettingsRepository.KEY_AGENT_FALLBACK_CONN)
        if (id.isBlank() || id == primaryId()) return null
        return get(id)
    }

    private suspend fun primaryId(): String =
        str(SettingsRepository.KEY_AGENT_PRIMARY_CONN).ifBlank { ID_OPENROUTER }

    private suspend fun str(key: String): String = settingsRepository.getString(key, "")

    companion object {
        const val ID_OPENROUTER = "openrouter"
        const val ID_ZAI = "zai"
        const val ID_CUSTOM = "custom"
        const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        const val ZAI_BASE_URL = "https://api.z.ai/api/paas/v4"
        const val ZAI_MODEL = "glm-4.7-flash"
        // Selector label when the user left the custom connection unnamed (agent UI is Russian-first).
        internal const val DEFAULT_CUSTOM_LABEL = "Своё"
    }
}
