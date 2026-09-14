package com.bydmate.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Wave 5: the driver comes back to the car an hour later and says "а сколько там до дома было?".
 * The five-minute conversation history is long gone, so today's exchanges have to carry it —
 * and only today's.
 */
class DayMemoryTest {

    private fun memory() = DayMemory(prefs = null)

    private fun at(hour: Int, dayOffset: Int = 0): Long {
        val c = Calendar.getInstance()
        c.set(2026, Calendar.SEPTEMBER, 14, hour, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return c.timeInMillis
    }

    @Test fun an_exchange_from_today_reaches_the_prompt() {
        val m = memory()
        m.record("сколько до дома", "Сто двадцать километров", at(9))
        val block = m.promptBlock(at(18))
        assertTrue(block, block.contains("сколько до дома"))
        assertTrue(block, block.contains("Сто двадцать километров"))
    }

    @Test fun yesterdays_exchange_is_not_context_for_today() {
        val m = memory()
        m.record("поехали домой", "Маршрут построен", at(20, dayOffset = -1))
        assertEquals("", m.promptBlock(at(9)))
    }

    @Test fun only_the_last_few_exchanges_are_kept() {
        val m = memory()
        repeat(DayMemory.MAX_EXCHANGES + 3) { i -> m.record("вопрос $i", "ответ $i", at(9)) }
        assertEquals(DayMemory.MAX_EXCHANGES, m.facts().size)
        val block = m.promptBlock(at(9))
        assertFalse(block, block.contains("вопрос 0"))
        assertTrue(block, block.contains("вопрос ${DayMemory.MAX_EXCHANGES + 2}"))
    }

    @Test fun the_block_stays_within_its_budget() {
        val m = memory()
        repeat(DayMemory.MAX_EXCHANGES) { i -> m.record("в".repeat(200) + i, "о".repeat(200) + i, at(9)) }
        val block = m.promptBlock(at(9))
        assertTrue("${block.length} chars", block.length <= DayMemory.MAX_BLOCK_CHARS + HEADER_SLACK)
        // The newest exchange is the one worth keeping when the budget forces a choice.
        assertTrue(block, block.contains("о".repeat(20) ))
    }

    @Test fun an_empty_answer_is_not_worth_remembering() {
        val m = memory()
        m.record("привет", "   ", at(9))
        assertEquals("", m.promptBlock(at(9)))
    }

    @Test fun forget_all_clears_the_day() {
        val m = memory()
        m.record("сколько до дома", "Сто двадцать", at(9))
        m.forgetAll()
        assertEquals("", m.promptBlock(at(9)))
    }

    private companion object {
        /** The block header sits outside the per-line budget. */
        const val HEADER_SLACK = 60
    }
}
