package com.bydmate.app.data.autoservice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoserviceClientImplTest {

    private class FakeAdb(
        val responses: Map<String, String?>,
        val connected: Boolean = true
    ) : AdbOnDeviceClient {
        val calls = mutableListOf<String>()
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun isConnected(): Boolean = connected
        override suspend fun exec(cmd: String): String? {
            calls += cmd
            return responses[cmd]
        }
        override suspend fun shutdown() {}
    }

    private fun parcelInt(value: Int): String =
        "Result: Parcel(00000000 %08x   '........')".format(value)

    private fun parcelFloat(valueBits: Int): String =
        "Result: Parcel(00000000 %08x   '........')".format(valueBits)

    @Test
    fun `getInt returns parsed value when ADB returns valid Parcel`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 1246777400"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(91)))
        val client = AutoserviceClientImpl(adb)

        val result = client.getInt(dev = 1014, fid = 1246777400)

        assertEquals(91, result)
        assertEquals(listOf(cmd), adb.calls)
    }

    @Test
    fun `getInt returns null when ADB returns sentinel 0xFFFF`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 99999"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(0xFFFF)))
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getInt(dev = 1014, fid = 99999))
    }

    @Test
    fun `getInt returns null when ADB returns -10011 wrong direction`() = runTest {
        val cmd = "service call autoservice 5 i32 1015 i32 12345"
        val adb = FakeAdb(responses = mapOf(cmd to parcelInt(-10011)))
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getInt(dev = 1015, fid = 12345))
    }

    @Test
    fun `getInt returns null when ADB exec returns null`() = runTest {
        val cmd = "service call autoservice 5 i32 1014 i32 1246777400"
        val adb = FakeAdb(responses = emptyMap(), connected = false)
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getInt(dev = 1014, fid = 1246777400))
        assertEquals(listOf(cmd), adb.calls)
    }

    @Test
    fun `getFloat parses IEEE 754 bits and returns Float`() = runTest {
        // 91.0f as int bits = 0x42B60000 = 1119223808
        val cmd = "service call autoservice 7 i32 1014 i32 1145045032"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0x42B60000.toInt())))
        val client = AutoserviceClientImpl(adb)

        assertEquals(91.0f, client.getFloat(dev = 1014, fid = 1145045032)!!, 0.001f)
    }

    @Test
    fun `getFloat returns null on -1f sentinel (0xBF800000)`() = runTest {
        val cmd = "service call autoservice 7 i32 1014 i32 1246765072"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0xBF800000.toInt())))
        val client = AutoserviceClientImpl(adb)

        assertNull(client.getFloat(dev = 1014, fid = 1246765072))
    }

    @Test
    fun `readBatterySnapshot wires every fid and aggregates into BatteryReading`() = runTest {
        val adb = FakeAdb(responses = mapOf(
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOH}" to parcelFloat(0x42C80000.toInt()),  // 100f
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOC}" to parcelFloat(0x42B60000.toInt()),  // 91f
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_LIFETIME_KWH}" to parcelFloat(0x4416B333.toInt()),  // ~602.7f
            "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_LIFETIME_MILEAGE}" to parcelFloat(0xBF800000.toInt()),  // sentinel
            "service call autoservice 7 i32 1003 i32 ${FidRegistry.FID_OTA_BATTERY_POWER_VOLTAGE}" to parcelFloat(0x41600000.toInt())  // 14.0f
        ))
        val client = AutoserviceClientImpl(adb)

        val snap = client.readBatterySnapshot()

        assertEquals(100.0f, snap!!.sohPercent!!, 0.01f)
        assertEquals(91.0f, snap.socPercent!!, 0.01f)
        assertEquals(602.7f, snap.lifetimeKwh!!, 0.1f)
        assertNull(snap.lifetimeMileageKm)  // sentinel
        assertEquals(14.0f, snap.voltage12v!!, 0.01f)
        assertTrue(snap.readAtMs > 0)
    }

    @Test
    fun `readBatterySnapshot returns null when ADB not connected`() = runTest {
        val adb = FakeAdb(responses = emptyMap(), connected = false)
        val client = AutoserviceClientImpl(adb)

        assertNull(client.readBatterySnapshot())
    }

    @Test
    fun `readChargingSnapshot aggregates gun_state and type`() = runTest {
        val adb = FakeAdb(responses = mapOf(
            "service call autoservice 5 i32 1005 i32 ${FidRegistry.FID_GUN_CONNECT_STATE}" to parcelInt(2),
            "service call autoservice 5 i32 1005 i32 ${FidRegistry.FID_CHARGING_TYPE}" to parcelInt(2),
            "service call autoservice 5 i32 1005 i32 ${FidRegistry.FID_CHARGE_BATTERY_VOLT}" to parcelInt(512),
            "service call autoservice 5 i32 1005 i32 ${FidRegistry.FID_BATTERY_TYPE}" to parcelInt(1)
        ))
        val client = AutoserviceClientImpl(adb)

        val snap = client.readChargingSnapshot()

        assertEquals(2, snap!!.gunConnectState)
        assertEquals(2, snap.chargingType)
        assertEquals(512, snap.chargeBatteryVoltV)
        assertEquals(1, snap.batteryType)
        assertTrue(snap.readAtMs > 0)
    }

    @Test
    fun `isAvailable returns false when ADB not connected`() = runTest {
        val adb = FakeAdb(responses = emptyMap(), connected = false)
        val client = AutoserviceClientImpl(adb)

        assertEquals(false, client.isAvailable())
    }

    @Test
    fun `isAvailable returns true when ADB connected and SoH read succeeds`() = runTest {
        val cmd = "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOH}"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0x42C80000.toInt())))
        val client = AutoserviceClientImpl(adb)

        assertEquals(true, client.isAvailable())
    }

    @Test
    fun `isAvailable returns false when SoH read returns sentinel`() = runTest {
        val cmd = "service call autoservice 7 i32 1014 i32 ${FidRegistry.FID_SOH}"
        val adb = FakeAdb(responses = mapOf(cmd to parcelFloat(0xBF800000.toInt())))
        val client = AutoserviceClientImpl(adb)

        // Connected but SoH read returns null → autoservice fids inaccessible on this model.
        assertEquals(false, client.isAvailable())
    }
}
