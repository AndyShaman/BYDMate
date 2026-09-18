package com.bydmate.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The known tags (pano_h, pano_l, apa, byd_apa) come from Leopard 3 / Sea Lion; on a Song with
 * DiLink 4.0 none of them answered (dump 2026-09-18), so the lookup falls back to whatever
 * getValidCameraTag reports. Firmwares return that in every shape, hence this parser.
 */
class AvmCameraProbeTagsTest {

    @Test fun `no answer gives no tags`() {
        assertEquals(emptyList<String>(), parseCameraTags(null))
    }

    @Test fun `a comma string in brackets is a list of tags`() {
        assertEquals(listOf("pano_h", "front"), parseCameraTags("[pano_h, front]"))
    }

    @Test fun `semicolons and pipes separate too`() {
        assertEquals(listOf("apa", "avm", "rear"), parseCameraTags("apa;avm|rear"))
    }

    @Test fun `quotes around a single tag are dropped`() {
        assertEquals(listOf("byd_apa"), parseCameraTags("\"byd_apa\""))
    }

    @Test fun `a blank answer gives no tags`() {
        assertEquals(emptyList<String>(), parseCameraTags("  ,; "))
    }

    @Test fun `an array keeps its order`() {
        assertEquals(listOf("pano_l", "apa"), parseCameraTags(arrayOf("pano_l", " apa ")))
    }

    @Test fun `a list keeps its order and drops nulls`() {
        assertEquals(listOf("pano_h", "apa"), parseCameraTags(listOf("pano_h", null, "apa")))
    }

    @Test fun `repeated tags are reported once`() {
        assertEquals(listOf("apa", "avm"), parseCameraTags("apa, avm, apa"))
    }

    @Test fun `anything else is its own single tag`() {
        assertEquals(listOf("7"), parseCameraTags(7))
    }
}
