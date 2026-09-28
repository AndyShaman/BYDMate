package com.bydmate.app.ui.trips

import com.bydmate.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure mapping period + filter state -> empty-list text (R6): TODAY/WEEK are calendar based,
 * so right after midnight or Monday there are no finished trips yet even with one running.
 */
class EmptyTripsStringResTest {

    @Test fun `today with no trips in period explains the current trip is still running`() {
        assertEquals(
            R.string.trips_empty_today,
            emptyTripsStringRes(TripPeriod.TODAY, hasTripsBeforeFilter = false),
        )
    }

    @Test fun `week with no trips in period explains the current trip is still running`() {
        assertEquals(
            R.string.trips_empty_week,
            emptyTripsStringRes(TripPeriod.WEEK, hasTripsBeforeFilter = false),
        )
    }

    @Test fun `month, year and all keep the generic empty text`() {
        assertEquals(R.string.trips_empty, emptyTripsStringRes(TripPeriod.MONTH, hasTripsBeforeFilter = false))
        assertEquals(R.string.trips_empty, emptyTripsStringRes(TripPeriod.YEAR, hasTripsBeforeFilter = false))
        assertEquals(R.string.trips_empty, emptyTripsStringRes(TripPeriod.ALL, hasTripsBeforeFilter = false))
    }

    @Test fun `empty only because a type filter hides trips keeps the generic empty text`() {
        assertEquals(
            R.string.trips_empty,
            emptyTripsStringRes(TripPeriod.TODAY, hasTripsBeforeFilter = true),
        )
        assertEquals(
            R.string.trips_empty,
            emptyTripsStringRes(TripPeriod.WEEK, hasTripsBeforeFilter = true),
        )
    }
}
