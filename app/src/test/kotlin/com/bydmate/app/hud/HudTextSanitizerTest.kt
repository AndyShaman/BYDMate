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

    @Test fun `Russian letters read as a driver spells them`() {
        assertEquals("Shchukina", HudTextSanitizer.sanitize("Щукина"))
        assertEquals("Zhukova", HudTextSanitizer.sanitize("Жукова"))
        assertEquals("Yaroslava Gasheka", HudTextSanitizer.sanitize("Ярослава Гашека"))
        assertEquals("Obezdnaya", HudTextSanitizer.sanitize("Объездная"))
        assertEquals("Leningradskiy pr.", HudTextSanitizer.sanitize("Ленинградский пр."))
        assertEquals("Yolkina Khvoynaya Tsentralnaya Chekhova Ekspo", HudTextSanitizer.sanitize("Ёлкина Хвойная Центральная Чехова Экспо"))
    }

    @Test fun `Belarusian letters and the apostrophe`() {
        assertEquals("vulitsa Kirava", HudTextSanitizer.sanitize("вуліца Кірава"))
        assertEquals("Vulitsa Pawlyuka", HudTextSanitizer.sanitize("Вуліца Паўлюка"))
        assertEquals("Padezd", HudTextSanitizer.sanitize("Пад’езд"))
        // Outside Cyrillic the apostrophe is ICU's as before.
        assertEquals("O'Neill", HudTextSanitizer.sanitize("O’Neill"))
    }

    @Test fun `an all-caps word stays all caps`() {
        assertEquals("ZHUKOVA", HudTextSanitizer.sanitize("ЖУКОВА"))
        // A lone capital has no caps neighbour to follow: it is an initial, only its first letter is capital.
        assertEquals("Shch.", HudTextSanitizer.sanitize("Щ."))
        assertEquals("ul. SHCHORSA", HudTextSanitizer.sanitize("ул. ЩОРСА"))
    }

    @Test fun `blank is empty and the result is trimmed`() {
        assertEquals("", HudTextSanitizer.sanitize(""))
        assertEquals("", HudTextSanitizer.sanitize("   "))
        assertEquals("Lenina", HudTextSanitizer.sanitize(" Ленина "))
    }
}
