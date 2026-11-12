package com.bydmate.app.ui.trips

import com.bydmate.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure mapping period + filter state -> empty-list text (R6): TODAY/WEEK are calendar based,
 * so right after midnight or Monday there are no finished trips yet even with one running.
 */
class EmptyTripsStringResTest {

    @Test fun `with no trips in the period today and week say the current trip is still running, the rest stay generic`() {
        val cases = listOf(
            Triple("today", TripPeriod.TODAY, R.string.trips_empty_today),
            Triple("week", TripPeriod.WEEK, R.string.trips_empty_week),
            Triple("month", TripPeriod.MONTH, R.string.trips_empty),
            Triple("year", TripPeriod.YEAR, R.string.trips_empty),
            Triple("all", TripPeriod.ALL, R.string.trips_empty),
        )
        for ((label, period, expected) in cases) {
            assertEquals("case: $label", expected, emptyTripsStringRes(period, hasTripsBeforeFilter = false))
        }
    }

    @Test fun `empty only because a type filter hides trips keeps the generic empty text`() {
        for (period in listOf(TripPeriod.TODAY, TripPeriod.WEEK)) {
            assertEquals(
                "case: $period",
                R.string.trips_empty,
                emptyTripsStringRes(period, hasTripsBeforeFilter = true),
            )
        }
    }
}
