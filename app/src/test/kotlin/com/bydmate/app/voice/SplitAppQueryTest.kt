package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** «сплит <приложение>» and its other forms give the app name; anything else gives nothing. */
class SplitAppQueryTest {

    @Test fun `split and the name`() {
        assertEquals("яндекс музыка", SplitAppQuery.name("сплит яндекс музыка"))
        assertEquals("яндекс музыка", SplitAppQuery.name("сплит с яндекс музыка"))
        assertEquals("тикток", SplitAppQuery.name("включи сплит тикток"))
        assertEquals("тикток", SplitAppQuery.name("сплин тикток"))
    }

    @Test fun `split screen with the name`() {
        assertEquals("ютуб", SplitAppQuery.name("раздели экран с ютуб"))
        assertEquals("ютуб", SplitAppQuery.name("разделение экрана с ютуб"))
    }

    @Test fun `add the name to the split`() {
        assertEquals("яндекс музыку", SplitAppQuery.name("добавь яндекс музыку в сплит"))
        assertEquals("яндекс музыку", SplitAppQuery.name("добавь в сплит яндекс музыку"))
        assertEquals("ютуб", SplitAppQuery.name("добавь ютуб в разделение экрана"))
        assertEquals("ютуб", SplitAppQuery.name("добавь ютуб к сплиту"))
    }

    @Test fun `no name or another phrase gives nothing`() {
        assertNull(SplitAppQuery.name("сплит"))
        assertNull(SplitAppQuery.name("включи разделение экрана"))
        assertNull(SplitAppQuery.name("раздели экран"))
        assertNull(SplitAppQuery.name("добавь в сплит"))
        assertNull(SplitAppQuery.name("добавь громкость"))
        assertNull(SplitAppQuery.name("открой тикток"))
    }
}
