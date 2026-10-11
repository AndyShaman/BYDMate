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

/** DM-i (#184): the fuel part of a trip's cost survives a tariff change and follows the litre price. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HybridFuelCostTest {

    private lateinit var db: AppDatabase
    private lateinit var calculator: CostCalculator

    private val tripStart = 5_000_000L

    @Before fun setup() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries().build()
            db.settingsDao().set(SettingEntity(SettingsRepository.KEY_BATTERY_CAPACITY, "72.9"))
            val settings = SettingsRepository(db.settingsDao(), LocalePreferences(context))
            calculator = CostCalculator(db.tariffPeriodDao(), db.chargeDao(), db.tripDao(), settings)
            db.tariffPeriodDao().insert(
                TariffPeriodEntity(
                    startTs = 0L, homeRate = 0.10, dcRate = 0.50,
                    acLossPct = 0.0, dcLossPct = 0.0, tripRule = TRIP_RULE_HOME,
                )
            )
        }
    }

    @After fun teardown() {
        db.close()
    }

    private suspend fun insert(
        kwh: Double? = 6.0,
        liters: Double? = 2.8,
        cost: Double? = 0.6 + 7.14,
        fuelCost: Double? = 7.14,
    ): Long = db.tripDao().insert(
        TripEntity(
            startTs = tripStart, endTs = tripStart + 60_000L, distanceKm = 40.0,
            kwhConsumed = kwh, fuelLiters = liters, cost = cost, fuelCost = fuelCost, source = "energydata",
        )
    )

    private suspend fun trip(id: Long): TripEntity = db.tripDao().getById(id)!!

    @Test fun `a tariff change keeps the fuel part of a hybrid trip`() = runBlocking {
        val hybrid = insert()
        val ev = insert(liters = null, cost = 0.6, fuelCost = null)
        db.tariffPeriodDao().insert(
            TariffPeriodEntity(
                startTs = 1L, homeRate = 0.20, dcRate = 0.50,
                acLossPct = 0.0, dcLossPct = 0.0, tripRule = TRIP_RULE_HOME,
            )
        )

        calculator.recalculate(0L, Long.MAX_VALUE)

        assertEquals(6.0 * 0.20 + 7.14, trip(hybrid).cost!!, 1e-9)
        assertEquals(7.14, trip(hybrid).fuelCost!!, 1e-9)
        assertEquals(6.0 * 0.20, trip(ev).cost!!, 0.0)
        assertNull(trip(ev).fuelCost)
    }

    @Test fun `a price entered later adds the fuel to a trip already priced by electricity`() = runBlocking {
        val id = insert(cost = 0.6, fuelCost = null)

        calculator.repriceFuel(2.55)

        assertEquals(7.14, trip(id).fuelCost!!, 1e-9)
        assertEquals(0.6 + 7.14, trip(id).cost!!, 1e-9)
    }

    @Test fun `a changed price swaps the fuel part and leaves the electricity alone`() = runBlocking {
        val id = insert()

        calculator.repriceFuel(3.0)

        assertEquals(8.4, trip(id).fuelCost!!, 1e-9)
        assertEquals(0.6 + 8.4, trip(id).cost!!, 1e-9)
    }

    @Test fun `a cleared price removes the fuel part`() = runBlocking {
        val withKwh = insert()
        val fuelOnly = insert(kwh = null, cost = 7.14)

        calculator.repriceFuel(null)

        assertNull(trip(withKwh).fuelCost)
        assertEquals(0.6, trip(withKwh).cost!!, 1e-9)
        assertNull(trip(fuelOnly).fuelCost)
        assertNull("no electricity was priced, so nothing is left", trip(fuelOnly).cost)
    }

    @Test fun `a trip with litres and no cost at all gets both parts`() = runBlocking {
        val id = insert(cost = null, fuelCost = null)

        calculator.repriceFuel(2.55)

        assertEquals(7.14, trip(id).fuelCost!!, 1e-9)
        assertEquals(0.6 + 7.14, trip(id).cost!!, 1e-9)
    }

    @Test fun `a trip without litres is not touched by the litre price`() = runBlocking {
        val ev = insert(liters = null, cost = 0.6, fuelCost = null)
        val zero = insert(liters = 0.0, cost = 0.6, fuelCost = null)
        val before = trip(ev)
        val beforeZero = trip(zero)

        calculator.repriceFuel(2.55)
        calculator.repriceFuel(null)

        assertEquals(before, trip(ev))
        assertEquals(beforeZero, trip(zero))
    }
}
