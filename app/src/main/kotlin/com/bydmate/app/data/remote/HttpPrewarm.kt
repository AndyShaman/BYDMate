package com.bydmate.app.data.remote

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Opens a pooled connection to a host ahead of the first real request. A cold voice turn
 * otherwise pays DNS + TCP + TLS inside the reply latency (the pool keeps an idle connection
 * only 5 minutes, and questions in the car are usually rarer). One fire-and-forget HEAD on
 * OkHttp's own dispatcher; any status is fine, the response is discarded. Clients built with
 * newBuilder() share the pool, so the warmed connection serves them too.
 */
object HttpPrewarm {
    private const val TAG = "HttpPrewarm"

    fun fire(http: OkHttpClient, url: String) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val startNs = System.nanoTime()
        val request = Request.Builder().url(parsed).head().build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "prewarm failed: host=${parsed.host} ${e.javaClass.simpleName}: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                val ms = (System.nanoTime() - startNs) / 1_000_000
                Log.i(TAG, "prewarm: host=${parsed.host} code=${response.code} ms=$ms")
            }
        })
    }
}
