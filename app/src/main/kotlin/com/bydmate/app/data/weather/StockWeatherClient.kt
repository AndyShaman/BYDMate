package com.bydmate.app.data.weather

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import com.bydmate.app.R
import com.bydmate.app.agent.awaitRead
import com.bydmate.app.util.appLocalizedContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockWeatherClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    class Unavailable : IllegalStateException("stock weather provider unavailable")

    private val weatherHttp = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    fun hasNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh(location: Location, stillEnabled: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!stillEnabled()) return@withContext false
        val resolver = context.contentResolver
        val previous = try { readRow(resolver) } catch (_: SecurityException) { throw Unavailable() }
        val forecast = fetch(FORECAST, location, mapOf(
            "current" to "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility,uv_index",
            "hourly" to "temperature_2m,weather_code,precipitation_probability,wind_speed_10m,wind_direction_10m,is_day",
            "daily" to "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,wind_speed_10m_max,wind_direction_10m_dominant",
            "past_days" to "1", "forecast_days" to "15", "forecast_hours" to "8", "wind_speed_unit" to "kmh",
        ))
        val air = try {
            fetch(AIR_QUALITY, location, mapOf("current" to "european_aqi,pm10,pm2_5"))
        } catch (e: CancellationException) { throw e } catch (_: IOException) { null }
        val localized = context.appLocalizedContext()
        val city = try {
            @Suppress("DEPRECATION")
            runInterruptible { Geocoder(context, localized.resources.configuration.locales[0])
                .getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }?.let {
                    it.locality ?: it.subAdminArea ?: it.adminArea
                }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val payload = StockWeatherPayload.build(forecast, air,
            city?.takeIf { it.isNotBlank() } ?: localized.getString(R.string.stock_weather_current_location),
            System.currentTimeMillis()).toString()
        ensureActive()
        writeProvider(resolver, previous, payload, stillEnabled)
    }

    private suspend fun fetch(endpoint: String, location: Location, parameters: Map<String, String>): JSONObject {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("timezone", "auto")
        parameters.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return weatherHttp.newCall(Request.Builder().url(url.build()).build()).awaitRead { response ->
            val body = response.body ?: throw IOException("empty weather response")
            val source = body.source()
            if (source.request(MAX_BYTES + 1)) throw IOException("weather response too large")
            JSONObject(source.buffer.readUtf8())
        }
    }

    private fun readRow(resolver: ContentResolver, selection: String? = null, args: Array<String>? = null): Row? {
        val cursor = resolver.query(URI, arrayOf("_id", "name"), selection, args, "_id DESC") ?: throw Unavailable()
        return cursor.use {
            if (it.moveToFirst()) Row(it.getLong(0), it.getString(1)) else null
        }
    }

    private fun writeProvider(resolver: ContentResolver, previous: Row?, payload: String, stillEnabled: () -> Boolean): Boolean {
        if (!stillEnabled()) return false
        var written: Row? = null
        var inserted = false
        try {
            val values = ContentValues().apply { put("name", payload) }
            if (previous == null) {
                if (resolver.insert(URI, values) == null) return false
                inserted = true
                written = readRow(resolver, "name=?", arrayOf(payload))
            } else {
                val selection = if (previous.payload == null) "_id=? AND name IS NULL" else "_id=? AND name=?"
                val args = if (previous.payload == null) arrayOf("${previous.id}") else arrayOf("${previous.id}", previous.payload)
                if (resolver.update(URI, values, selection, args) != 1) return false
                written = Row(previous.id, payload)
            }
            if (written == null || !stillEnabled() || readRow(resolver, "_id=?", arrayOf("${written.id}"))?.payload != payload) {
                rollback(resolver, previous, written, inserted, payload)
                return false
            }
            if (!stillEnabled()) {
                rollback(resolver, previous, written, inserted, payload)
                return false
            }
            context.sendBroadcast(Intent(REFRESH_ACTION))
            // The stock launcher's weather card observes this URI on supported DiLink builds.
            runCatching { resolver.notifyChange(Settings.System.getUriFor("time_12_24"), null) }
            return true
        } catch (_: Exception) {
            rollback(resolver, previous, written, inserted, payload)
            return false
        }
    }

    private fun rollback(resolver: ContentResolver, previous: Row?, written: Row?, inserted: Boolean, payload: String) {
        runCatching {
            if (previous != null) {
                resolver.update(URI, ContentValues().apply { put("name", previous.payload) }, "_id=? AND name=?",
                    arrayOf("${previous.id}", payload))
            } else if (inserted) {
                val row = written ?: readRow(resolver, "name=?", arrayOf(payload))
                if (row != null) resolver.delete(URI, "_id=? AND name=?", arrayOf("${row.id}", payload))
            }
        }
    }

    private data class Row(val id: Long, val payload: String?)

    companion object {
        const val PACKAGE = "com.byd.weatherdata"
        const val REFRESH_VIEW = "com.byd.weatherdata:id/iv_refresh"
        const val REFRESH_ACTION = "com.byd.weatherdata.action.THIRD_REFRESH"
        private val URI = Uri.parse("content://com.byd.weatherdata.utils.WeatherContentProvider/weather")
        private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
        private const val AIR_QUALITY = "https://air-quality-api.open-meteo.com/v1/air-quality"
        private const val MAX_BYTES = 2 * 1024 * 1024L
    }
}
