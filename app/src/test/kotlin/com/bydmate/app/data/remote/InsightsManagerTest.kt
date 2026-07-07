package com.bydmate.app.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsManagerTest {

    @Test
    fun cacheKey_includesLang() {
        assertEquals("insight_2026-05-13_en", cacheKey("2026-05-13", "en"))
    }

    @Test
    fun cacheKey_ruLang() {
        assertEquals("insight_2026-05-13_ru", cacheKey("2026-05-13", "ru"))
    }

    @Test
    fun buildPrompt_en_containsEnglishInstruction() {
        val prompt = makeManager().buildPromptForTest(sampleInsightData(), "en")
        assertTrue(prompt.contains("in English", ignoreCase = true))
    }

    @Test
    fun buildPrompt_ru_containsRussianInstruction() {
        val prompt = makeManager().buildPromptForTest(sampleInsightData(), "ru")
        assertTrue(prompt.contains("на русском", ignoreCase = true))
    }

    private fun cacheKey(date: String, lang: String): String {
        val method = InsightsManager::class.java.getDeclaredMethod(
            "cacheKey",
            String::class.java,
            String::class.java,
        )
        return method.invoke(null, date, lang) as String
    }

    private fun InsightsManager.buildPromptForTest(data: InsightData, lang: String): String {
        val method = javaClass.getDeclaredMethod(
            "buildPromptForTest",
            InsightData::class.java,
            String::class.java,
        )
        return method.invoke(this, data, lang) as String
    }

    private fun makeManager(): InsightsManager =
        InsightsManager(
            context = mockContext(),
            openRouterClient = mockk<OpenRouterClient>(relaxed = true),
            tripDao = mockk<TripDao>(relaxed = true),
            idleDrainDao = mockk<IdleDrainDao>(relaxed = true),
            chargeDao = mockk<ChargeDao>(relaxed = true),
            settingsRepository = mockk<SettingsRepository>(relaxed = true),
        )

    private fun mockContext(): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }

    private fun sampleInsightData(): InsightData =
        InsightData(
            title = "Sample",
            summary = "Sample",
            dynamics = emptyList(),
            insights = emptyList(),
            tone = "good",
        )
}
