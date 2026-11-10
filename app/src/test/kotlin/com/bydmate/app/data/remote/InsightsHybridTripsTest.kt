package com.bydmate.app.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a DM-i the kWh/100 rules rate only the trips that burned no fuel; on an EV nothing changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class InsightsHybridTripsTest {

    private val hour = 3_600_000L

    /**
     * Five trips known electric (0 L) at 15 kWh/100, two engine trips whose kWh would read 40 kWh/100,
     * and one trip whose litres are unknown at 30 kWh/100.
     */
    private fun trips(now: Long): List<TripEntity> {
        val electric = (1..5).map { i ->
            TripEntity(startTs = now - i * hour, distanceKm = 20.0, kwhConsumed = 3.0, kwhPer100km = 15.0, fuelLiters = 0.0)
        }
        val engine = (6..7).map { i ->
            TripEntity(startTs = now - i * hour, distanceKm = 50.0, kwhConsumed = 20.0, kwhPer100km = 40.0, fuelLiters = 2.5)
        }
        val unknown = TripEntity(startTs = now - 8 * hour, distanceKm = 10.0, kwhConsumed = 3.0, kwhPer100km = 30.0)
        return electric + engine + unknown
    }

    private fun manager(kind: VehicleKind?): InsightsManager {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val now = System.currentTimeMillis()
        val tripDao = mockk<TripDao>()
        coEvery { tripDao.getAllSnapshot() } returns trips(now)
        val settings = mockk<SettingsRepository>()
        coEvery { settings.getCurrency() } returns SettingsRepository.CURRENCIES.first()
        val monitor = kind?.let { k -> mockk<VehicleKindMonitor> { every { this@mockk.kind } returns MutableStateFlow(k) } }
        return InsightsManager(
            context = ctx,
            openRouterClient = OpenRouterClient(OkHttpClient()),
            tripDao = tripDao,
            idleDrainDao = mockk<IdleDrainDao>(relaxed = true),
            chargeDao = mockk<ChargeDao>(relaxed = true),
            settingsRepository = settings,
        ).also { m -> monitor?.let(m::injectVehicleKind) }
    }

    @Test fun `an EV rates every trip, exactly as without the car type`() = runTest {
        val before = manager(null).collectStats(7)!!
        assertEquals(before, manager(VehicleKind.EV).collectStats(7))
        assertEquals(before, manager(VehicleKind.UNKNOWN).collectStats(7))
        assertEquals(58.0 / 210.0 * 100, before.recentAvgCons, 1e-9)
        assertEquals(40.0, before.worstTripCons!!, 1e-9)
    }

    @Test fun `a hybrid rates kWh per 100 only on trips known to burn no fuel`() = runTest {
        val stats = manager(VehicleKind.HYBRID).collectStats(7)!!
        assertEquals(15.0, stats.recentAvgCons, 1e-9)
        assertEquals(15.0, stats.worstTripCons!!, 1e-9)
        // Distance and trip count still describe all driving.
        assertEquals(8, stats.recentTripCount)
        assertEquals(210.0, stats.recentKm, 1e-9)
    }

    @Test fun `the trip filter is the identity on an EV`() {
        val all = trips(0L)
        assertSame(all, InsightStatsAggregator.consumptionTrips(all, hybrid = false))
        assertEquals(5, InsightStatsAggregator.consumptionTrips(all, hybrid = true).size)
    }
}
