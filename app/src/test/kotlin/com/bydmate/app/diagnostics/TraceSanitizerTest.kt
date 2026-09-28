package com.bydmate.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one gate every trace value passes: the trace keeps the cause, never the data. */
class TraceSanitizerTest {

    private fun v(value: Any) = TraceSanitizer.value(value)

    @Test fun `codes, hosts, classes and numbers pass unchanged`() {
        assertEquals("camera", v("camera"))
        assertEquals("where_am_i", v("where_am_i"))
        assertEquals("overpass-api.de", v("overpass-api.de"))
        assertEquals("SSLHandshakeException", v("SSLHandshakeException"))
        assertEquals("com.byd.avc", v("com.byd.avc"))
        assertEquals("com.byd.avc.ui.RadarOverlayWindow", v("com.byd.avc.ui.RadarOverlayWindow"))
        assertEquals("app:com.yandex.navi", v("app:com.yandex.navi"))
        assertEquals("406", v(406))
        assertEquals("493", v(493L))
        assertEquals("true", v(true))
        assertEquals("12.5", v(12.5))
    }

    @Test fun `coordinate pairs are removed in every usual spelling`() {
        assertEquals("<coords>", v("55.751244,37.618423"))
        assertEquals("<coords>", v("55.7512, 37.6184"))
        assertEquals("<coords>", v("-33.868820 151.209290"))
        assertEquals("at_<coords>_now", v("at 55.751;37.618 now"))
    }

    @Test fun `a lone high precision number is removed, a plain measurement is not`() {
        assertEquals("<num>", v(55.751244))
        assertEquals("lat=<num>", v("lat=55.7512"))
        assertEquals("0.25", v("0.25"))
    }

    @Test fun `a url keeps only its host`() {
        assertEquals("nominatim.openstreetmap.org/...", v("https://nominatim.openstreetmap.org/reverse?lat=55.7&lon=37.6"))
        assertEquals("api.open-meteo.com/...", v("https://api.open-meteo.com/v1/forecast?latitude=1&longitude=2"))
        assertEquals("openrouter.ai", v("https://openrouter.ai"))
        assertEquals("host.example/...", v("https://user:pass@host.example:8443/path"))
        assertEquals("maps.example.com/...", v("maps.example.com/search?text=home"))
        assertEquals("build_route_on_map/...", v("yandexnavi://build_route_on_map?lat_to=55.7&lon_to=37.6"))
        assertEquals("geo:...", v("geo:55.75,37.61?q=cafe"))
    }

    @Test fun `phone numbers are removed`() {
        assertEquals("<phone>", v("+7 912 345-67-89"))
        assertEquals("<phone>", v("89123456789"))
        assertEquals("call_<phone>", v("call +375291234567"))
    }

    @Test fun `text a person said or wrote never passes`() {
        assertEquals("<text>", v("закрой окна"))
        assertEquals("<text>", v("Дом"))
        assertEquals("<text>", v("设置温度22"))
        assertEquals("<text>", v("route to Café"))
    }

    @Test fun `whitespace cannot break the key value format`() {
        assertEquals("bad_JSON", v("bad JSON"))
        assertEquals("a_b", v("a \n\t b"))
        assertEquals("-", v(""))
    }

    @Test fun `a long value is capped`() {
        val out = v("x".repeat(500))
        assertEquals(TraceSanitizer.MAX_VALUE_CHARS, out.length)
        assertTrue(out.endsWith("~"))
    }

    @Test fun `redaction happens before the cap, so a cut cannot expose half a coordinate`() {
        val out = v("a".repeat(75) + " 55.751244,37.618423")
        assertFalse(out, out.contains("55.75"))
    }

    @Test fun `keys keep only lowercase letters, digits, dash and underscore`() {
        assertEquals("asr_ms", TraceSanitizer.key("asr_ms"))
        assertEquals("route", TraceSanitizer.key("Route"))
        assertEquals("ab", TraceSanitizer.key("a b=ф"))
        assertEquals("k", TraceSanitizer.key("="))
    }

    @Test fun `every output is plain ascii`() {
        for (input in listOf("закрой", "a\u0000b", "x".repeat(200), "https://ex.com/ü")) {
            assertTrue(input, v(input).all { it.code in 0x21..0x7E })
        }
    }
}
