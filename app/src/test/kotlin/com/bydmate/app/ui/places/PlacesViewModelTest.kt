package com.bydmate.app.ui.places

import com.bydmate.app.data.local.dao.PlaceDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.PlaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlacesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Fake DAOs ────────────────────────────────────────────────────────────

    private class FakePlaceDao(
        private val placesFlow: MutableStateFlow<List<PlaceEntity>>
    ) : PlaceDao {
        val deletedIds = mutableListOf<Long>()

        override fun getAll(): Flow<List<PlaceEntity>> = placesFlow
        override suspend fun getAllSnapshot() = placesFlow.value
        override suspend fun getById(id: Long) = placesFlow.value.firstOrNull { it.id == id }
        override suspend fun insert(place: PlaceEntity): Long {
            val newId = (placesFlow.value.maxOfOrNull { it.id } ?: 0L) + 1L
            placesFlow.value = placesFlow.value + place.copy(id = newId)
            return newId
        }
        override suspend fun update(place: PlaceEntity) {
            placesFlow.value = placesFlow.value.map { if (it.id == place.id) place else it }
        }
        override suspend fun delete(place: PlaceEntity) {
            deletedIds += place.id
            placesFlow.value = placesFlow.value.filter { it.id != place.id }
        }
        override suspend fun getCount() = placesFlow.value.size
    }

    private class FakeRuleDao(
        // placeId -> rule count
        private val usageCounts: Map<Long, Int> = emptyMap()
    ) : RuleDao {
        override suspend fun insert(rule: RuleEntity): Long = 0L
        override suspend fun update(rule: RuleEntity) {}
        override suspend fun delete(rule: RuleEntity) {}
        override fun getAll(): Flow<List<RuleEntity>> = MutableStateFlow(emptyList())
        override suspend fun getEnabled(): List<RuleEntity> = emptyList()
        override suspend fun getById(id: Long): RuleEntity? = null
        override suspend fun updateLastTriggered(id: Long, ts: Long) {}
        override suspend fun setEnabled(id: Long, enabled: Boolean) {}
        override suspend fun getCount(): Int = 0
        override suspend fun countRulesUsingPlace(placeId: Long): Int = usageCounts[placeId] ?: 0
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun makePlace(id: Long, name: String = "Place $id") = PlaceEntity(
        id = id, name = name, lat = 55.0, lon = 37.0, radiusM = 50
    )

    private fun buildVm(
        places: List<PlaceEntity> = emptyList(),
        usageCounts: Map<Long, Int> = emptyMap()
    ): Triple<PlacesViewModel, FakePlaceDao, MutableStateFlow<List<PlaceEntity>>> {
        val flow = MutableStateFlow(places)
        val placeDao = FakePlaceDao(flow)
        val ruleDao = FakeRuleDao(usageCounts)
        val repo = PlaceRepository(placeDao, ruleDao)
        val vm = PlacesViewModel(repo)
        return Triple(vm, placeDao, flow)
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `delete with zero usage calls repository delete`() = runTest {
        val place = makePlace(1L)
        val (vm, placeDao) = buildVm(places = listOf(place), usageCounts = mapOf(1L to 0))
        advanceUntilIdle()

        vm.delete(place)
        advanceUntilIdle()

        assertTrue("delete should be called", placeDao.deletedIds.contains(1L))
    }

    @Test
    fun `delete with non-zero usage does NOT call repository delete`() = runTest {
        val place = makePlace(1L)
        val (vm, placeDao) = buildVm(places = listOf(place), usageCounts = mapOf(1L to 2))
        advanceUntilIdle()

        vm.delete(place)
        advanceUntilIdle()

        assertTrue("delete must NOT be called", placeDao.deletedIds.isEmpty())
    }

    @Test
    fun `delete with non-zero usage emits deleteBlocked with correct count`() = runTest {
        val place = makePlace(1L)
        val (vm) = buildVm(places = listOf(place), usageCounts = mapOf(1L to 3))
        advanceUntilIdle()

        var blockedCount: Int? = null
        val collectJob = launch(testDispatcher) {
            blockedCount = vm.deleteBlocked.first()
        }

        vm.delete(place)
        advanceUntilIdle()
        collectJob.cancel()

        assertEquals(3, blockedCount)
    }

    @Test
    fun `usageCounts populated after init`() = runTest {
        val places = listOf(makePlace(1L), makePlace(2L))
        val (vm) = buildVm(places = places, usageCounts = mapOf(1L to 1, 2L to 0))
        advanceUntilIdle()

        val counts = vm.usageCounts.value
        assertEquals(1, counts[1L])
        assertEquals(0, counts[2L])
    }

    @Test
    fun `delete of unused place removes it from the list`() = runTest {
        val place = makePlace(1L)
        val (vm, _, flow) = buildVm(places = listOf(place), usageCounts = emptyMap())
        advanceUntilIdle()

        vm.delete(place)
        advanceUntilIdle()

        assertTrue(flow.value.isEmpty())
    }
}
