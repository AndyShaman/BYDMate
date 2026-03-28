package com.bydmate.app.domain.model

// TODO: Expand with full fields from DB schema (see docs/plans/2026-03-28-bydmate-design.md)

data class Trip(
    val id: Long = 0L,
    val startTime: Long = 0L,
    val endTime: Long = 0L,
)

data class TripPoint(
    val id: Long = 0L,
    val tripId: Long = 0L,
    val timestamp: Long = 0L,
)

data class Charge(
    val id: Long = 0L,
    val startTime: Long = 0L,
    val endTime: Long = 0L,
)

data class ChargePoint(
    val id: Long = 0L,
    val chargeId: Long = 0L,
    val timestamp: Long = 0L,
)
