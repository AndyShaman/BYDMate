package com.bydmate.app.data.autoservice

import android.util.Log
import javax.inject.Inject

data class Pm25(val inside: Int?, val outside: Int?, val levelIn: Int?, val levelOut: Int?)

/**
 * PM2.5 (dev 1008), read on demand by the «Техника» screen only: most cars have no sensor, so
 * it stays out of the global poll. The two level fids decide first; a value is read only for a
 * side whose level is one of the SDK's grades 1..6 (0 is the SDK's own "invalid").
 */
class Pm25Reader @Inject constructor(private val autoservice: AutoserviceClient) {

    @Volatile private var lastLine: String? = null

    /** null = no sensor (neither level fid answers 1..6). */
    suspend fun read(): Pm25? {
        val levelIn = autoservice.getInt(DEV, LEVEL_IN)?.takeIf { it in LEVELS }
        val levelOut = autoservice.getInt(DEV, LEVEL_OUT)?.takeIf { it in LEVELS }
        if (levelIn == null && levelOut == null) {
            log("pm25 read: no sensor")
            return null
        }
        val inside = levelIn?.let { autoservice.getInt(DEV, VALUE_IN)?.takeIf { it in VALUES } }
        val outside = levelOut?.let { autoservice.getInt(DEV, VALUE_OUT)?.takeIf { it in VALUES } }
        log("pm25 read: in=$inside/$levelIn out=$outside/$levelOut")
        return Pm25(inside, outside, levelIn, levelOut)
    }

    /** Only a result that changed since the last logged one: the screen reads once a minute. */
    private fun log(line: String) {
        if (line == lastLine) return
        lastLine = line
        Log.d(TAG, line)
    }

    companion object {
        private const val TAG = "TechPanel"
        private const val DEV = 1008
        private const val VALUE_IN = 1331691536
        private const val VALUE_OUT = 1331691548
        private const val LEVEL_IN = 1331691568
        private const val LEVEL_OUT = 1331691571
        private val LEVELS = 1..6
        private val VALUES = 0..3000
    }
}
