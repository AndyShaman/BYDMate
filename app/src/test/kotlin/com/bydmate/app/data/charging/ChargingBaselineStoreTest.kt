package com.bydmate.app.data.charging

import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargingBaselineStoreTest {

    private class FakeChargeDao(private val maxKwh: Double?) : ChargeDao {
        override suspend fun insert(charge: ChargeEntity): Long = 0L
        override suspend fun update(charge: ChargeEntity) {}
        override fun getAll(): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): ChargeEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary =
            ChargeSummary(0, 0.0, 0.0)
        override fun getLastCharge(): Flow<ChargeEntity?> = flowOf(null)
        override suspend fun getLastSuspendedCharge(): ChargeEntity? = null
        override suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity> = emptyList()
        override suspend fun getLastChargeSync(): ChargeEntity? = null
        override suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity> = emptyList()
        override suspend fun getMaxLifetimeKwhAtFinish(): Double? = maxKwh
    }

    private class FakeSettings(
        var baseline: Pair<Double, Long>? = null
    ) : SettingsRepository(FakeSettingsDao()) {
        override suspend fun getAutoserviceBaseline(): Pair<Double, Long>? = baseline
        override suspend fun setAutoserviceBaseline(kwh: Double, ts: Long) {
            baseline = kwh to ts
        }
    }

    private class FakeSettingsDao : com.bydmate.app.data.local.dao.SettingsDao {
        private val map = mutableMapOf<String, String?>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(setting: com.bydmate.app.data.local.entity.SettingEntity) {
            map[setting.key] = setting.value
        }
        override fun getAll(): Flow<List<com.bydmate.app.data.local.entity.SettingEntity>> = flowOf(emptyList())
    }

    private object NullChargePointDao : com.bydmate.app.data.local.dao.ChargePointDao {
        override suspend fun insertAll(points: List<com.bydmate.app.data.local.entity.ChargePointEntity>) {}
        override suspend fun getByChargeId(chargeId: Long): List<com.bydmate.app.data.local.entity.ChargePointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
    }

    private fun store(maxFromDb: Double?, baseline: Pair<Double, Long>?): ChargingBaselineStore {
        val dao = FakeChargeDao(maxFromDb)
        val chargeRepo = ChargeRepository(dao, NullChargePointDao)
        val settings = FakeSettings(baseline)
        return ChargingBaselineStore(chargeRepo, settings)
    }

    @Test
    fun `prefers DB MAX over k-v fallback`() = runTest {
        val s = store(maxFromDb = 600.5, baseline = 100.0 to 1L)
        assertEquals(600.5, s.getBaseline()!!, 0.001)
    }

    @Test
    fun `falls back to k-v when DB has no autoservice rows`() = runTest {
        val s = store(maxFromDb = null, baseline = 250.0 to 1700000000000L)
        assertEquals(250.0, s.getBaseline()!!, 0.001)
    }

    @Test
    fun `returns null when DB and k-v are both empty (cold start)`() = runTest {
        val s = store(maxFromDb = null, baseline = null)
        assertNull(s.getBaseline())
    }

    @Test
    fun `setBaseline writes to k-v store`() = runTest {
        val settings = FakeSettings(baseline = null)
        val dao = FakeChargeDao(null)
        val s = ChargingBaselineStore(ChargeRepository(dao, NullChargePointDao), settings)

        s.setBaseline(kwh = 700.0, ts = 1700100000000L)

        assertEquals(700.0 to 1700100000000L, settings.baseline)
    }
}
