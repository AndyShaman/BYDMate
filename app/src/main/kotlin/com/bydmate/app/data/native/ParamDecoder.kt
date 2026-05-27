package com.bydmate.app.data.native

import com.bydmate.app.data.autoservice.SentinelDecoder

enum class Decoder {
    INT_RAW,
    INT_DIV10,
    INT_PERCENT,
    INT_ENUM,
    INT_TEMP_C,
    INT_KPA,
    FLOAT_VOLT,
    FLOAT_PERCENT,
    FLOAT_KW,
    FLOAT_KWH,
    STATIC_USER_SETTING,
}

object ParamDecoder {

    fun decodeInt(rawInt: Int, decoder: Decoder): Int? {
        val cleaned = SentinelDecoder.decodeInt(rawInt) ?: return null
        return when (decoder) {
            Decoder.INT_RAW, Decoder.INT_ENUM, Decoder.INT_KPA -> cleaned
            Decoder.INT_PERCENT -> cleaned.takeIf { it in 0..100 }
            Decoder.INT_TEMP_C -> cleaned.takeIf { it in -50..80 }
            // INT_DIV10 is a float-producing decoder — callers should use decodeFloat
            Decoder.INT_DIV10 -> cleaned
            else -> null
        }
    }

    fun decodeFloat(rawInt: Int, decoder: Decoder): Double? {
        return when (decoder) {
            Decoder.INT_DIV10 -> {
                val v = SentinelDecoder.decodeInt(rawInt) ?: return null
                v / 10.0
            }
            Decoder.FLOAT_VOLT, Decoder.FLOAT_PERCENT,
            Decoder.FLOAT_KW, Decoder.FLOAT_KWH -> {
                val f = SentinelDecoder.parseFloatFromShellInt(rawInt) ?: return null
                f.toDouble()
            }
            else -> null
        }
    }
}
