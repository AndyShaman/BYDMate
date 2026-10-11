package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.SentinelDecoder

enum class Decoder {
    INT_RAW,
    INT_DIV10,
    INT_SCALED,
    INT_PERCENT,
    INT_ENUM,
    INT_TEMP_C,
    INT_TEMP_C_OFS40,
    INT_KPA,
    FLOAT_VOLT,
    FLOAT_PERCENT,
    FLOAT_KW,
    FLOAT_KWH,
    FLOAT_AMP,
    /** Range in km; 2046 is the "no value" word an EV answers on the fuel range. */
    INT_RANGE_KM,
    /** Engine rpm; 8191 is the "no engine" word. */
    INT_RPM,
    /** Litres; zero or negative means the counter is absent. */
    FLOAT_LITERS,
}

object ParamDecoder {

    fun decodeInt(rawInt: Int, decoder: Decoder): Int? {
        val cleaned = SentinelDecoder.decodeInt(rawInt) ?: return null
        return when (decoder) {
            Decoder.INT_RAW, Decoder.INT_ENUM, Decoder.INT_KPA -> cleaned
            Decoder.INT_PERCENT -> cleaned.takeIf { it in 0..100 }
            Decoder.INT_TEMP_C -> cleaned.takeIf { it in -50..80 }
            // Battery temp fids (dev=1014) encode °C with a -40 CAN offset.
            Decoder.INT_TEMP_C_OFS40 -> (cleaned - 40).takeIf { it in -50..80 }
            // INT_DIV10 is a float-producing decoder — callers should use decodeFloat
            Decoder.INT_DIV10 -> cleaned
            Decoder.INT_RANGE_KM -> cleaned.takeIf { it != RANGE_ABSENT }
            Decoder.INT_RPM -> cleaned.takeIf { it != RPM_ABSENT }
            else -> null
        }
    }

    fun decodeScaled(rawInt: Int, scale: Double): Double? {
        val cleaned = SentinelDecoder.decodeInt(rawInt) ?: return null
        return cleaned * scale
    }

    fun decodeFloat(rawInt: Int, decoder: Decoder): Double? {
        return when (decoder) {
            Decoder.INT_DIV10 -> {
                val v = SentinelDecoder.decodeInt(rawInt) ?: return null
                v / 10.0
            }
            Decoder.FLOAT_VOLT, Decoder.FLOAT_PERCENT,
            Decoder.FLOAT_KW, Decoder.FLOAT_KWH, Decoder.FLOAT_AMP -> {
                val f = SentinelDecoder.parseFloatFromShellInt(rawInt) ?: return null
                f.toDouble()
            }
            Decoder.FLOAT_LITERS -> {
                val f = SentinelDecoder.parseFloatFromShellInt(rawInt) ?: return null
                f.toDouble().takeIf { it > 0.0 }
            }
            else -> null
        }
    }

    private const val RANGE_ABSENT = 2046
    private const val RPM_ABSENT = 8191
}
