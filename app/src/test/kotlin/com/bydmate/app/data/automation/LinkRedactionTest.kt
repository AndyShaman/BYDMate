package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
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
    }

    @Test fun `dial label drops the number`() {
        assertEquals("dial:<phone>", log("dial:+375291234567"))
        assertEquals("dial:<phone>", log("dial:+375 (29) 123-45-67"))    }

    @Test fun `navigate labels drop the coordinates`() {
        assertEquals("navigate:<coords>", log("navigate:53.9045,27.5615"))
        assertEquals("navigate_show:<coords>", log("navigate_show:-33.86, 151.2"))
        assertEquals("navigate_maps:<coords>", log("navigate_maps:53.9045,27.5615"))
        assertEquals("navigate_maps_show:<coords> (Дача)", log("navigate_maps_show:53.9045,27.5615 (Дача)"))
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
            "dgis://2gis.ru/routeSearch/rsType/car/to/<coords>",
            log("dgis://2gis.ru/routeSearch/rsType/car/to/27.5615,53.9045"),
        )
    }

    @Test fun `geo and tel links and intent data are covered too`() {
        assertEquals("geo:<coords>?<redacted>", log("geo:53.9045,27.5615?q=home"))
        assertEquals("tel:<phone>", log("tel:+375291234567"))
        assertEquals(
            "Intent { act=VIEW dat=https://api.example.com/x?<redacted> }",
            log("Intent { act=VIEW dat=https://api.example.com/x?token=SECRET }"),
        )
    }

    @Test fun `text without a link, number or coordinates is unchanged`() {
        assertEquals("navigate_shortcut:home", log("navigate_shortcut:home"))
        assertEquals("app_launch:ru.yandex.music", log("app_launch:ru.yandex.music"))
    }

    @Test fun `the dump still hides a link whole`() {
        assertEquals("fail: <uri>", LinkRedaction.redactAll("fail: https://api.example.com/hook?token=SECRET"))
    }
}
