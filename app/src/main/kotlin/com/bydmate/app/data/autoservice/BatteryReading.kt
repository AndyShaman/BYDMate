package com.bydmate.app.data.autoservice

/**
 * One snapshot of battery-related autoservice fids.
 *
 * Distinct from BatterySnapshotEntity (DB row tied to a charge session).
 * This is the in-memory carrier of a single live read.
 *
 * All fields nullable: any field can come back as a sentinel.
 */
data class BatteryReading(
    val sohPercent: Float?,
    val socPercent: Float?,
    val lifetimeKwh: Float?,
    val lifetimeMileageKm: Float?,
    val voltage12v: Float?,
    val readAtMs: Long
)
