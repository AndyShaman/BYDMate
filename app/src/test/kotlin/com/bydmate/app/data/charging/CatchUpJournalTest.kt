package com.bydmate.app.data.charging

import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchUpJournalTest {

    private class FakeSettingsDao :
        com.bydmate.app.data.local.dao.SettingsDao {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private fun journal(): CatchUpJournal =
        CatchUpJournal(SettingsRepository(FakeSettingsDao(), mockk(relaxed = true)))

    @Test
    fun `append stores entries in order with timestamps`() = runTest {
        val j = journal()
        j.append("SENTINEL", now = 1_000_000L)
        j.append("SESSION_CREATED kwh=1.50", now = 2_000_000L)

        val lines = j.read().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("SENTINEL"))
        assertTrue(lines[1].endsWith("SESSION_CREATED kwh=1.50"))
        // 19-char timestamp prefix + space
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} """).containsMatchIn(lines[0]))
    }

    @Test
    fun `consecutive identical payloads collapse into one line with counter`() = runTest {
        val j = journal()
        j.append("STILL_CHARGING gun=2", now = 1_000_000L)
        j.append("STILL_CHARGING gun=2", now = 2_000_000L)
        j.append("STILL_CHARGING gun=2", now = 3_000_000L)

        val lines = j.read().lines()
        assertEquals(1, lines.size)
        assertTrue(lines[0].endsWith("STILL_CHARGING gun=2 (x3)"))
    }

    @Test
    fun `different payload breaks the collapse run`() = runTest {
        val j = journal()
        j.append("STILL_CHARGING gun=2", now = 1_000_000L)
        j.append("STILL_CHARGING gun=2", now = 2_000_000L)
        j.append("SESSION_CREATED kwh=30.00", now = 3_000_000L)
        j.append("STILL_CHARGING gun=2", now = 4_000_000L)

        val lines = j.read().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("STILL_CHARGING gun=2 (x2)"))
        assertTrue(lines[1].endsWith("SESSION_CREATED kwh=30.00"))
        assertTrue(lines[2].endsWith("STILL_CHARGING gun=2"))
    }

    @Test
    fun `ring trims to MAX_ENTRIES dropping oldest`() = runTest {
        val j = journal()
        repeat(CatchUpJournal.MAX_ENTRIES + 5) { i ->
            j.append("NO_DELTA soc=$i", now = 1_000_000L + i)
        }

        val lines = j.read().lines()
        assertEquals(CatchUpJournal.MAX_ENTRIES, lines.size)
        assertTrue(lines.first().endsWith("NO_DELTA soc=5"))
        assertTrue(lines.last().endsWith("NO_DELTA soc=${CatchUpJournal.MAX_ENTRIES + 4}"))
    }

    @Test
    fun `empty journal reads as blank`() = runTest {
        assertEquals("", journal().read())
    }
}
