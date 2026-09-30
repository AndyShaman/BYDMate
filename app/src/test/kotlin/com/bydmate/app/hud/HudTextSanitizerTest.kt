package com.bydmate.app.hud

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** OpenBYD 2.5's HudTextSanitizer: what the instrument's CAN road name gets instead of Cyrillic. */
@RunWith(RobolectricTestRunner::class)
class HudTextSanitizerTest {

    @Test fun `Cyrillic becomes plain Latin`() {
        assertEquals("Prospekt Nezavisimosti", HudTextSanitizer.sanitize("Проспект Независимости"))
    }

    @Test fun `Latin with digits stays as it is`() {
        assertEquals("M1 Minsk-Brest 42", HudTextSanitizer.sanitize("M1 Minsk-Brest 42"))
    }

    @Test fun `diacritics lose their marks`() {
        assertEquals("Ulica Swietokrzyska", HudTextSanitizer.sanitize("Ulica Świętokrzyska"))
    }

    @Test fun `Chinese characters are kept`() {
        assertEquals("长安街", HudTextSanitizer.sanitize("长安街"))
    }

    @Test fun `blank is empty and the result is trimmed`() {
        assertEquals("", HudTextSanitizer.sanitize(""))
        assertEquals("", HudTextSanitizer.sanitize("   "))
        assertEquals("Lenina", HudTextSanitizer.sanitize(" Ленина "))
    }
}
