package com.bydmate.app.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class HudRoadScriptTest {

    private fun classify(text: String) = HudRoadScript.classify(text)

    @Test fun `scripts`() {
        assertEquals("cyrillic", classify("ул. Ленина, 12"))
        assertEquals("latin", classify("Main St"))
        assertEquals("latin", classify("Rue de l'Église"))
        assertEquals("mixed", classify("ул. Ленина | 00:12 min"))
        assertEquals("other", classify("长安街"))
        assertEquals("mixed", classify("长安街 Road"))
        assertEquals("other", classify("�� 12"))
        assertEquals("mixed", classify("Main "))
    }

    @Test fun `blank is empty, digits and punctuation alone read as latin`() {
        assertEquals("empty", classify(""))
        assertEquals("empty", classify(" "))
        assertEquals("latin", classify("55-1"))
        assertEquals("cyrillic", classify("М-1"))
    }
}
