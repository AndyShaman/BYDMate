package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.OdometerSampleEntity

@Dao
interface OdometerSampleDao {

    @Insert
    suspend fun insert(sample: OdometerSampleEntity): Long

    /** Newest sample (by id, monotonic via autoGenerate). */
    @Query("SELECT * FROM odometer_samples ORDER BY id DESC LIMIT 1")
    suspend fun last(): OdometerSampleEntity?

    /** All samples with mileage_km >= :minMileage, ASC by mileage. */
    @Query("""
        SELECT * FROM odometer_samples
        WHERE mileage_km >= :minMileage
        ORDER BY mileage_km ASC
    """)
    suspend fun windowFrom(minMileage: Double): List<OdometerSampleEntity>

    /** Trim samples below the rolling cutoff. */
    @Query("DELETE FROM odometer_samples WHERE mileage_km < :cutoff")
    suspend fun trimBelow(cutoff: Double)

    /** Hard-cap fallback — drop the N oldest rows by id. */
    @Query("""
        DELETE FROM odometer_samples WHERE id IN (
            SELECT id FROM odometer_samples ORDER BY id ASC LIMIT :howMany
        )
    """)
    suspend fun deleteOldest(howMany: Int)

    @Query("SELECT COUNT(*) FROM odometer_samples")
    suspend fun count(): Int

    /** Wipe — used by reset / debug only. */
    @Query("DELETE FROM odometer_samples")
    suspend fun clear()
}
