package com.bydmate.app.data.backup

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why a Telegram Bot API call failed; the UI turns it into a localized text. */
enum class TelegramError { BAD_TOKEN, NO_NETWORK, NO_CHAT, TOO_LARGE, WEBHOOK, BAD_RESPONSE, HTTP }

/**
 * A failed Telegram Bot API call. [httpCode] is null when the request never got an HTTP answer.
 * [transient] = worth retrying later (no network, garbled answer, rate limit, server error); a bad
 * token or another 4xx answer will not heal by itself.
 */
class TelegramSinkException(val error: TelegramError, val httpCode: Int? = null, cause: Throwable? = null) :
    IOException("$error${httpCode?.let { " $it" }.orEmpty()}", cause) {
    val transient: Boolean
        get() = when (error) {
            TelegramError.NO_NETWORK, TelegramError.BAD_RESPONSE -> true
            TelegramError.HTTP -> httpCode == HTTP_TOO_MANY_REQUESTS || (httpCode ?: 0) >= HTTP_SERVER_ERROR
            else -> false
        }

    /** Stable key for settings and the dump: `BAD_TOKEN`, `HTTP:502`, … */
    val key: String get() = if (error == TelegramError.HTTP) "HTTP:$httpCode" else error.name

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}

/** The private chat the bot will send to, with a display name so the owner sees who gets the backups. */
data class TelegramChat(val id: Long, val name: String)

/** The stored bot binding (#237); [configured] = there is a chat to send to. */
data class TgBackupConfig(val token: String, val chatId: Long?, val botName: String, val chatName: String) {
    val configured: Boolean get() = token.isNotBlank() && chatId != null
}

/**
 * Delivers backups to the user's own Telegram bot chat through the Bot API (#237).
 * The token never reaches the log: URLs are logged with the token masked. Calls are cancellable:
 * a cancelled coroutine (CoroutineWorker's 10 min limit on a slow modem) cancels the HTTP call.
 */
@Singleton
class TelegramBackupSink internal constructor(
    httpClient: OkHttpClient,
    private val baseUrl: String,
) {
    @Inject constructor(httpClient: OkHttpClient) : this(httpClient, DEFAULT_BASE_URL)

    companion object {
        private const val TAG = "AutoBackup"
        const val DEFAULT_BASE_URL = "https://api.telegram.org"
        /** Bot API upload limit for sendDocument. */
        const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024
        private const val UPDATES_PAGE = 100
        private const val HTTP_CONFLICT = 409
        /** `["message"]`, pre-encoded: other update kinds only crowd the page. */
        private const val ALLOWED_UPDATES = "%5B%22message%22%5D"
        private val ZIP_MEDIA = "application/zip".toMediaType()
    }

    // Uploads of a multi-MB zip over a car modem need far more than the shared 15 s write timeout.
    private val client = httpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Bot username (without @) for the given token. */
    suspend fun getMe(token: String): Result<String> =
        call(token, "getMe", null) { it.getJSONObject("result").getString("username") }

    /**
     * The private chat whose latest message is [code]; null when no chat sent it. Binding by a
     * one-time code, not by "whoever wrote last", keeps a stranger who found the bot from getting
     * the backups. A negative offset reads the last page of the queue instead of the oldest one.
     */
    suspend fun findPrivateChat(token: String, code: String): Result<TelegramChat?> =
        call(token, "getUpdates?offset=-$UPDATES_PAGE&limit=$UPDATES_PAGE&allowed_updates=$ALLOWED_UPDATES", null) { json ->
            val updates = json.getJSONArray("result")
            val seenChats = mutableSetOf<Long>()
            (updates.length() - 1 downTo 0)
                .asSequence()
                .mapNotNull { updates.getJSONObject(it).optJSONObject("message") }
                .filter { it.optJSONObject("chat")?.optString("type") == "private" }
                // Newest first: only the first message seen per chat is its latest one.
                .filter { seenChats.add(it.getJSONObject("chat").getLong("id")) }
                .firstOrNull { it.optString("text").trim() == code }
                ?.getJSONObject("chat")
                ?.let { TelegramChat(it.getLong("id"), chatName(it)) }
        }

    suspend fun sendMessage(token: String, chatId: Long, text: String): Result<Unit> {
        val body = FormBody.Builder()
            .add("chat_id", chatId.toString())
            .add("text", text)
            .build()
        return call(token, "sendMessage", body) { }
    }

    suspend fun sendDocument(token: String, chatId: Long, file: File, caption: String): Result<Unit> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("caption", caption)
            .addFormDataPart("document", file.name, file.asRequestBody(ZIP_MEDIA))
            .build()
        return call(token, "sendDocument", body) { }
    }

    /** "Имя @username", either part may be missing. */
    private fun chatName(chat: JSONObject): String = listOfNotNull(
        chat.optString("first_name").takeIf { it.isNotEmpty() },
        chat.optString("username").takeIf { it.isNotEmpty() }?.let { "@$it" },
    ).joinToString(" ")

    /** GET when [body] is null, POST otherwise; maps every failure to a [TelegramSinkException]. */
    private suspend fun <T> call(
        token: String,
        method: String,
        body: RequestBody?,
        parse: (JSONObject) -> T,
    ): Result<T> {
        val maskedUrl = "$baseUrl/bot***/${method.substringBefore('?')}"
        val request = Request.Builder().url("$baseUrl/bot$token/$method").apply {
            if (body != null) post(body)
        }.build()
        val (code, text) = try {
            await(client.newCall(request))
        } catch (e: IOException) {
            Log.w(TAG, "telegram rc=io ${e.javaClass.simpleName} ($maskedUrl)")
            return Result.failure(TelegramSinkException(TelegramError.NO_NETWORK))
        }
        if (code !in 200..299) {
            val failure = httpFailure(code, text)
            Log.w(TAG, "telegram rc=$code ${failure.key} ($maskedUrl)")
            return Result.failure(failure)
        }
        return try {
            val json = JSONObject(text)
            apiFailure(json)?.let { failure ->
                Log.w(TAG, "telegram rc=$code ${failure.key} ($maskedUrl)")
                return Result.failure(failure)
            }
            val parsed = parse(json)
            Log.i(TAG, "telegram rc=ok $maskedUrl")
            Result.success(parsed)
        } catch (e: JSONException) {
            // No exception text: it may quote the answer body.
            Log.w(TAG, "telegram rc=$code ${TelegramError.BAD_RESPONSE} ($maskedUrl)")
            Result.failure(TelegramSinkException(TelegramError.BAD_RESPONSE, cause = e))
        }
    }

    /** A 2xx answer that is still not a success: `ok` false (mapped by its error_code) or no result. */
    private fun apiFailure(json: JSONObject): TelegramSinkException? {
        val errorCode = json.optInt("error_code")
        return when {
            !json.optBoolean("ok") && errorCode > 0 -> httpFailure(errorCode, json.toString())
            !json.optBoolean("ok") || !json.has("result") -> TelegramSinkException(TelegramError.BAD_RESPONSE)
            else -> null
        }
    }

    /**
     * Runs [call] on OkHttp's thread pool and cancels it with the coroutine. The body is read in the
     * callback, so callers on the main thread never touch the socket.
     */
    private suspend fun await(call: Call): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to it.body?.string().orEmpty() }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(result)
            }
        })
    }

    private fun httpFailure(code: Int, text: String): TelegramSinkException {
        val description = runCatching { JSONObject(text).optString("description") }.getOrDefault("")
        val error = when {
            code == 401 || code == 404 -> TelegramError.BAD_TOKEN
            code == 400 && description.contains("chat not found", ignoreCase = true) -> TelegramError.NO_CHAT
            code == 413 -> TelegramError.TOO_LARGE
            code == HTTP_CONFLICT -> TelegramError.WEBHOOK
            else -> TelegramError.HTTP
        }
        return TelegramSinkException(error, code)
    }
}
