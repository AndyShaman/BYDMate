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
        private val responseBody: String = """{"status":"ok"}""",
        private val responseHeaders: Map<String, String> = emptyMap(),
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
            val builder = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message(if (responseCode == 200) "OK" else "Error")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
            responseHeaders.forEach { (k, v) -> builder.header(k, v) }
            return builder.build()
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
    fun `utc field uses sampleTimeMs param when provided - snapshot timestamp not send time`() = runTest {
        // ABRP plots samples on a timeline. At 8 s charging cadence the
        // ADB+HTTP round-trip can push the wall-clock by ~500 ms, which
        // smears the visible curve. Caller passes the moment of sampling
        // (snapshotMs) and the client uses that for the `utc` field.
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        val knownEpochMs = 1_700_000_000_000L
        client.send(
            apiKey = "k", userToken = "tok", data = drivingData(),
            nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null,
            sampleTimeMs = knownEpochMs,
        )

        val tlm = capt.lastTlmJson!!
        assertEquals(knownEpochMs / 1000L, tlm.getLong("utc"))
    }

    @Test
    fun `successful send returns success`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery =null, charging = null, carModel = null)

        assertTrue(r.isSuccess)
    }

    // === Patch A: live battery power via autoservice ENG_POW ===
    //
    // ENG_POW (fid 339738656, dev=1012, tx=5) returns raw signed kW directly
    // (see reference_eng_pow_fid.md, validated 2026-05-11). It must take
    // priority over the DiPars `data.power` field, which on Leopard 3 often
    // arrives null in reduced-payload mode or reports motor mechanical power
    // (not battery-side draw).

    @Test
    fun `enginePowerKw from autoservice takes priority over DiPars power`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        client.send(
            apiKey = "k", userToken = "tok",
            data = drivingData(power = 12.0),
            nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null,
            enginePowerKw = 5,
        )

        val tlm = capt.lastTlmJson!!
        assertEquals(5.0, tlm.getDouble("power"), 0.001)
    }

    @Test
    fun `enginePowerKw null falls back to DiPars data power`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        client.send(
            apiKey = "k", userToken = "tok",
            data = drivingData(power = 7.5),
            nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null,
            enginePowerKw = null,
        )

        val tlm = capt.lastTlmJson!!
        assertEquals(7.5, tlm.getDouble("power"), 0.001)
    }

    @Test
    fun `enginePowerKw out of plausible range is rejected and falls back to DiPars`() = runTest {
        // Sanity gate per reference_eng_pow_fid.md: [-300, +500] kW covers the
        // worst-case Leopard 3 envelope (max DC charge ~150 kW, max discharge
        // ~250 kW during launch). Anything outside that window is a sentinel
        // or a Parcel parse glitch — drop it and try the next source.
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        client.send(
            apiKey = "k", userToken = "tok",
            data = drivingData(power = 7.0),
            nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null,
            enginePowerKw = 999,
        )

        val tlm = capt.lastTlmJson!!
        assertEquals("must fall back to DiPars on out-of-range autoservice value",
            7.0, tlm.getDouble("power"), 0.001)
    }

    @Test
    fun `enginePowerKw deeply negative out of range falls back to zero when DiPars absent`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        client.send(
            apiKey = "k", userToken = "tok",
            data = drivingData(power = null),
            nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null,
            enginePowerKw = -1000,
        )

        val tlm = capt.lastTlmJson!!
        assertEquals(0.0, tlm.getDouble("power"), 0.001)
    }

    @Test
    fun `enginePowerKw at range boundary minus 300 is accepted`() = runTest {
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        client.send(
            apiKey = "k", userToken = "tok",
            data = drivingData(power = 99.0),
            nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null,
            enginePowerKw = -300,
        )

        val tlm = capt.lastTlmJson!!
        assertEquals(-300.0, tlm.getDouble("power"), 0.001)
    }

    // === Patch A: rate-limit / server-error signaling ===
    //
    // Iternio doesn't publish a strict QPS limit, but their CDN returns
    // 429 with Retry-After during burst events and 5xx during deploys.
    // We surface both as typed exceptions so TrackingService can stop
    // hammering when the upstream asks us to.

    @Test
    fun `HTTP 429 surfaces IternioRateLimitException with Retry-After value`() = runTest {
        val capt = CapturingInterceptor(
            responseCode = 429,
            responseBody = "rate limited",
            responseHeaders = mapOf("Retry-After" to "42"),
        )
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null)

        assertTrue(r.isFailure)
        val ex = r.exceptionOrNull()
        assertTrue("expected IternioRateLimitException, got ${ex?.javaClass}", ex is IternioRateLimitException)
        assertEquals(42, (ex as IternioRateLimitException).retryAfterSec)
    }

    @Test
    fun `HTTP 429 without Retry-After header still surfaces IternioRateLimitException`() = runTest {
        val capt = CapturingInterceptor(responseCode = 429, responseBody = "rate limited")
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null)

        assertTrue(r.isFailure)
        val ex = r.exceptionOrNull()
        assertTrue("expected IternioRateLimitException, got ${ex?.javaClass}", ex is IternioRateLimitException)
        // Null means "no upstream hint" — caller picks its own backoff.
        assertEquals(null, (ex as IternioRateLimitException).retryAfterSec)
    }

    @Test
    fun `429 Retry-After as HTTP-date in the past returns 0 seconds`() = runTest {
        // RFC 7231 allows Retry-After in HTTP-date format. A date already
        // elapsed means "OK to retry now" — surface 0.
        val capt = CapturingInterceptor(
            responseCode = 429, responseBody = "rl",
            responseHeaders = mapOf("Retry-After" to "Sun, 01 Jan 2000 00:00:00 GMT"),
        )
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null)

        val ex = r.exceptionOrNull() as? IternioRateLimitException
        assertNotNull(ex)
        assertEquals(0, ex!!.retryAfterSec)
    }

    @Test
    fun `429 Retry-After as HTTP-date far in future returns null retryAfterSec`() = runTest {
        // Server told us to wait years — clearly broken. Return null so the
        // caller picks its own bounded backoff instead of cooling for an hour.
        val capt = CapturingInterceptor(
            responseCode = 429, responseBody = "rl",
            responseHeaders = mapOf("Retry-After" to "Sun, 01 Jan 2099 00:00:00 GMT"),
        )
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null)

        val ex = r.exceptionOrNull() as? IternioRateLimitException
        assertNotNull(ex)
        assertEquals(null, ex!!.retryAfterSec)
    }

    @Test
    fun `429 Retry-After garbage value returns null retryAfterSec`() = runTest {
        val capt = CapturingInterceptor(
            responseCode = 429, responseBody = "rl",
            responseHeaders = mapOf("Retry-After" to "not-a-date-or-number"),
        )
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null)

        val ex = r.exceptionOrNull() as? IternioRateLimitException
        assertNotNull(ex)
        assertEquals(null, ex!!.retryAfterSec)
    }

    @Test
    fun `HTTP 503 surfaces IternioServerErrorException with status code`() = runTest {
        val capt = CapturingInterceptor(responseCode = 503, responseBody = "down")
        val client = makeClient(capt)

        val r = client.send(apiKey = "k", userToken = "tok", data = drivingData(), nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null)

        assertTrue(r.isFailure)
        val ex = r.exceptionOrNull()
        assertTrue("expected IternioServerErrorException, got ${ex?.javaClass}", ex is IternioServerErrorException)
        assertEquals(503, (ex as IternioServerErrorException).httpStatus)
    }

    @Test
    fun `power always present even when both autoservice and DiPars null - sends 0`() = runTest {
        // ABRP calibration ranks data sources by what arrives at >= 1 sample
        // per 10 seconds. Skipping `power` when sources are dead pulls our
        // score below 12% — the only way to keep ABRP confident is to send
        // 0 explicitly. The signal lives in upstream logs (power_source
        // metric); the payload itself must always carry `power`.
        val capt = CapturingInterceptor()
        val client = makeClient(capt)

        client.send(
            apiKey = "k", userToken = "tok",
            data = drivingData(power = null),
            nominalCapacityKwh = 72.9, battery = null, charging = null, carModel = null,
            enginePowerKw = null,
        )

        val tlm = capt.lastTlmJson!!
        assertTrue("power must always be present in payload", tlm.has("power"))
        assertEquals(0.0, tlm.getDouble("power"), 0.001)
    }
}
