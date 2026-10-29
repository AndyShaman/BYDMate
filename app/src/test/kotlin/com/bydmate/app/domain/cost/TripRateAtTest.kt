package com.bydmate.app.domain.cost

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_CHARGES
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_DC
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rate the dashboard counters and the Telegram report put on the trip under way must
 * follow the period's trip rule, the same way [CostCalculator] prices a saved trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TripRateAtTest {

    private lateinit var db: AppDatabase
    private lateinit var calculator: CostCalculator

    private val day = 24 * 3600_000L
    private val now = 100 * day

    @Before fun setup() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries().build()
            db.settingsDao().set(SettingEntity(SettingsRepository.KEY_BATTERY_CAPACITY, "72.9"))
            val settings = SettingsRepository(db.settingsDao(), LocalePreferences(context))
            calculator = CostCalculator(db.tariffPeriodDao(), db.chargeDao(), db.tripDao(), settings)
        }
    }

    @After fun teardown() {
        db.close()
    }

    private suspend fun period(rule: String) = db.tariffPeriodDao().insert(
        TariffPeriodEntity(startTs = 0L, homeRate = 0.10, dcRate = 0.50, acLossPct = 0.0, dcLossPct = 0.0, tripRule = rule)
    )

    private suspend fun charge(ts: Long, kwh: Double, cost: Double) = db.chargeDao().insert(
        ChargeEntity(startTs = ts, endTs = ts, socStart = 0, socEnd = 100, kwhCharged = kwh, type = "DC", cost = cost)
    )

    @Test fun `the charges rule prices the trip under way at the energy in the pack`() = runBlocking {
        period(TRIP_RULE_CHARGES)
        charge(now - day, kwh = 40.0, cost = 20.0)

        assertEquals(0.50, calculator.tripRateAt(now), 1e-9)
    }

    @Test fun `the charges rule with no recent charge falls back to the home rate`() = runBlocking {
        period(TRIP_RULE_CHARGES)
        charge(now - 90 * day, kwh = 40.0, cost = 20.0)

        assertEquals(0.10, calculator.tripRateAt(now), 1e-9)
    }

    @Test fun `a fixed rule reads straight off the period`() = runBlocking {
        period(TRIP_RULE_DC)
        charge(now - day, kwh = 40.0, cost = 4.0)

        assertEquals(0.50, calculator.tripRateAt(now), 1e-9)
    }

    @Test fun `a custom number is the rate`() = runBlocking {
        period("0.33")

        assertEquals(0.33, calculator.tripRateAt(now), 1e-9)
    }
}
