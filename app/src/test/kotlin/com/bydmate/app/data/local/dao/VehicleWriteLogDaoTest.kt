package com.bydmate.app.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VehicleWriteLogDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VehicleWriteLogDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.vehicleWriteLogDao()
    }

    @After fun tearDown() = db.close()

    private fun row(n: Int) = VehicleWriteLogEntity(
        ts = 1_000L * n, actionName = "a$n", dev = 1001, fid = n, requested = 1,
        readback = null, status = 0, error = null, validated = true,
    )

    @Test fun `trim keeps the newest rows and reports what it deleted`() = runBlocking {
        (1..10).forEach { dao.insert(row(it)) }

        assertEquals(6, dao.trimTo(4))

        assertEquals(listOf("a10", "a9", "a8", "a7"), dao.getLatest(40).map { it.actionName })
        assertEquals(0, dao.trimTo(4))
    }

    @Test fun `latest is newest first and bounded`() = runBlocking {
        (1..5).forEach { dao.insert(row(it)) }

        assertEquals(listOf("a5", "a4"), dao.getLatest(2).map { it.actionName })
    }
}
