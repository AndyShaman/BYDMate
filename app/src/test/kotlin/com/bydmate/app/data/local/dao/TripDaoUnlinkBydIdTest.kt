package com.bydmate.app.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TripDaoUnlinkBydIdTest {

    private lateinit var db: AppDatabase

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun teardown() { db.close() }

    @Test fun `unlink clears the id only for the matching trip and id`() = runBlocking {
        val dao = db.tripDao()
        val id = dao.insert(TripEntity(startTs = 1000L, bydId = 3L, distanceKm = 8.0))

        assertEquals(0, dao.unlinkBydId(id, 4L))
        assertEquals(3L, dao.getById(id)!!.bydId)

        assertEquals(1, dao.unlinkBydId(id, 3L))
        val after = dao.getById(id)!!
        assertNull(after.bydId)
        assertEquals(8.0, after.distanceKm!!, 1e-9)

        assertEquals(0, dao.unlinkBydId(id, 3L))
    }
}
