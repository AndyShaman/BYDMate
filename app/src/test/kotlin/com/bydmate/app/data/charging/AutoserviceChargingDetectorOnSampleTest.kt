package com.bydmate.app.data.charging

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoserviceChargingDetectorOnSampleTest {
    @Test fun `onSample caches the latest DiParsData`() {
        val detector = AutoserviceChargingDetector(
            client = mockk(relaxed = true),
            chargeRepo = mockk(relaxed = true),
            batteryHealthRepo = mockk(relaxed = true),
            stateStore = mockk(relaxed = true),
            classifier = mockk(relaxed = true),
            settings = mockk(relaxed = true),
            parsReader = mockk<ParsReader>(relaxed = true),
        )

        val sample = diParsData(soc = 85)
        detector.onSample(sample)
        // private field — assert via reflection
        val field = AutoserviceChargingDetector::class.java.getDeclaredField("lastSample")
        field.isAccessible = true
        assertEquals(sample, field.get(detector))
    }
}
