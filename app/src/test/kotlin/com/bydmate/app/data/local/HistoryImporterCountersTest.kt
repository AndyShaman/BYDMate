package com.bydmate.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.CounterSnapshotEntity
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_HOME
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.kindMonitor
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.cost.CostCalculator
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DM-i (#184): energydata trips get litres only from a snapshot pair at their own edges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistoryImporterCountersTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository

    private val start = System.currentTimeMillis() - 2 * 60 * 60_000L
    private val end = start + 40 * 60_000L

    @Before fun setup() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries().build()
            settings = SettingsRepository(db.settingsDao(), LocalePreferences(context))
            db.settingsDao().set(SettingEntity(SettingsRepository.KEY_BATTERY_CAPACITY, "72.9"))
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

    private fun importer(kind: VehicleKind) = HistoryImporter(
        context = ApplicationProvider.getApplicationContext(),
        energyDataReader = mockk(relaxed = true),
        tripRepository = mockk(relaxed = true),
        tripDao = db.tripDao(),
        tripPointDao = mockk(relaxed = true),
        idleDrainDao = mockk(relaxed = true),
        settingsRepository = settings,
        lastSessionRepository = mockk(relaxed = true),
        tripTombstoneDao = mockk(relaxed = true),
        costCalculator = CostCalculator(db.tariffPeriodDao(), db.chargeDao(), db.tripDao(), settings),
        odometerMarks = mockk(relaxed = true),
        vehicleKind = kindMonitor(kind),
        counterSnapshotDao = db.counterSnapshotDao(),
        counterSnapshotWriter = com.bydmate.app.data.trips.CounterSnapshotWriter(db.counterSnapshotDao()),
        lastStateDao = db.lastStateDao(),
    )

    private suspend fun insertTrip(kwh: Double? = 6.0, source: String = "energydata"): Long =
        db.tripDao().insert(
            TripEntity(startTs = start, endTs = end, distanceKm = 40.0, kwhConsumed = kwh, source = source)
        )

    private suspend fun snapshot(ts: Long, fuel: Double, hev: Double) {
        db.counterSnapshotDao().insert(
            CounterSnapshotEntity(
                ts = ts, fuelTotal = fuel, mileageHev = hev, mileageEv = 9240.0,
                odometer = null, reason = CounterSnapshotEntity.REASON_PERIODIC,
            )
        )
    }

    private suspend fun trip(id: Long): TripEntity = db.tripDao().getById(id)!!

    /** Puts a litre price into the one period the setup created. */
    private suspend fun setFuelPrice(price: Double) {
        db.tariffPeriodDao().update(db.tariffPeriodDao().getAllAsc().single().copy(fuelPricePerLiter = price))
    }

    @Test fun `a snapshot pair inside the window gives the trip its litres`() = runBlocking {
        val id = insertTrip()
        snapshot(start - 60_000L, 302.4, 3812.0)
        snapshot(start - 200_000L, 290.0, 3700.0)
        snapshot(end + 30_000L, 305.2, 3850.0)

        importer(VehicleKind.HYBRID).attachCounters()

        val t = trip(id)
        assertEquals(2.8, t.fuelLiters!!, 1e-9)
        assertEquals(38.0, t.hevKm!!, 1e-9)
        assertEquals(302.4, t.fuelTotalStart!!, 1e-9)
        assertEquals(305.2, t.fuelTotalEnd!!, 1e-9)
        assertEquals(3812.0, t.mileageHevStart!!, 1e-9)
        assertEquals(3850.0, t.mileageHevEnd!!, 1e-9)
    }

    @Test fun `one side missing leaves the trip without litres`() = runBlocking {
        val id = insertTrip()
        snapshot(start + 10_000L, 302.4, 3812.0)

        importer(VehicleKind.HYBRID).attachCounters()

        val t = trip(id)
        assertNull(t.fuelLiters)
        assertNull(t.hevKm)
        assertNull(t.fuelTotalStart)
        assertNull(t.fuelTotalEnd)
    }

    @Test fun `a snapshot 100 s away is not used`() = runBlocking {
        val id = insertTrip()
        snapshot(start - 100_000L, 302.4, 3812.0)
        snapshot(end, 305.2, 3850.0)

        importer(VehicleKind.HYBRID).attachCounters()

        assertNull(trip(id).fuelLiters)
        assertNull(trip(id).fuelTotalStart)
    }

    @Test fun `a trip older than 7 days is not offered counters`() = runBlocking {
        val oldStart = System.currentTimeMillis() - 8 * 24 * 60 * 60_000L
        val oldEnd = oldStart + 40 * 60_000L
        val id = db.tripDao().insert(
            TripEntity(startTs = oldStart, endTs = oldEnd, distanceKm = 40.0, kwhConsumed = 6.0, source = "energydata")
        )
        snapshot(oldStart, 302.4, 3812.0)
        snapshot(oldEnd, 305.2, 3850.0)

        val since = System.currentTimeMillis() - HistoryImporter.COUNTER_ATTACH_LOOKBACK_MS
        assertEquals(emptyList<TripEntity>(), db.tripDao().getEnergyDataTripsWithoutCounters(since))
        importer(VehicleKind.HYBRID).attachCounters()

        assertNull(trip(id).fuelLiters)
        assertNull(trip(id).fuelTotalStart)
    }

    @Test fun `a hybrid trip with litres but no kWh is priced by its fuel alone`() = runBlocking {
        setFuelPrice(2.55)
        val id = insertTrip(kwh = null)
        snapshot(start, 302.4, 3812.0)
        snapshot(end, 305.2, 3850.0)

        val importer = importer(VehicleKind.HYBRID)
        importer.attachCounters()
        importer.calculateMissingCosts()

        val t = trip(id)
        assertEquals(7.14, t.fuelCost!!, 1e-9)
        assertEquals(7.14, t.cost!!, 1e-9)
        assertNull(t.kwhConsumed)
    }

    @Test fun `the EV query still skips trips without kWh, litres or not`() = runBlocking {
        val withKwh = insertTrip(kwh = 6.0)
        val fuelOnly = db.tripDao().insert(
            TripEntity(startTs = start, endTs = end, distanceKm = 40.0, fuelLiters = 2.8, source = "energydata")
        )
        insertTrip(kwh = null)

        assertEquals(listOf(withKwh), db.tripDao().getTripsWithoutCost().map { it.id })
        assertEquals(setOf(withKwh, fuelOnly), db.tripDao().getHybridTripsWithoutCost().map { it.id }.toSet())

        setFuelPrice(2.55)
        importer(VehicleKind.EV).calculateMissingCosts()
        assertNull("an EV never prices a litres-only row", trip(fuelOnly).cost)
        assertEquals(0.6, trip(withKwh).cost!!, 1e-9)
    }

    @Test fun `one runSync writes the cold_start row before it attaches the counters`() = runBlocking {
        val id = insertTrip()
        snapshot(start, 302.4, 3812.0)
        // The process died with the car: no ignition_off row, only the last ON tick in last_state.
        db.lastStateDao().upsert(
            LastStateEntity(id = 1, ts = end + 30_000L, onTs = end, onFuelTotal = 305.2, onMileageHev = 3850.0)
        )

        importer(VehicleKind.HYBRID).runSync()

        assertEquals(CounterSnapshotEntity.REASON_COLD_START, db.counterSnapshotDao().nearest(end, 0L)!!.reason)
        assertEquals(2.8, trip(id).fuelLiters!!, 1e-9)
        assertEquals(305.2, trip(id).fuelTotalEnd!!, 1e-9)
    }

    @Test fun `a null ignition_on row at the start is no reason to take the previous trip's row`() = runBlocking {
        val id = insertTrip()
        snapshot(start - 70_000L, 302.4, 3812.0)   // the previous trip's tail
        db.counterSnapshotDao().insert(
            CounterSnapshotEntity(
                ts = start, fuelTotal = null, mileageHev = null, mileageEv = null,
                odometer = null, reason = CounterSnapshotEntity.REASON_IGNITION_ON,
            )
        )
        db.counterSnapshotDao().insert(
            CounterSnapshotEntity(
                ts = end, fuelTotal = 305.2, mileageHev = 3850.0, mileageEv = null,
                odometer = null, reason = CounterSnapshotEntity.REASON_IGNITION_OFF,
            )
        )

        importer(VehicleKind.HYBRID).attachCounters()

        assertNull(trip(id).fuelLiters)
        assertNull("stays retryable", trip(id).fuelTotalStart)
    }

    @Test fun `an EV car skips the step and writes nothing`() = runBlocking {
        val id = insertTrip()
        snapshot(start, 302.4, 3812.0)
        snapshot(end, 305.2, 3850.0)
        val before = trip(id)

        importer(VehicleKind.EV).attachCounters()
        importer(VehicleKind.UNKNOWN).attachCounters()

        assertEquals(before, trip(id))
    }

    @Test fun `with a price the trip cost is electricity plus fuel`() = runBlocking {
        setFuelPrice(2.55)
        val id = insertTrip(kwh = 6.0)
        snapshot(start, 302.4, 3812.0)
        snapshot(end, 305.2, 3850.0)

        val importer = importer(VehicleKind.HYBRID)
        importer.attachCounters()
        importer.calculateMissingCosts()

        val t = trip(id)
        assertEquals(7.14, t.fuelCost!!, 1e-9)            // 2.8 L × 2.55
        assertEquals(0.6 + 7.14, t.cost!!, 1e-9)          // 6 kWh × 0.10 + fuel
    }

    @Test fun `litres attached after the first sync are priced from the trip's own period`() = runBlocking {
        setFuelPrice(2.55)
        // A period starting after the trip: its price must not reach it.
        db.tariffPeriodDao().insert(
            TariffPeriodEntity(
                startTs = end + 60 * 60_000L, homeRate = 0.10, dcRate = 0.50,
                acLossPct = 0.0, dcLossPct = 0.0, tripRule = TRIP_RULE_HOME, fuelPricePerLiter = 9.99,
            )
        )
        val id = insertTrip(kwh = 6.0)
        val importer = importer(VehicleKind.HYBRID)

        // First sync: no snapshot pair yet, the trip is priced by its electricity alone.
        importer.attachCounters()
        importer.calculateMissingCosts()
        assertEquals(0.6, trip(id).cost!!, 1e-9)
        assertNull(trip(id).fuelCost)

        // Second sync: the pair is there now, the litres land and get their price.
        snapshot(start, 302.4, 3812.0)
        snapshot(end, 305.2, 3850.0)
        importer.attachCounters()
        importer.calculateMissingCosts()

        val t = trip(id)
        assertEquals(2.8, t.fuelLiters!!, 1e-9)
        assertEquals(7.14, t.fuelCost!!, 1e-9)
        assertEquals(0.6 + 7.14, t.cost!!, 1e-9)

        // A third sync leaves a fully priced trip as it is.
        importer.calculateMissingCosts()
        assertEquals(t, trip(id))
    }

    @Test fun `a trip that already has both cost parts is not offered again`() = runBlocking {
        db.tripDao().insert(
            TripEntity(startTs = start, endTs = end, distanceKm = 40.0, kwhConsumed = 6.0, fuelLiters = 2.8,
                cost = 7.74, fuelCost = 7.14, source = "energydata")
        )
        val pending = db.tripDao().insert(
            TripEntity(startTs = start, endTs = end, distanceKm = 40.0, kwhConsumed = 6.0, fuelLiters = 2.8,
                cost = 0.6, source = "energydata")
        )
        val zeroLitres = db.tripDao().insert(
            TripEntity(startTs = start, endTs = end, distanceKm = 40.0, kwhConsumed = 6.0, fuelLiters = 0.0,
                cost = 0.6, source = "energydata")
        )

        val offered = db.tripDao().getHybridTripsWithoutCost().map { it.id }.toSet()
        assertEquals(setOf(pending), offered)
        assertNull(trip(zeroLitres).fuelCost)
    }

    @Test fun `without a price the cost is electricity only`() = runBlocking {
        val id = insertTrip(kwh = 6.0)
        snapshot(start, 302.4, 3812.0)
        snapshot(end, 305.2, 3850.0)

        val importer = importer(VehicleKind.HYBRID)
        importer.attachCounters()
        importer.calculateMissingCosts()

        val t = trip(id)
        assertNull(t.fuelCost)
        assertEquals(0.6, t.cost!!, 1e-9)
    }

    @Test fun `an EV trip costs exactly what it cost before, period price set or not`() = runBlocking {
        setFuelPrice(2.55)
        val ev = insertTrip(kwh = 6.0)
        importer(VehicleKind.EV).calculateMissingCosts()

        val t = trip(ev)
        assertEquals(6.0 * 0.10, t.cost!!, 0.0)
        assertNull(t.fuelCost)
    }
}
