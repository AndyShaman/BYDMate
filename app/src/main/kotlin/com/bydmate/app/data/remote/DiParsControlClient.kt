package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiParsControlClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DiParsControl"
        private const val BASE_URL = "http://127.0.0.1:8988/api/sendCmd"
        private const val PREFIX = "迪加"
        private val BLOCKED_PATTERNS = listOf("发送CAN", "执行SHELL", "下电")
    }

    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        if (BLOCKED_PATTERNS.any { command.contains(it) }) {
            Log.w(TAG, "Blocked dangerous command: $command")
            return@withContext false
        }

        try {
            val fullCmd = "$PREFIX$command"
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("cmd", fullCmd)
                .build()
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            Log.d(TAG, "sendCmd '$fullCmd' -> $body")

            // D+ always returns {"success":true} even for invalid commands
            val json = body?.let { JSONObject(it) }
            json?.optBoolean("success", false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "sendCmd failed: ${e.message}")
            false
        }
    }
}
