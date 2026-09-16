package com.bydmate.app.domain.cost

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_HOME
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * The scenario Andy described: a tariff starting 1 September is entered on 16 September and
 * everything from 1 September onwards has to recount, while August keeps its old prices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RetroactiveRecalcTest {

    private lateinit var db: AppDatabase
    private lateinit var calculator: CostCalculator

    private fun ts(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis

    private val aug10 = ts(2026, 8, 10)
    private val sep1 = ts(2026, 9, 1)
    private val sep5 = ts(2026, 9, 5)
    private val sep10 = ts(2026, 9, 10)

    @Before fun setup() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries().build()
            db.settingsDao().set(SettingEntity(SettingsRepository.KEY_BATTERY_CAPACITY, "72.9"))
            val settings = SettingsRepository(db.settingsDao(), LocalePreferences(context))
            calculator = CostCalculator(db.tariffPeriodDao(), db.chargeDao(), db.tripDao(), settings)
            // The period that was in force all summer: no losses, so the seeded costs are round.
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

    private suspend fun insertCharge(
        startTs: Long,
        kwh: Double,
        cost: Double,
        manual: Boolean = false,
    ): Long = db.chargeDao().insert(
        ChargeEntity(
            startTs = startTs, endTs = startTs, socStart = 20, socEnd = 60,
            kwhCharged = kwh, type = "AC", cost = cost, costManual = manual,
        )
    )

    private suspend fun costOf(id: Long): Double = db.chargeDao().getById(id)!!.cost!!

    @Test fun `a period added mid-month re-prices only what comes after its date`() = runBlocking {
        val august = insertCharge(aug10, kwh = 10.0, cost = 1.0)
        val earlySeptember = insertCharge(sep5, kwh = 10.0, cost = 1.0)
        val lateSeptember = insertCharge(sep10, kwh = 10.0, cost = 1.0)

        // On 16 September the driver adds "from 1 September the kWh costs 0.30, AC losses 10%".
        db.tariffPeriodDao().insert(
            TariffPeriodEntity(
                startTs = sep1, homeRate = 0.30, dcRate = 0.80,
                acLossPct = 10.0, dcLossPct = 5.0, tripRule = TRIP_RULE_HOME,
            )
        )
        val result = calculator.recalculate(sep1, Long.MAX_VALUE)

        // 10 kWh into the pack at 10% losses = 11.11 kWh paid, at 0.30 = 3.33
        val expected = 10.0 / 0.9 * 0.30
        assertEquals(expected, costOf(earlySeptember), 1e-9)
        assertEquals(expected, costOf(lateSeptember), 1e-9)
        assertEquals(1.0, costOf(august), 1e-9)
        assertEquals(2, result.charges)
    }

    @Test fun `a charge priced by hand is never re-priced`() = runBlocking {
        val manual = insertCharge(sep5, kwh = 10.0, cost = 42.0, manual = true)
        db.tariffPeriodDao().insert(
            TariffPeriodEntity(startTs = sep1, homeRate = 0.30, dcRate = 0.80, tripRule = TRIP_RULE_HOME)
        )

        val result = calculator.recalculate(sep1, Long.MAX_VALUE)

        assertEquals(42.0, costOf(manual), 1e-9)
        assertEquals(0, result.charges)
        assertEquals(1, result.skippedManual)
    }

    @Test fun `a meter reading prices the session instead of the loss estimate`() = runBlocking {
        val id = db.chargeDao().insert(
            ChargeEntity(startTs = sep5, kwhCharged = 10.0, type = "AC", meterKwh = 12.0)
        )
        db.tariffPeriodDao().insert(
            TariffPeriodEntity(
                startTs = sep1, homeRate = 0.30, dcRate = 0.80,
                acLossPct = 10.0, dcLossPct = 5.0, tripRule = TRIP_RULE_HOME,
            )
        )

        calculator.recalculate(sep1, Long.MAX_VALUE)

        assertEquals(12.0 * 0.30, costOf(id), 1e-9)
    }

    @Test fun `an empty table seeds the first period from the flat settings`() = runBlocking {
        db.tariffPeriodDao().getAllAsc().forEach { db.tariffPeriodDao().delete(it) }
        db.settingsDao().set(SettingEntity(SettingsRepository.KEY_HOME_TARIFF, "0.42"))

        val schedule = calculator.schedule()

        assertEquals(1, schedule.periods.size)
        assertEquals(0.42, schedule.periods.first().homeRate, 1e-9)
        assertEquals(1, db.tariffPeriodDao().count())
    }
}
