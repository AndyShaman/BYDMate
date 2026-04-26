package com.bydmate.app.data.autoservice

/**
 * Translates raw autoservice Binder return values to typed Kotlin nullables.
 *
 * Sentinels observed on Leopard 3 (see feedback_autoservice_validated.md):
 *   - 0x0000FFFF (65535)   = DEVICE_THE_FEATURE_LINK_ERROR (fid↔CAN link not established)
 *   - 0x000FFFFF (1048575) = 20-bit "not initialized"
 *   - 0xFFFFD8E3 (-10013)  = wrong transact code
 *   - 0xFFFFD8E5 (-10011)  = fid not writable / wrong direction
 *   - 0xBF800000 = -1.0f   = float "not initialized"
 *
 * NOTE: transact 7 returns 4-byte IEEE 754 FLOAT (not double). Parse via
 * Float.intBitsToFloat — see parseFloatFromShellInt.
 */
object SentinelDecoder {

    private const val FEATURE_LINK_ERROR = 0x0000FFFF
    private const val NOT_INITIALIZED_20BIT = 0x000FFFFF
    private const val WRONG_TRANSACT = -10013
    private const val WRONG_DIRECTION = -10011

    fun decodeInt(raw: Int): Int? = when (raw) {
        FEATURE_LINK_ERROR,
        NOT_INITIALIZED_20BIT,
        WRONG_TRANSACT,
        WRONG_DIRECTION -> null
        else -> raw
    }

    fun decodeFloat(raw: Float): Float? = when {
        raw.isNaN() -> null
        raw.isInfinite() -> null
        raw == -1.0f -> null
        else -> raw
    }

    /**
     * `service call autoservice 7 i32 <dev> i32 <fid>` returns a 32-bit value
     * encoded as a hex int by the shell wrapper. The bytes are the IEEE 754
     * representation of a Float.
     */
    fun parseFloatFromShellInt(rawBits: Int): Float? =
        decodeFloat(java.lang.Float.intBitsToFloat(rawBits))
}
