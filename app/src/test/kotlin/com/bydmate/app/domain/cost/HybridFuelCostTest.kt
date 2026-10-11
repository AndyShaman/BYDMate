package com.bydmate.app.domain.cost

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_HOME
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DM-i (#184): the litre price lives in the tariff periods. A trip's fuel is priced by the period
 * of its own date, so a new period re-prices only from its start and never rewrites history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HybridFuelCostTest {

    private lateinit var db: AppDatabase
    private lateinit var calculator: CostCalculator

    private val day = 24 * 60 * 60_000L
    private val septemberStart = 1_756_684_800_000L // 01.09.2026
    private val octoberStart = 1_759_276_800_000L   // 01.10.2026
    private val novemberStart = 1_761_955_200_000L  // 01.11.2026

    @Before fun setup() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries().build()
            db.settingsDao().set(SettingEntity(SettingsRepository.KEY_BATTERY_CAPACITY, "72.9"))
            val settings = SettingsRepository(db.settingsDao(), LocalePreferences(context))
            calculator = CostCalculator(db.tariffPeriodDao(), db.chargeDao(), db.tripDao(), settings)
            db.tariffPeriodDao().insert(period(septemberStart, fuel = 2.00))
        }
    }

    @After fun teardown() {
        db.close()
    }

    private fun period(start: Long, fuel: Double?, home: Double = 0.10) = TariffPeriodEntity(
        startTs = start, homeRate = home, dcRate = 0.50,
        acLossPct = 0.0, dcLossPct = 0.0, tripRule = TRIP_RULE_HOME, fuelPricePerLiter = fuel,
    )

    private suspend fun insert(
        start: Long,
        kwh: Double? = 6.0,
        liters: Double? = 2.8,
        cost: Double? = null,
        fuelCost: Double? = null,
    ): Long = db.tripDao().insert(
        TripEntity(
            startTs = start, endTs = start + 60_000L, distanceKm = 40.0,
            kwhConsumed = kwh, fuelLiters = liters, cost = cost, fuelCost = fuelCost, source = "energydata",
        )
    )

    private suspend fun trip(id: Long): TripEntity = db.tripDao().getById(id)!!

    private suspend fun priceAll(vararg ids: Long) {
        calculator.priceUncostedTrips(ids.map { trip(it) })
    }

    @Test fun `a new period re-prices the fuel only from its own start`() = runBlocking {
        val september = insert(septemberStart + 5 * day)
        val october = insert(octoberStart + 5 * day)
        priceAll(september, october)
        assertEquals(5.60, trip(september).fuelCost!!, 1e-9)       // 2.8 L × 2.00
        assertEquals(5.60, trip(october).fuelCost!!, 1e-9)

        val b = period(octoberStart, fuel = 2.50)
        db.tariffPeriodDao().insert(b)
        calculator.recalculate(b.startTs, Long.MAX_VALUE)

        assertEquals(5.60, trip(september).fuelCost!!, 1e-9)
        assertEquals(0.6 + 5.60, trip(september).cost!!, 1e-9)
        assertEquals(7.00, trip(october).fuelCost!!, 1e-9)         // 2.8 L × 2.50
        assertEquals(0.6 + 7.00, trip(october).cost!!, 1e-9)
    }

    @Test fun `a period without a litre price leaves the cost electricity only`() = runBlocking {
        db.tariffPeriodDao().insert(period(novemberStart, fuel = null))
        val stale = insert(novemberStart + day, cost = 0.6 + 7.0, fuelCost = 7.0)
        val fresh = insert(novemberStart + 2 * day)

        calculator.recalculate(novemberStart, Long.MAX_VALUE)
        priceAll(fresh)

        assertNull(trip(stale).fuelCost)
        assertEquals(0.6, trip(stale).cost!!, 1e-9)
        assertNull(trip(fresh).fuelCost)
        assertEquals(0.6, trip(fresh).cost!!, 1e-9)
    }

    @Test fun `litres without kWh in a priced period cost the fuel alone`() = runBlocking {
        val id = insert(septemberStart + day, kwh = null)

        priceAll(id)
        assertEquals(5.60, trip(id).fuelCost!!, 1e-9)
        assertEquals(5.60, trip(id).cost!!, 1e-9)

        db.tariffPeriodDao().insert(period(septemberStart, fuel = 2.50))
        calculator.recalculate(0L, Long.MAX_VALUE)
        assertEquals(7.00, trip(id).fuelCost!!, 1e-9)
        assertEquals(7.00, trip(id).cost!!, 1e-9)
    }

    @Test fun `litres without kWh in a period without a price lose their cost`() = runBlocking {
        db.tariffPeriodDao().insert(period(novemberStart, fuel = null))
        val stale = insert(novemberStart + day, kwh = null, cost = 7.0, fuelCost = 7.0)
        val fresh = insert(novemberStart + 2 * day, kwh = null)

        calculator.recalculate(novemberStart, Long.MAX_VALUE)
        priceAll(fresh)

        assertNull(trip(stale).fuelCost)
        assertNull("nothing but fuel was ever priced", trip(stale).cost)
        assertNull(trip(fresh).fuelCost)
        assertNull(trip(fresh).cost)
    }

    @Test fun `EV trips are priced exactly as before on import and on recalculation`() = runBlocking {
        val ev = insert(septemberStart + day, liters = null)
        val zero = insert(septemberStart + 2 * day, liters = 0.0)

        priceAll(ev, zero)
        assertEquals(6.0 * 0.10, trip(ev).cost!!, 0.0)
        assertNull(trip(ev).fuelCost)
        assertEquals(6.0 * 0.10, trip(zero).cost!!, 0.0)
        assertNull(trip(zero).fuelCost)

        db.tariffPeriodDao().insert(period(septemberStart, fuel = 2.50, home = 0.20))
        calculator.recalculate(0L, Long.MAX_VALUE)
        assertEquals(6.0 * 0.20, trip(ev).cost!!, 0.0)
        assertNull(trip(ev).fuelCost)
        assertEquals(6.0 * 0.20, trip(zero).cost!!, 0.0)
        assertNull(trip(zero).fuelCost)
    }
}
