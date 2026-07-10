package com.bydmate.app.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class InsightsManagerTest {

    @Test
    fun cacheKey_includesLang() {
        assertEquals("insight_2026-05-13_en_7", cacheKey("2026-05-13", "en", 7))
    }

    @Test
    fun cacheKey_ruLang() {
        assertEquals("insight_2026-05-13_ru_7", cacheKey("2026-05-13", "ru", 7))
    }

    @Test
    fun cacheKey_differsByPeriod() {
        assertNotEquals(cacheKey("2026-05-13", "ru", 7), cacheKey("2026-05-13", "ru", 30))
    }

    // Finding 1: buildNightDrainMetric() hard-coded a 7/14-day window regardless of the
    // caller's periodDays, so a 30-day insight could carry a weekly night-idle row. Proves
    // getDisplayInsight(30) queries idleDrainDao.getSince() with 30/60-day timestamps, not 7/14.
    @Test
    fun `getDisplayInsight for a 30-day period queries the night-idle window at 30-60 days, not 7-14`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        // BYDMateApp.onCreate() -> bootstrapLocale() auto-detects and persists a language on
        // first run, so currentLanguage() would not fall back to its "ru" default here -- pin
        // it explicitly so the cache key below is deterministic.
        com.bydmate.app.data.local.LocalePreferences(ctx).setLanguage("ru")
        val sinceCalls = mutableListOf<Long>()

        val idleDrainDao = mockk<IdleDrainDao>(relaxed = true)
        coEvery { idleDrainDao.getSince(any()) } answers {
            sinceCalls.add(firstArg())
            emptyList()
        }

        val manager = InsightsManager(
            context = ctx,
            openRouterClient = OpenRouterClient(OkHttpClient()),
            tripDao = mockk<TripDao>(),
            idleDrainDao = idleDrainDao,
            chargeDao = mockk<ChargeDao>(),
            settingsRepository = mockk<SettingsRepository>(),
        )

        // Seed today's cache for periodDays=30 with no night_idle row yet, so
        // getDisplayInsight() takes the enrichLocalInsight() path directly.
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        ctx.getSharedPreferences("insights_cache", Context.MODE_PRIVATE).edit()
            .putString(
                InsightsManager.cacheKey(today, "ru", 30),
                """{"title":"t","summary":"s","tone":"good","dynamics":[],"insights":[]}""",
            )
            .apply()

        val before = System.currentTimeMillis()
        manager.getDisplayInsight(30)

        val cal = Calendar.getInstance()
        cal.timeInMillis = before
        cal.add(Calendar.DAY_OF_YEAR, -30)
        val expectedCurrent = cal.timeInMillis
        cal.timeInMillis = before
        cal.add(Calendar.DAY_OF_YEAR, -60)
        val expectedPrevious = cal.timeInMillis

        assertEquals(2, sinceCalls.size)
        assertTrue(sinceCalls.any { kotlin.math.abs(it - expectedCurrent) < 5_000L })
        assertTrue(sinceCalls.any { kotlin.math.abs(it - expectedPrevious) < 5_000L })
    }

    private fun cacheKey(date: String, lang: String, periodDays: Int): String {
        val method = InsightsManager::class.java.getDeclaredMethod(
            "cacheKey",
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        return method.invoke(null, date, lang, periodDays) as String
    }
}
