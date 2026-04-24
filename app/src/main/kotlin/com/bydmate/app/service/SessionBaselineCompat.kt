package com.bydmate.app.service

/**
 * Temporary shim — keeps SessionPersistence + TrackingService compiling while
 * Task 6/7 rewires the new OdometerConsumptionBuffer integration.
 *
 * TODO(Task 6): delete this file and rewrite SessionPersistence to use OdometerSampleEntity.
 */
@Deprecated("Will be removed in Task 6 — replaced by OdometerConsumptionBuffer persistence")
data class SessionBaseline(
    val sessionStartedAt: Long,
    val mileageStart: Double,
    val totalElecStart: Double,
)
