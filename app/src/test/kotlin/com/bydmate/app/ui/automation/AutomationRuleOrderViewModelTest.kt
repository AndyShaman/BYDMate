package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #249: a dragged rule order through the real AutomationViewModel and SettingsRepository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationRuleOrderViewModelTest {

    /** The settings table in memory, shared by every view model of a test like the real one. */
    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        var writes = 0
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override suspend fun set(setting: SettingEntity) { writes++; map[setting.key] = setting.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private val testDispatcher = StandardTestDispatcher()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val settingsDao = FakeSettingsDao()
    private val settings = SettingsRepository(settingsDao, mockk<LocalePreferences>(relaxed = true))

    /** What RuleDao.getAll emits: newest first. */
    private val dbRules = MutableStateFlow(listOf(rule(5), rule(4), rule(3), rule(2)))

    private fun rule(id: Long) = RuleEntity(id = id, name = "r$id", triggers = "[]", actions = "[]", createdAt = id)

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        ctx.getSharedPreferences("automation", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("templates_inserted", true).commit()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): AutomationViewModel {
        val ruleDao = mockk<RuleDao>(relaxed = true)
        val ruleLogDao = mockk<RuleLogDao>(relaxed = true)
        val placeRepository = mockk<PlaceRepository>(relaxed = true)
        every { ruleDao.getAll() } returns dbRules
        every { ruleLogDao.getRecent(any()) } returns flowOf(emptyList())
        every { placeRepository.getAll() } returns MutableStateFlow<List<PlaceEntity>>(emptyList())
        return AutomationViewModel(
            ruleDao = ruleDao,
            ruleLogDao = ruleLogDao,
            placeRepository = placeRepository,
            vehicleApi = mockk(relaxed = true),
            actionDispatcher = mockk(relaxed = true),
            settingsRepository = settings,
            context = ctx,
        ).also { testDispatcher.scheduler.advanceUntilIdle() }
    }

    private fun AutomationViewModel.shownIds() = uiState.value.rules.map { it.id }

    @Test fun `without a saved order the rules stay newest first`() {
        assertEquals(listOf(5L, 4L, 3L, 2L), vm().shownIds())
    }

    @Test fun `a drop shows the new order at once and saves it once`() {
        val vm = vm()
        vm.moveRule(moved = 2, target = 5)
        assertEquals(listOf(2L, 5L, 4L, 3L), vm.shownIds())
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("2,5,4,3", settingsDao.map[SettingsRepository.KEY_AUTOMATION_RULE_ORDER])
        assertEquals(1, settingsDao.writes)
    }

    @Test fun `the order survives a new view model, as after an app restart`() {
        vm().moveRule(moved = 5, target = 2)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(4L, 3L, 2L, 5L), vm().shownIds())
    }

    @Test fun `a new rule goes on top and a deleted one drops out of the saved order`() {
        val vm = vm()
        vm.moveRule(moved = 2, target = 5)
        testDispatcher.scheduler.advanceUntilIdle()

        dbRules.value = listOf(rule(6), rule(5), rule(3), rule(2))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(6L, 2L, 5L, 3L), vm.shownIds())
        assertEquals(listOf(6L, 2L, 5L, 3L), vm().shownIds())
    }

    @Test fun `a drop in its own place writes nothing`() {
        val vm = vm()
        vm.moveRule(moved = 4, target = 4)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(5L, 4L, 3L, 2L), vm.shownIds())
        assertEquals(0, settingsDao.writes)
    }
}
