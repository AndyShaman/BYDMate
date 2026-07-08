package com.bydmate.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun `two sentences split on terminator plus space`() {
        val c = SentenceChunker()
        val out = c.feed("Заряд 80 процентов. Хватит до Минска.")
        assertEquals(listOf("Заряд 80 процентов."), out)
        assertEquals("Хватит до Минска.", c.flush())
    }

    @Test
    fun `decimal number does not split`() {
        val c = SentenceChunker()
        val out = c.feed("Температура 22.5 градуса. Тепло.")
        assertEquals(listOf("Температура 22.5 градуса."), out)
        assertEquals("Тепло.", c.flush())
    }

    @Test
    fun `char by char feeding emits sentence once boundary is complete`() {
        val c = SentenceChunker()
        val collected = mutableListOf<String>()
        "Да. Нет.".forEach { ch -> collected += c.feed(ch.toString()) }
        assertEquals(listOf("Да."), collected)
        assertEquals("Нет.", c.flush())
    }

    @Test
    fun `newline is a boundary`() {
        val c = SentenceChunker()
        val out = c.feed("Первая строка\nВторая строка")
        assertEquals(listOf("Первая строка"), out)
        assertEquals("Вторая строка", c.flush())
    }

    @Test
    fun `terminator run stays together`() {
        val c = SentenceChunker()
        val out = c.feed("Правда?! Да. ")
        assertEquals(listOf("Правда?!", "Да."), out)
        assertNull(c.flush())
    }

    @Test
    fun `ellipsis char is a terminator`() {
        val c = SentenceChunker()
        val out = c.feed("Подожди… Сейчас проверю.")
        assertEquals(listOf("Подожди…"), out)
        assertEquals("Сейчас проверю.", c.flush())
    }

    @Test
    fun `flush on empty buffer returns null and resets`() {
        val c = SentenceChunker()
        c.feed("Готово. ")
        assertNull(c.flush())
    }
}
