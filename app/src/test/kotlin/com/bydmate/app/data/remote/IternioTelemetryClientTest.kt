package com.bydmate.app.data.remote

import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IternioTelemetryClientTest {

    /**
     * OkHttp interceptor that captures the outgoing request and returns a synthetic
     * response. Lets us assert on URL, headers, and form body without spinning up
     * a real network or MockWebServer.
     */
    private class CapturingInterceptor(
        private val responseCode: Int = 200,
        private val responseBody: String = """{"status":"ok"}"""
    ) : Interceptor {
        var lastRequest: Request? = null
        var lastFormBody: String? = null
        var lastTlmJson: JSONObject? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            lastRequest = req
            req.body?.let { body ->
                val sink = Buffer()
                body.writeTo(sink)
                lastFormBody = sink.readUtf8().also { sink.close() }
            }
            // Form body is application/x-www-form-urlencoded: token=...&tlm=...
            lastFormBody?.let { form ->
                val tlmRaw = form.split('&').firstOrNull { it.startsWith("tlm=") }
                    ?.substringAfter("tlm=")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                if (tlmRaw != null) lastTlmJson = JSONObject(tlmRaw)
            }
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message(if (responseCode == 200) "OK" else "Error")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun makeClient(interceptor: CapturingInterceptor): IternioTelemetryClient {
        val ok = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return IternioTelemetryClient(ok)
    }

    /** Minimal DiParsData with the most common driving fields populated. */
    private fun drivingData(
        soc: Int? = 73,
        speed: Int? = 60,
        power: Double? = 12.5,
        chargeGunState: Int? = 1,
        chargingStatus: Int? = 0,
        gear: Int? = 4,
    ): DiParsData = DiParsData(
        soc = soc, speed = speed, mileage = 12345.0, power = power,
        chargeGunState = chargeGunState,
        maxBatTemp = 28, avgBatTemp = 26, minBatTemp = 24,
        chargingStatus = chargingStatus, batteryCapacityKwh = 72.9,
        totalElecConsumption = null,
        voltage12v = 12.6, maxCellVoltage = 3.31, minCellVoltage = 3.30,
        exteriorTemp = 18, gear = gear, powerState = 2, insideTemp = 22,
        acStatus = 1, acTemp = 22, fanLevel = 2, acCirc = 0,
        doorFL = 0, doorFR = 0, doorRL = 0, doorRR = 0,
        windowFL = 0, windowFR = 0, windowRL = 0, windowRR = 0,
        sunroof = 0, trunk = 0, hood = 0, seatbeltFL = 1, lockFL = 2,
        tirePressFL = 240, tirePressFR = 241, tirePressRL = 239, tirePressRR = 242,
        driveMode = 1, workMode = 1, autoPark = 0, rain = 0,
        lightLow = 0, drl = 1
    )

    @Test
    fun `empty token rejects request without HTTP call`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "  ", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        assertTrue(r.isFailure)
        assertNull("must not hit network on empty token", capt.lastRequest)
    }

    @Test
    fun `null SOC skips send entirely - SOC is required by Iternio`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        val data = drivingData(soc = null)
        val r = client.send(apiKey = "k", userToken = "tok", data = data, nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        assertTrue("must report failure when SOC unavailable", r.isFailure)
        assertNull("must not send a payload without SOC", capt.lastRequest)
    }

    @Test
    fun `URL targets legacy v1 endpoint with api_key as query parameter`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        client.send(apiKey = "appkey", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        val url = capt.lastRequest!!.url
        assertEquals("api.iternio.com", url.host)
        assertEquals("/1/tlm/send", url.encodedPath)
        assertEquals("appkey", url.queryParameter("api_key"))
    }

    @Test
    fun `blank api_key falls back to embedded community key`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        // Empty string from settings — Iternio rejects with HTTP 401 if api_key
        // is missing, so the client must send DEFAULT_API_KEY instead.
        client.send(apiKey = "", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        val url = capt.lastRequest!!.url
        assertEquals(IternioTelemetryClient.DEFAULT_API_KEY, url.queryParameter("api_key"))
    }

    @Test
    fun `payload omits GPS fields - ABRP-on-DiLink reads location itself`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        val tlm = capt.lastTlmJson!!
        assertFalse("lat must not be sent", tlm.has("lat"))
        assertFalse("lon must not be sent", tlm.has("lon"))
        assertFalse("elevation must not be sent", tlm.has("elevation"))
    }

    @Test
    fun `payload includes core driving fields with passthrough power sign`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        // BYD instantaneous power matches Iternio convention: positive = consumption,
        // negative = charging/regen. Pass through without inverting.
        client.send(
            apiKey = "k", userToken = "tok",
            data = drivingData(soc = 50, speed = 0, power = -15.0, chargeGunState = 2, chargingStatus = 1, gear = 1),
            nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null
        )

        val tlm = capt.lastTlmJson!!
        assertEquals(50, tlm.getInt("soc"))
        assertEquals(0, tlm.getInt("speed"))
        assertEquals(-15.0, tlm.getDouble("power"), 0.001)
        assertEquals(72.9, tlm.getDouble("capacity"), 0.001)
        assertEquals(1, tlm.getInt("is_charging"))
        assertEquals(1, tlm.getInt("is_parked"))
        assertNotNull(tlm.opt("utc"))
    }

    @Test
    fun `is_dcfc set to 1 when charging gun state indicates DC`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        val charging = ChargingReading(
            gunConnectState = 3, // DC
            chargingType = 3, chargeBatteryVoltV = 400, batteryType = 1,
            chargingCapacityKwh = 7.2f, bmsState = 1, readAtMs = 0L
        )
        client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = charging, carModel = null)

        val tlm = capt.lastTlmJson!!
        assertEquals(1, tlm.getInt("is_dcfc"))
        assertEquals(7.2, tlm.getDouble("kwh_charged"), 0.01)
    }

    @Test
    fun `is_dcfc set to 0 for AC charging`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        val charging = ChargingReading(
            gunConnectState = 2, // AC
            chargingType = 2, chargeBatteryVoltV = 0, batteryType = 1,
            chargingCapacityKwh = 3.5f, bmsState = 1, readAtMs = 0L
        )
        client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = charging, carModel = null)

        val tlm = capt.lastTlmJson!!
        assertEquals(0, tlm.getInt("is_dcfc"))
        assertEquals(3.5, tlm.getDouble("kwh_charged"), 0.01)
    }

    @Test
    fun `soh sent when battery reading provides it`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        val battery = BatteryReading(
            sohPercent = 92f, socPercent = 73f, lifetimeKwh = 1234f,
            lifetimeMileageKm = 11000f, voltage12v = 12.6f, readAtMs = 0L
        )
        client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =battery, charging = null, carModel = null)

        val tlm = capt.lastTlmJson!!
        assertEquals(92.0, tlm.getDouble("soh"), 0.01)
    }

    @Test
    fun `autoservice fields absent when battery and charging null`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        val tlm = capt.lastTlmJson!!
        assertFalse("is_dcfc must be absent without ChargingReading", tlm.has("is_dcfc"))
        assertFalse("kwh_charged must be absent", tlm.has("kwh_charged"))
        assertFalse("soh must be absent without BatteryReading", tlm.has("soh"))
    }

    @Test
    fun `chargingCapacityKwh sentinel -1 not sent as kwh_charged`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        val charging = ChargingReading(
            gunConnectState = 1, chargingType = 1, chargeBatteryVoltV = 0, batteryType = 1,
            chargingCapacityKwh = -1.0f, bmsState = null, readAtMs = 0L
        )
        client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = charging, carModel = null)

        val tlm = capt.lastTlmJson!!
        assertFalse("negative sentinel must not be sent as kwh_charged", tlm.has("kwh_charged"))
    }

    @Test
    fun `non-200 HTTP response returns failure`() = runTest {
        val capt = CapturingInterceptor(responseCode = 500, responseBody = "server error")
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        assertTrue(r.isFailure)
    }

    @Test
    fun `API status non-ok returns failure even on HTTP 200`() = runTest {
        val capt = CapturingInterceptor(responseBody = """{"status":"error","reason":"bad token"}""")
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        assertTrue(r.isFailure)
    }

    @Test
    fun `successful send returns success`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        assertTrue(r.isSuccess)
    }
}
