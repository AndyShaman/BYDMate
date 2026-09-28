@file:Suppress("MatchingDeclarationName") // the HTTP helpers shared by the map clients, not a file for one class

package com.bydmate.app.agent

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONException
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A server answered, but with nothing usable. The message is a fixed reason code ("HTTP 503",
 *  "bad JSON"), safe for a user-shared log: never a URL, coordinates or a place name. */
internal class MapBadAnswer(reason: String) : IOException(reason)

/** For a per-server log line: the reason code of a [MapBadAnswer], else the exception class. */
internal fun mapOutcome(e: IOException): String = if (e is MapBadAnswer) e.message.orEmpty() else e.javaClass.simpleName

internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/**
 * Runs the call on OkHttp's thread pool and cancels it with the coroutine (a blocking execute()
 * would keep a cancelled voice turn waiting for the whole call timeout). [read] consumes a 2xx
 * answer on the OkHttp thread, so parsing a large body never touches the caller's thread; any
 * failure inside it, a malformed body included, ends as an IOException, never as a crash of
 * that thread.
 */
internal suspend fun <T> Call.awaitRead(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        // Android's org.json JSONException is a checked Exception, the JVM library's a
        // RuntimeException: both are caught by name, and any other runtime surprise of a
        // malformed body lands here too instead of killing OkHttp's thread.
        @Suppress("TooGenericExceptionCaught")
        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { resp ->
                    if (!resp.isSuccessful) throw MapBadAnswer("HTTP ${resp.code}")
                    read(resp)
                }
            } catch (e: IOException) {
                cont.resumeWithException(e)
                return
            } catch (_: JSONException) {
                cont.resumeWithException(MapBadAnswer("bad JSON"))
                return
            } catch (_: RuntimeException) {
                cont.resumeWithException(MapBadAnswer("bad answer"))
                return
            }
            cont.resume(result)
        }
    })
}
