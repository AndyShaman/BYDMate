package com.bydmate.app.data.nativestack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #429: CAN-offset decoding for hvCurrent (dev=1009 fid=1145045016) on Song Plus EV 2025. */
class HvCurrentTest {

    @Test fun `fromCanInt decodes live CAN ints and rejects out-of-range and sentinel words`() {
        assertEquals(1.8f, HvCurrent.fromCanInt(5018)!!, 0.01f)
        assertEquals(78.8f, HvCurrent.fromCanInt(5788)!!, 0.01f)
        assertEquals(-58.6f, HvCurrent.fromCanInt(4414)!!, 0.01f)
        assertEquals(0.0f, HvCurrent.fromCanInt(5000)!!, 0.01f)
        assertEquals(-49.8f, HvCurrent.fromCanInt(4502)!!, 0.01f)

        assertNull(HvCurrent.fromCanInt(15001))
        assertNull(HvCurrent.fromCanInt(65535))
        assertNull(HvCurrent.fromCanInt(-10011))
        assertNull(HvCurrent.fromCanInt(-10013))
    }

    @Test fun `replays Song Plus EV 429 log probe pairs through fromCanInt`() {
        val fixture = "native-stack-fixtures/hv-current-int-songplus-429.txt"
        val pairs = checkNotNull(javaClass.classLoader?.getResourceAsStream(fixture)) { fixture }
            .bufferedReader()
            .use { it.readLines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                parts[0].toInt() to parts[1].toFloat()
            }

        assertTrue("fixture must include parked/accelerating/regenerating samples", pairs.size >= 5)
        val raws = pairs.map { it.first }.toSet()
        listOf(5018, 5788, 4414, 4502).forEach { expectedRaw ->
            assertTrue("expected log value $expectedRaw in fixture", expectedRaw in raws)
        }

        for ((raw, expectedAmps) in pairs) {
            val decoded = HvCurrent.fromCanInt(raw)
            assertEquals("raw=$raw", expectedAmps, decoded!!, 0.01f)
        }
    }

    @Test fun `fromCanInt rejects raw 0 sent on empty push`() {
        assertNull(HvCurrent.fromCanInt(0))
        assertEquals(1.8f, HvCurrent.fromCanInt(5018)!!, 0.01f)
    }
}
