package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Отправляет ту же телеметрию, что уходит в ABRP (см.
 * [IternioTelemetryClient.buildTelemetry]), POST-запросом на произвольный URL
 * пользователя.
 *
 * Никаких очередей и ретраев: телеметрия живая, пропущенный сэмпл
 * бессмысленно досылать через минуту — следующий тик всё равно свежее.
 * Пауза после ошибки — забота вызывающего ([com.bydmate.app.service.TrackingService]).
 */
@Singleton
class WebhookTelemetryClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "WebhookTelemetry"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** https anywhere; plain http only on loopback (the only cleartext hosts the app allows). */
        fun isAllowedWebhookUrl(url: HttpUrl): Boolean =
            url.scheme == "https" ||
                (url.scheme == "http" && (url.host == "localhost" || url.host == "127.0.0.1"))
    }

    /**
     * @param url Адрес вебхука. Принимаем https, а http — только на localhost/127.0.0.1:
     *            network_security_config.xml разрешает cleartext лишь для loopback,
     *            любой другой http-хост платформа молча заблокирует.
     * @param secret Необязательный секрет; уходит как `Authorization: Bearer`.
     *               Никогда не попадает в лог.
     * @param telemetry Готовый JSON — тот же объект, что уходит в Iternio.
     */
    suspend fun send(url: String, secret: String?, telemetry: JSONObject): Result<Unit> =
        withContext(Dispatchers.IO) {
            val httpUrl = url.trim().toHttpUrlOrNull()
            if (httpUrl == null || !isAllowedWebhookUrl(httpUrl)) {
                return@withContext Result.failure(IllegalArgumentException("неверный URL вебхука"))
            }
            try {
                val request = Request.Builder()
                    .url(httpUrl)
                    .post(telemetry.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .apply {
                        secret?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            header("Authorization", "Bearer $it")
                        }
                    }
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Never log the body: a user endpoint may echo the secret back.
                        Log.w(TAG, "HTTP ${response.code}")
                        return@withContext Result.failure(IllegalStateException("HTTP ${response.code}"))
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "отправка не удалась: ${e.message}")
                Result.failure(e)
            }
        }
}
