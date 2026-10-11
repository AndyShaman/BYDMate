package com.bydmate.app.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.CounterSnapshotEntity
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.trips.CounterSnapshotWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DM-i (#184): a snapshot without counters is no trip edge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CounterSnapshotDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CounterSnapshotDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.counterSnapshotDao()
    }

    @After fun tearDown() = db.close()

    private suspend fun row(ts: Long, fuel: Double?, hev: Double?, reason: String) = dao.insert(
        CounterSnapshotEntity(ts = ts, fuelTotal = fuel, mileageHev = hev, mileageEv = null, odometer = null, reason = reason)
    )

    @Test fun `nearest skips a row without counters for a readable one further away`() = runBlocking {
        row(1_000_000L, null, null, CounterSnapshotEntity.REASON_IGNITION_OFF)
        row(1_050_000L, 305.2, null, CounterSnapshotEntity.REASON_PERIODIC)

        val found = dao.nearest(1_000_000L, 90_000L)!!
        assertEquals(1_050_000L, found.ts)
        assertEquals(null, dao.nearest(1_000_000L, 10_000L))
    }

    @Test fun `a cold start next to a row without counters still writes its row`() = runBlocking {
        row(5_000_000L - 5_000L, null, null, CounterSnapshotEntity.REASON_IGNITION_OFF)

        CounterSnapshotWriter(dao).onColdStart(
            LastStateEntity(id = 1, ts = 5_030_000L, onTs = 5_000_000L, onFuelTotal = 305.2, onMileageHev = 3850.0),
            VehicleKind.HYBRID,
        )

        val found = dao.nearest(5_000_000L, 90_000L)!!
        assertEquals(CounterSnapshotEntity.REASON_COLD_START, found.reason)
        assertEquals(305.2, found.fuelTotal!!, 1e-9)
    }
}
