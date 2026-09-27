package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Log lines keep the scheme, host and path of a link; tokens, numbers and coordinates go. */
class LinkRedactionTest {

    private fun log(text: String) = LinkRedaction.forLog(text)

    @Test fun `url label keeps scheme host and path, drops the query and fragment`() {
        assertEquals(
            "url:https://api.example.com/hook?<redacted>",
            log("url:https://api.example.com/hook?token=SECRET123&chat=42#frag"),
        )
        assertEquals("url:https://example.com/page", log("url:https://example.com/page#anchor"))
        assertEquals("url:https://example.com:8443/page", log("url:https://example.com:8443/page"))
    }

    @Test fun `user info in a link is dropped`() {
        assertEquals("https://example.com/path?<redacted>", log("https://alice:secret@example.com/path?token=abc"))
        assertEquals("url:https://example.com/path?<redacted>", log("url:https://alice:secret@example.com/path?token=abc"))
    }

    @Test fun `dial label drops the number whatever its length`() {
        assertEquals("dial:<phone>", log("dial:+375291234567"))
        assertEquals("dial:<phone>", log("dial:+375 (29) 123-45-67"))
        assertEquals("dial:<phone>", log("dial:123456"))
    }

    @Test fun `navigate labels drop the coordinates`() {
        assertEquals("navigate:<coords>", log("navigate:53.9045,27.5615"))
        assertEquals("navigate_show:<coords>", log("navigate_show:-33.86, 151.2"))
        assertEquals("navigate_maps:<coords>", log("navigate_maps:53.9045,27.5615"))
        assertEquals("navigate_maps_show:<coords> (name len=4)", log("navigate_maps_show:53.9045,27.5615 (Дача)"))
    }

    @Test fun `navigate labels drop coordinates in exponent form`() {
        assertEquals("navigate:<coords>", log("navigate:1.0E-4,27.5615"))
        assertEquals("navigate_maps_show:<coords> (name len=3)", log("navigate_maps_show:53.9,-1.0E-5 (Дом)"))
    }

    @Test fun `a point name is never logged, whatever it carries`() {
        val name = "https://host/p?token=SECRET и тел +375291234567, дом 53.9,27.5"
        val out = log("navigate_maps_show:53.9,27.5 ($name)")
        assertEquals("navigate_maps_show:<coords> (name len=${name.length})", out)
        assertTrue(out, "SECRET" !in out)
        assertTrue(out, "375291234567" !in out)
        assertTrue(out, "53.9" !in out)
    }

    @Test fun `search labels keep their text`() {
        assertEquals("navigate_search:Минск вокзал", log("navigate_search:Минск вокзал"))
    }

    @Test fun `navigator deep links keep scheme and host or path only`() {
        assertEquals(
            "yandexnavi://build_route_on_map?<redacted>",
            log("yandexnavi://build_route_on_map?lat_to=53.9045&lon_to=27.5615"),
        )
        assertEquals(
            "yandexnavi://show_point_on_map?<redacted>",
            log("yandexnavi://show_point_on_map?lat=53.9045&lon=27.5615&zoom=14&desc=%D0%94%D0%BE%D0%BC"),
        )
        assertEquals("yandexmaps://maps.yandex.ru/?<redacted>", log("yandexmaps://maps.yandex.ru/?rtext=~53.9045,27.5615&rtt=auto"))
        assertEquals(
            "dgis://2gis.ru/routeSearch/rsType/car/to/<n>",
            log("dgis://2gis.ru/routeSearch/rsType/car/to/27.5615,53.9045"),
        )
        assertEquals("dgis://2gis.ru/geo/<n>", log("dgis://2gis.ru/geo/1.0E-4,53.9045"))
    }

    // Privacy over debug detail: an id is indistinguishable from a coordinate by shape alone, so
    // both are masked. Was "a numeric id in a link path survives" before 3.20.
    @Test fun `a numeric id in a link path is masked too`() {
        assertEquals("dgis://2gis.ru/firm/<n>", log("dgis://2gis.ru/firm/70000001033556712"))
    }

    @Test fun `a percent-encoded coordinate in a link path is masked too`() {
        val out = log("dgis://2gis.ru/geo/27.5615%2C53.9045")
        assertEquals("dgis://2gis.ru/geo/<n>", out)
        assertTrue(out, out.substringAfter("2gis.ru").none { it.isDigit() })
    }

    @Test fun `a plain-word path segment is left alone`() {
        assertEquals("dgis://2gis.ru/routeSearch/rsType", log("dgis://2gis.ru/routeSearch/rsType"))
    }

    @Test fun `geo and tel links and intent data are covered too`() {
        assertEquals("geo:<coords>?<redacted>", log("geo:53.9045,27.5615?q=home"))
        assertEquals("geo:<coords>", log("geo:1.0E-4,27.5615"))
        assertEquals("tel:<phone>", log("tel:+375291234567"))
        assertEquals("tel:<phone>", log("tel:123456"))
        assertEquals(
            "Intent { act=VIEW dat=https://api.example.com/x?<redacted> }",
            log("Intent { act=VIEW dat=https://api.example.com/x?token=SECRET }"),
        )
    }

    @Test fun `a link that does not parse is hidden whole`() {
        assertEquals("open <uri> failed", log("open https://example.com/a|b?token=SECRET failed"))
    }

    @Test fun `a phone number in free text is masked`() {
        assertEquals("Позвонить <phone>", log("Позвонить +375 29 123-45-67"))
    }

    @Test fun `text without a link, number or coordinates is unchanged`() {
        assertEquals("navigate_shortcut:home", log("navigate_shortcut:home"))
        assertEquals("app_launch:ru.yandex.music", log("app_launch:ru.yandex.music"))
    }

    @Test fun `the dump still hides a link whole`() {
        assertEquals("fail: <uri>", LinkRedaction.redactAll("fail: https://api.example.com/hook?token=SECRET"))
    }
}
