package com.bydmate.app.data.weather

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.location.Location
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class StockWeatherClientTest {
    private lateinit var context: Context
    private lateinit var provider: WeatherProvider
    private val requests = mutableListOf<okhttp3.HttpUrl>()
    private val location get() = Location("gps").apply { latitude = 53.9; longitude = 27.56 }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = WeatherProvider()
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        requests.clear()
    }

    @Test fun `verified provider update refreshes the stock weather card`() = runTest {
        assertTrue(client().refresh(location) { true })
        val payload = JSONObject(provider.payload!!)
        assertEquals(21, payload.getJSONObject("data").getJSONObject("condition").getInt("temperature"))
        assertTrue(provider.selection!!.contains("_id=?"))
        assertEquals("old weather", provider.arguments!![1])
        assertTrue(shadowOf(context as android.app.Application).broadcastIntents
            .any { it.action == REFRESH })
        val forecast = requests.first { it.host == "api.open-meteo.com" }
        assertEquals("8", forecast.queryParameter("forecast_hours"))
        assertEquals("15", forecast.queryParameter("forecast_days"))
        assertEquals("1", forecast.queryParameter("past_days"))
        assertEquals("kmh", forecast.queryParameter("wind_speed_unit"))
    }

    @Test fun `empty provider receives an inserted forecast`() = runTest {
        provider.payload = null
        assertTrue(client().refresh(location) { true })
        assertNotNull(provider.payload)
    }

    @Test fun `failed provider readback restores previous weather and sends no refresh`() = runTest {
        provider.badReadback = true
        assertFalse(client().refresh(location) { true })
        assertEquals("old weather", provider.payload)
        assertFalse(shadowOf(context as android.app.Application).broadcastIntents.any { it.action == REFRESH })
    }

    @Test fun `switching off during the provider write restores previous weather`() = runTest {
        var enabled = true
        provider.afterWrite = { enabled = false }
        assertFalse(client().refresh(location) { enabled })
        assertEquals("old weather", provider.payload)
        assertFalse(shadowOf(context as android.app.Application).broadcastIntents.any { it.action == REFRESH })
    }

    @Test fun `incomplete network data never changes the provider`() = runTest {
        assertTrue(runCatching { client(forecast = "{}").refresh(location) { true } }.isFailure)
        assertEquals("old weather", provider.payload)
    }

    @Test fun `missing air quality does not prevent a weather update`() = runTest {
        assertTrue(client(airCode = 503).refresh(location) { true })
        assertEquals(-1, JSONObject(provider.payload!!).getJSONObject("data")
            .getJSONObject("aqi").getInt("aqivalue"))
    }

    @Test fun `disabled request neither contacts the provider nor the network`() = runTest {
        assertFalse(client().refresh(location) { false })
        assertTrue(requests.isEmpty())
        assertEquals(0, provider.queries)
    }

    @Test fun `approximate permission cannot supply the shared GPS stream`() {
        val app = context as android.app.Application
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(client().hasLocationPermission())
    }

    private fun client(forecast: String = StockWeatherPayloadTest.forecast().toString(), airCode: Int = 200): StockWeatherClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request.url)
            val air = request.url.host == "air-quality-api.open-meteo.com"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (air) airCode else 200).message("test")
                .body((if (air) """{"current":{"european_aqi":24}}""" else forecast)
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        return StockWeatherClient(context, http)
    }

    class WeatherProvider : ContentProvider() {
        var payload: String? = "old weather"
        var selection: String? = null
        var arguments: Array<out String>? = null
        var badReadback = false
        var afterWrite: (() -> Unit)? = null
        var queries = 0
        private var written = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(arrayOf("_id", "name")).apply {
                queries++
                val current = payload
                if (current != null) addRow(arrayOf<Any>(1L, if (badReadback && written) "wrong weather" else current))
            }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            payload = values!!.getAsString("name")
            written = true
            afterWrite?.invoke()
            return Uri.withAppendedPath(uri, "1")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            this.selection = selection
            arguments = selectionArgs
            if (selectionArgs?.size == 2 && payload != selectionArgs[1]) return 0
            payload = values!!.getAsString("name")
            written = true
            afterWrite?.invoke()
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (selectionArgs?.size == 2 && payload != selectionArgs[1]) return 0
            payload = null
            return 1
        }
    }

    companion object {
        const val AUTHORITY = "com.byd.weatherdata.utils.WeatherContentProvider"
        const val REFRESH = "com.byd.weatherdata.action.THIRD_REFRESH"
    }
}
