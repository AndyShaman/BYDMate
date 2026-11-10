package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.BatteryReading

/**
 * Single in-process facade for vehicle reads + writes.
 *
 * Reads delegate to AutoserviceClient (Phase 1b stack).
 * Writes (Group C, populated from Phase 2c probe) go through HelperClient
 * via WriteAllowlist.
 *
 * Returns null on any read failure (sentinel, daemon down, parse failure).
 * Returns Result.failure(VehicleWriteError) on any write failure — never throws.
 * The error type carries actionName + details for structured logging.
 */
interface VehicleApi {
    // Liveness / snapshots — passthroughs to AutoserviceClient.
    suspend fun isAvailable(): Boolean
    suspend fun readBatterySnapshot(): BatteryReading?

    // Write entrypoint, wired to WriteAllowlist + HelperClient (Group C).
    // Failure reason available via Result.exceptionOrNull() as VehicleWriteError.
    suspend fun dispatch(commandString: String): Result<Unit>

    /** Rear seats whose heat this car has (#327). */
    suspend fun rearSeatHeatSides(): Set<RearSeat> = emptySet()
}
