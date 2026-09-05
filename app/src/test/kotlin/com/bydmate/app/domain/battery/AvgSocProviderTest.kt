package com.bydmate.app.domain.battery

import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.TripRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The average-SOC aggregation (#93) moved out of DashboardViewModel when the battery-health
 * dialog became the «Техника» screen; this keeps its coverage.
 */
class AvgSocProviderTest {

    private fun provider(
        trips: List<TripEntity>,
        charges: List<ChargeEntity>,
    ): AvgSocProvider {
        val tripRepository = mockk<TripRepository>()
        val chargeDao = mockk<ChargeDao>()
        every { tripRepository.getAllTrips() } returns flowOf(trips)
        every { chargeDao.getAll() } returns flowOf(charges)
        return AvgSocProvider(tripRepository, chargeDao)
    }

    /** charge 40→80 ends at t=200, trip 80→60 spans 300..400: both windows hold soc 60
     *  for essentially the whole span up to now, so both round to 60. */
    @Test
    fun `averages both windows from trips and charges`() = runTest {
        val avg = provider(
            trips = listOf(TripEntity(startTs = 300L, endTs = 400L, socStart = 80, socEnd = 60)),
            charges = listOf(
                ChargeEntity(startTs = 100L, endTs = 200L, socStart = 40, socEnd = 80, status = "COMPLETED")
            ),
        ).compute()

        assertEquals(60, avg.allTime)
        assertEquals(60, avg.sinceCharge)
    }

    /** No completed charge → no "since charge" window; the all-time one still answers. */
    @Test
    fun `since charge is null without a completed charge`() = runTest {
        val avg = provider(
            trips = listOf(TripEntity(startTs = 300L, endTs = 400L, socStart = 80, socEnd = 60)),
            charges = emptyList(),
        ).compute()

        assertEquals(60, avg.allTime)
        assertNull(avg.sinceCharge)
    }

    @Test
    fun `empty history yields nothing`() = runTest {
        val avg = provider(trips = emptyList(), charges = emptyList()).compute()

        assertNull(avg.allTime)
        assertNull(avg.sinceCharge)
    }
}
