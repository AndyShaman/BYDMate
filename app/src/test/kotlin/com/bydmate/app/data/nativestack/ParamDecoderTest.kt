package com.bydmate.app.data.nativestack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParamDecoderTest {

    @Test fun `int_raw passes through`() {
        assertEquals(42, ParamDecoder.decodeInt(42, Decoder.INT_RAW))
    }

    @Test fun `int_div10 produces mileage km`() {
        assertEquals(1234.5, ParamDecoder.decodeFloat(12345, Decoder.INT_DIV10)!!, 0.001)
    }

    @Test fun `float_volt decodes float bits`() {
        // 12.6f → 0x4149999A
        val bits = java.lang.Float.floatToRawIntBits(12.6f)
        assertEquals(12.6f.toDouble(), ParamDecoder.decodeFloat(bits, Decoder.FLOAT_VOLT)!!, 0.001)
    }

    @Test fun `sentinel int returns null`() {
        assertNull(ParamDecoder.decodeInt(-10011, Decoder.INT_RAW))
        assertNull(ParamDecoder.decodeInt(0xFFFFD8E5.toInt(), Decoder.INT_RAW))
    }

    @Test fun `int_percent clamps invalid range`() {
        assertEquals(50, ParamDecoder.decodeInt(50, Decoder.INT_PERCENT))
        assertNull(ParamDecoder.decodeInt(200, Decoder.INT_PERCENT))
    }

    @Test fun `int_temp_c plausible range`() {
        assertEquals(-20, ParamDecoder.decodeInt(-20, Decoder.INT_TEMP_C))
        assertEquals(60, ParamDecoder.decodeInt(60, Decoder.INT_TEMP_C))
        assertNull(ParamDecoder.decodeInt(200, Decoder.INT_TEMP_C))
    }

    @Test fun `int_temp_c_ofs40 subtracts 40 within plausible range`() {
        // Battery temp fids (dev=1014) encode °C with a -40 CAN offset.
        // raw 51 → 11°C, raw 50 → 10°C (matches D+ 10/11/11 and competitor offsets).
        assertEquals(11, ParamDecoder.decodeInt(51, Decoder.INT_TEMP_C_OFS40))
        assertEquals(10, ParamDecoder.decodeInt(50, Decoder.INT_TEMP_C_OFS40))
        assertEquals(16, ParamDecoder.decodeInt(56, Decoder.INT_TEMP_C_OFS40))
        // 200 → 160 out of plausible decoded range → null
        assertNull(ParamDecoder.decodeInt(200, Decoder.INT_TEMP_C_OFS40))
        // sentinel rejected before offset
        assertNull(ParamDecoder.decodeInt(-10011, Decoder.INT_TEMP_C_OFS40))
    }

    @Test fun `int_scaled applies scale factor`() {
        assertEquals(1234.5, ParamDecoder.decodeScaled(12345, 0.1)!!, 0.001)
        assertEquals(3.456, ParamDecoder.decodeScaled(3456, 0.001)!!, 0.0001)
        assertNull(ParamDecoder.decodeScaled(-10011, 0.1))
    }
}
