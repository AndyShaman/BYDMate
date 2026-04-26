package com.bydmate.app.data.autoservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentinelDecoderTest {

    @Test
    fun `decodeInt returns null for 0x0000FFFF feature link error`() {
        assertNull(SentinelDecoder.decodeInt(0x0000FFFF))
    }

    @Test
    fun `decodeInt returns null for 0x000FFFFF 20-bit not initialized`() {
        assertNull(SentinelDecoder.decodeInt(0x000FFFFF))
    }

    @Test
    fun `decodeInt returns null for -10013 wrong transact code`() {
        assertNull(SentinelDecoder.decodeInt(-10013))
    }

    @Test
    fun `decodeInt returns null for -10011 fid not writable`() {
        assertNull(SentinelDecoder.decodeInt(-10011))
    }

    @Test
    fun `decodeInt returns the value for normal int`() {
        assertEquals(91, SentinelDecoder.decodeInt(91))
        assertEquals(0, SentinelDecoder.decodeInt(0))
        assertEquals(2091, SentinelDecoder.decodeInt(2091))
    }

    @Test
    fun `decodeInt returns the value for negative non-sentinel int`() {
        assertEquals(-1, SentinelDecoder.decodeInt(-1))
        assertEquals(-100, SentinelDecoder.decodeInt(-100))
    }

    @Test
    fun `decodeFloat returns null for 0xBF800000 minus one not initialized`() {
        // Float.intBitsToFloat(0xBF800000.toInt()) == -1.0f
        assertNull(SentinelDecoder.decodeFloat(-1.0f))
    }

    @Test
    fun `decodeFloat returns null for NaN`() {
        assertNull(SentinelDecoder.decodeFloat(Float.NaN))
    }

    @Test
    fun `decodeFloat returns null for positive infinity`() {
        assertNull(SentinelDecoder.decodeFloat(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `decodeFloat returns null for negative infinity`() {
        assertNull(SentinelDecoder.decodeFloat(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `decodeFloat returns the value for normal float`() {
        assertEquals(91.0f, SentinelDecoder.decodeFloat(91.0f)!!, 0.001f)
        assertEquals(0.0f, SentinelDecoder.decodeFloat(0.0f)!!, 0.001f)
        assertEquals(602.7f, SentinelDecoder.decodeFloat(602.7f)!!, 0.001f)
    }

    @Test
    fun `parseFloatFromShellInt parses IEEE 754 representation correctly`() {
        // 91.0f as int bits = 0x42B60000 = 1119223808
        assertEquals(91.0f, SentinelDecoder.parseFloatFromShellInt(1119223808)!!, 0.001f)
        // 602.7f as int bits = 0x4416ACCD = 1142336717
        assertEquals(602.7f, SentinelDecoder.parseFloatFromShellInt(1142336717)!!, 0.01f)
    }

    @Test
    fun `parseFloatFromShellInt returns null when bits decode to sentinel float`() {
        // 0xBF800000 = -1.0f sentinel
        assertNull(SentinelDecoder.parseFloatFromShellInt(0xBF800000.toInt()))
    }
}
