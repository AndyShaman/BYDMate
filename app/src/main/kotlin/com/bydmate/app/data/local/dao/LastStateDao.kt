package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.bydmate.app.data.local.entity.LastStateEntity

@Dao
interface LastStateDao {
    @Query("SELECT * FROM last_state WHERE id = 1")
    suspend fun getCurrent(): LastStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: LastStateEntity)

    /**
     * Row id=1 with the open-trip columns left NULL. Used as the missing-row half of
     * [writeSnapshot], and by TripRecorder before its own trip-columns-only retry when
     * `openTrip()` finds no row yet; a no-op when the row already exists.
     */
    @Query(
        """
        INSERT OR IGNORE INTO last_state (id, ts, soc, mileage, total_elec, ignition, energydata_available)
        VALUES (1, :ts, :soc, :mileage, :totalElec, :ignition, :energydataAvailable)
        """
    )
    @Suppress("LongParameterList") // one bound parameter per snapshot column
    suspend fun insertSnapshotRowIfAbsent(
        ts: Long,
        soc: Int?,
        mileage: Double?,
        totalElec: Double?,
        ignition: Int?,
        energydataAvailable: Int,
    )

    @Query(
        """
        UPDATE last_state
        SET ts = :ts,
            soc = :soc,
            mileage = :mileage,
            total_elec = :totalElec,
            ignition = :ignition,
            energydata_available = :energydataAvailable
        WHERE id = 1
        """
    )
    @Suppress("LongParameterList") // one bound parameter per snapshot column
    suspend fun updateSnapshotColumns(
        ts: Long,
        soc: Int?,
        mileage: Double?,
        totalElec: Double?,
        ignition: Int?,
        energydataAvailable: Int,
    )

    /**
     * SharedAdaptiveLoop's per-tick write: touches only the loop-owned columns (ts, soc,
     * mileage, total_elec, ignition, energydata_available) and never open_trip_id /
     * trip_start_*, which TripRecorder owns. A whole-row REPLACE (the old [upsert] path)
     * raced with TripRecorder's openTrip()/clearOpenTrip() UPDATEs between this method's
     * getCurrent() read and its write, and could wipe out a trip opened or closed
     * concurrently with a snapshot tick.
     *
     * INSERT OR IGNORE first covers the row-does-not-exist-yet case (very first tick).
     */
    @Suppress("LongParameterList") // one bound parameter per snapshot column
    @Transaction
    suspend fun writeSnapshot(
        ts: Long,
        soc: Int?,
        mileage: Double?,
        totalElec: Double?,
        ignition: Int?,
        energydataAvailable: Int,
    ) {
        insertSnapshotRowIfAbsent(ts, soc, mileage, totalElec, ignition, energydataAvailable)
        updateSnapshotColumns(ts, soc, mileage, totalElec, ignition, energydataAvailable)
    }

    /**
     * Mark a new open trip in last_state. Writes to row id=1 (creating it if absent
     * via UPSERT semantics on the caller's side — most callers will read getCurrent()
     * first and merge). Called by TripRecorder on trip open.
     *
     * Uses COALESCE to preserve an existing openTripId if one is already set.
     * Returns the number of rows affected (0 if row id=1 does not exist yet).
     */
    @Query(
        """
        UPDATE last_state
        SET open_trip_id = COALESCE(open_trip_id, :startTs),
            trip_start_ts = :startTs,
            trip_start_soc = :startSoc,
            trip_start_mileage = :startMileage,
            trip_start_total_elec = :startTotalElec,
            trip_start_exterior_temp = :startExteriorTemp,
            ts = :now
        WHERE id = 1
        """
    )
    @Suppress("LongParameterList") // one bound parameter per open-trip column
    suspend fun openTrip(
        startTs: Long,
        startSoc: Int?,
        startMileage: Double?,
        startTotalElec: Double?,
        startExteriorTemp: Int?,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE last_state
        SET open_trip_id = NULL,
            trip_start_ts = NULL,
            trip_start_soc = NULL,
            trip_start_mileage = NULL,
            trip_start_total_elec = NULL,
            trip_start_exterior_temp = NULL
        WHERE id = 1
        """
    )
    suspend fun clearOpenTrip()
}
