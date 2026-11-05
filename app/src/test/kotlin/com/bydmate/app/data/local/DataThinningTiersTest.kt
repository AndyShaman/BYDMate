package com.bydmate.app.data.local

import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.TripPointDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Trip points keep full resolution for 30 days, then 1 per 5 s forever (the trip window
 * needs dense points for its cursor). Charge points keep their old 15 s / 60 s tiers.
 */
class DataThinningTiersTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun `trip points are thinned only past 30 days, to 1 per 5 s`() = runBlocking {
        val trips = mockk<TripPointDao>()
        val charges = mockk<ChargePointDao>()
        coEvery { trips.thinOldPoints(any(), any()) } returns 0
        coEvery { charges.thinOldPoints(any(), any()) } returns 0

        DataThinningWorker.thin(trips, charges, now)

        coVerify(exactly = 1) { trips.thinOldPoints(now - 30 * day, 5_000L) }
        coVerify(exactly = 1) { trips.thinOldPoints(any(), any()) }
    }

    @Test
    fun `charge points keep the 15 s tier after 7 days and the 60 s tier after 30 days`() = runBlocking {
        val trips = mockk<TripPointDao>()
        val charges = mockk<ChargePointDao>()
        coEvery { trips.thinOldPoints(any(), any()) } returns 0
        coEvery { charges.thinOldPoints(any(), any()) } returns 0

        DataThinningWorker.thin(trips, charges, now)

        coVerify(exactly = 1) { charges.thinOldPoints(now - 7 * day, 15_000L) }
        coVerify(exactly = 1) { charges.thinOldPoints(now - 30 * day, 60_000L) }
        coVerify(exactly = 2) { charges.thinOldPoints(any(), any()) }
    }
}
