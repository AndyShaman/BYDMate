package com.bydmate.app.service

import com.bydmate.app.data.remote.DiParsData
import org.json.JSONObject

/**
 * Adds DM-i hybrid fuel and powertrain fields to a copy of a telemetry JSON payload for custom
 * webhooks (#459). ABRP does not use this helper, and EV snapshots (all fields null) keep exact
 * input keys.
 */
object WebhookHybridFields {

    private const val MAX_FUEL_PERCENT = 100
    private const val MAX_RANGE_KM = 2000
    private const val MAX_ENGINE_RPM = 10000

    fun addTo(telemetry: JSONObject, data: DiParsData): JSONObject {
        val copy = JSONObject(telemetry.toString())
        data.fuelPercent?.takeIf { it in 0..MAX_FUEL_PERCENT }?.let { copy.put("fuel_percent", it) }
        data.fuelRangeKm?.takeIf { it in 0..MAX_RANGE_KM }?.let { copy.put("fuel_range_km", it) }
        data.fuelTotalL?.takeIf { it.isFinite() && it >= 0.0 }?.let { copy.put("fuel_total_l", it) }
        data.evRangeKm?.takeIf { it in 0..MAX_RANGE_KM }?.let { copy.put("ev_range_km", it) }
        data.mileageHevKm?.takeIf { it >= 0 }?.let { copy.put("odometer_hev_km", it) }
        data.mileageEvKm?.takeIf { it >= 0 }?.let { copy.put("odometer_ev_km", it) }
        data.engineRpm?.takeIf { it in 0..MAX_ENGINE_RPM }?.let { copy.put("engine_rpm", it) }
        return copy
    }
}
