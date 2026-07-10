package com.bydmate.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for TripRepository.deleteTrip().
 * Uses a real in-memory Room DB so withTransaction semantics are real
 * (no MockK extension-function workaround needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TripRepositoryDeleteTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TripRepository

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TripRepository(db.tripDao(), db.tripPointDao(), db.tripTombstoneDao(), db)
    }

    @After fun teardown() { db.close() }

    @Test fun `delete of energydata trip writes tombstone and removes trip`() = runBlocking {
        val id = db.tripDao().insert(TripEntity(startTs = 1000L, bydId = 42L))
        repo.deleteTrip(TripEntity(id = id, startTs = 1000L, bydId = 42L))
        assertNull("trip must be gone after delete", db.tripDao().getById(id))
        assertTrue("tombstone must exist for bydId=42", db.tripTombstoneDao().exists(42L))
    }

    @Test fun `delete of live trip without bydId removes trip and writes no tombstone`() = runBlocking {
        val id = db.tripDao().insert(TripEntity(startTs = 2000L, bydId = null))
        repo.deleteTrip(TripEntity(id = id, startTs = 2000L, bydId = null))
        assertNull("trip must be gone after delete", db.tripDao().getById(id))
        // bydId=0 is the sentinel for "no bydId" in TripTombstoneEntity — must NOT be inserted.
        assertFalse("no tombstone must be written for a live trip", db.tripTombstoneDao().exists(0L))
    }

    @Test fun `tombstone is present and trip is gone after atomic delete`() = runBlocking {
        val id = db.tripDao().insert(TripEntity(startTs = 3000L, bydId = 99L))
        // Insert a trip, then delete atomically. End state: trip gone, tombstone present.
        repo.deleteTrip(TripEntity(id = id, startTs = 3000L, bydId = 99L))
        assertNull("trip entity must not exist", db.tripDao().getById(id))
        assertTrue("tombstone for bydId=99 must exist", db.tripTombstoneDao().exists(99L))
    }
}
