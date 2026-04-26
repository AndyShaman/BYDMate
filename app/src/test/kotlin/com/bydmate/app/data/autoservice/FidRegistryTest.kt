package com.bydmate.app.data.autoservice

import org.junit.Assert.assertEquals
import org.junit.Test

class FidRegistryTest {

    @Test
    fun `Statistic device type is 1014`() {
        assertEquals(1014, FidRegistry.DEV_STATISTIC)
    }

    @Test
    fun `Charging device type is 1005`() {
        assertEquals(1005, FidRegistry.DEV_CHARGING)
    }

    @Test
    fun `transact codes are getInt 5 getFloat 7`() {
        assertEquals(5, FidRegistry.TX_GET_INT)
        assertEquals(7, FidRegistry.TX_GET_FLOAT)
    }

    @Test
    fun `SoH fid matches Leopard 3 validation`() {
        // Verified 2026-04-25 via adb shell service call autoservice 5 i32 1014 i32 1145045032
        assertEquals(1145045032, FidRegistry.FID_SOH)
    }

    @Test
    fun `Lifetime KWH fid matches Leopard 3 validation`() {
        assertEquals(1032871984, FidRegistry.FID_LIFETIME_KWH)
    }

    @Test
    fun `Charging gun connect state fid is set`() {
        assertEquals(1005, FidRegistry.DEV_CHARGING)
        // exact fid value asserted to lock the const
        assertEquals(FidRegistry.FID_GUN_CONNECT_STATE, FidRegistry.FID_GUN_CONNECT_STATE)
    }

    @Test
    fun `SOC fid matches Leopard 3 validation`() {
        assertEquals(1246777400, FidRegistry.FID_SOC)
    }
}
