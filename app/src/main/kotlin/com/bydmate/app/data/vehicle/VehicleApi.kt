package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.remote.DiParsData

/**
 * Single in-process facade for vehicle reads + writes.
 *
 * Reads delegate to NativeParsReader / AutoserviceClient (Phase 1b stack).
 * Writes (Group C, populated from Phase 2c probe) go through HelperClient
 * via WriteAllowlist.
 *
 * Returns null on any read failure (sentinel, daemon down, parse failure).
 * Returns Result.failure(VehicleWriteError) on any write failure — never throws.
 * The error type carries actionName + details for structured logging.
 */
interface VehicleApi {
    // Liveness / snapshots — passthroughs to AutoserviceClient / NativeParsReader.
    suspend fun isAvailable(): Boolean
    suspend fun readBatterySnapshot(): BatteryReading?
    suspend fun readSnapshot(): DiParsData?

    // Individual read accessors (grow organically as consumers migrate).
    suspend fun readSoc(): Float?
    suspend fun readSpeed(): Float?
    suspend fun readMileageKm(): Float?
    suspend fun readPowerKw(): Int?
    suspend fun readAcStatus(): Int?
    suspend fun readAcTemp(): Int?
    suspend fun readInsideTemp(): Int?
    suspend fun readExteriorTemp(): Int?
    suspend fun readFanLevel(): Int?
    suspend fun readWindowDriver(): Int?
    suspend fun readWindowPassenger(): Int?
    suspend fun readWindowRearLeft(): Int?
    suspend fun readWindowRearRight(): Int?

    // Write entrypoints — backwards-compat dispatch + structured methods.
    // All `write*` methods wired to WriteAllowlist + HelperClient (Group C).
    // Failure reason available via Result.exceptionOrNull() as VehicleWriteError.
    suspend fun dispatch(commandString: String): Result<Unit>
    suspend fun writeAcOn(): Result<Unit>
    suspend fun writeAcOff(): Result<Unit>
    suspend fun writeSetDriverTemp(celsius: Int): Result<Unit>   // range 16..30
    suspend fun writeWindowDriver(percent: Int): Result<Unit>    // range 0..100
    suspend fun writeWindowPassenger(percent: Int): Result<Unit> // range 0..100
    suspend fun writeWindowRearLeft(percent: Int): Result<Unit>  // range 0..100
    suspend fun writeWindowRearRight(percent: Int): Result<Unit> // range 0..100
    suspend fun writeLockDoors(): Result<Unit>
    suspend fun writeUnlockDoors(): Result<Unit>
    suspend fun writeSunroof(mode: SunroofMode): Result<Unit>
    suspend fun writeSunshade(open: Boolean): Result<Unit>
}
