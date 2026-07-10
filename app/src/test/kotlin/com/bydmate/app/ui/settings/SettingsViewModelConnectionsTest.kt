package com.bydmate.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.LlmConnection
import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.backup.BackupManager
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.remote.OpenRouterModel
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SeatChannelStore
import com.bydmate.app.service.UpdateChecker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave J: connection state, selectors and the "Проверить" (test connection) action on
 * [SettingsViewModel]. Setup mirrors [SettingsViewModelTest]'s Robolectric/mockk factory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsViewModelConnectionsTest {

    private val testDispatcher = StandardTestDispatcher()
    private val seatChannelStore: SeatChannelStore = mockk(relaxed = true)
    private val helperClient: HelperClient = mockk(relaxed = true)
    private val helperBootstrap: HelperBootstrap = mockk(relaxed = true)
    private val agentOrchestrator: AgentOrchestrator = mockk(relaxed = true)
    private val llmConnectionResolver: LlmConnectionResolver = mockk(relaxed = true)
    private val openRouterClient: OpenRouterClient = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Fake DAOs (mirrors SettingsViewModelTest) ---

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private class StubTripDao : TripDao {
        override suspend fun insert(trip: TripEntity): Long = 0L
        override suspend fun update(trip: TripEntity) {}
        override fun getAll(): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): TripEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getTodaySummary(dayStart: Long, dayEnd: Long): TripSummary = TripSummary(0.0, 0.0)
        override fun getLastTrip(): Flow<TripEntity?> = flowOf(null)
        override fun getRecent(limit: Int): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getCount(): Int = 0
        override suspend fun getByBydId(bydId: Long): TripEntity? = null
        override suspend fun getTripsWithoutSoc(): List<TripEntity> = emptyList()
        override suspend fun getTripsWithoutCost(): List<TripEntity> = emptyList()
        override suspend fun getPeriodSummary(from: Long, to: Long): TripSummary = TripSummary(0.0, 0.0)
        override suspend fun getLiveTrips(): List<TripEntity> = emptyList()
        override suspend fun getByStartTsRange(minTs: Long, maxTs: Long): TripEntity? = null
        override suspend fun getAllSnapshot(): List<TripEntity> = emptyList()
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteZeroKmTrips(): Int = 0
        override suspend fun getTripsForCapacityEstimate(minSocDelta: Int, limit: Int): List<TripEntity> = emptyList()
        override suspend fun getRecentSummary(maxTrips: Int): TripSummary = TripSummary(0.0, 0.0)
        override suspend fun getRecentForEma(limit: Int): List<TripEntity> = emptyList()
        override suspend fun getForEmaSince(fromTs: Long): List<TripEntity> = emptyList()
        override suspend fun getRecentForEmaFiltered(minKm: Double, limit: Int): List<TripEntity> = emptyList()
    }

    private class StubTripPointDao : TripPointDao {
        override suspend fun insertAll(points: List<TripPointEntity>) {}
        override suspend fun getByTripId(tripId: Long): List<TripPointEntity> = emptyList()
        override suspend fun getByTripIds(tripIds: List<Long>): List<TripPointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun getAll(): List<TripPointEntity> = emptyList()
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
        override suspend fun getByTimeRange(from: Long, to: Long): List<TripPointEntity> = emptyList()
        override suspend fun attachToTrip(tripId: Long, from: Long, to: Long): Int = 0
        override suspend fun insert(point: TripPointEntity): Long = 0L
    }

    private class StubChargeDao : ChargeDao {
        override suspend fun insert(charge: ChargeEntity): Long = 0L
        override suspend fun update(charge: ChargeEntity) {}
        override fun getAll(): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): ChargeEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary = ChargeSummary(0, 0.0, 0.0)
        override fun getLastCharge(): Flow<ChargeEntity?> = flowOf(null)
        override suspend fun getLastSuspendedCharge(): ChargeEntity? = null
        override suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity> = emptyList()
        override suspend fun getLastChargeSync(): ChargeEntity? = null
        override suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity> = emptyList()
        override suspend fun getMaxLifetimeKwhAtFinish(): Double? = null
        override suspend fun getAllAutoserviceCharges(): List<ChargeEntity> = emptyList()
        override suspend fun hasLegacyCharges(): Boolean = false
        override suspend fun deleteEmpty(): Int = 0
        override suspend fun getCompletedSince(since: Long): List<ChargeEntity> = emptyList()
        override suspend fun deletePhantomAutoserviceRows(): Int = 0
        override suspend fun delete(charge: ChargeEntity) {}
    }

    private class StubChargePointDao : ChargePointDao {
        override suspend fun insertAll(points: List<ChargePointEntity>) {}
        override suspend fun getByChargeId(chargeId: Long): List<ChargePointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
    }

    private class StubIdleDrainDao : IdleDrainDao {
        override suspend fun insert(drain: IdleDrainEntity): Long = 0L
        override suspend fun update(drain: IdleDrainEntity) {}
        override fun getByDateRange(from: Long, to: Long): Flow<List<IdleDrainEntity>> = flowOf(emptyList())
        override suspend fun getTodayDrainKwh(dayStart: Long, dayEnd: Long): Double = 0.0
        override suspend fun getCount(): Int = 0
        override suspend fun getAll(): List<IdleDrainEntity> = emptyList()
        override suspend fun getTotalKwh(): Double = 0.0
        override suspend fun deleteAll() {}
        override suspend fun getTodayDrainHours(dayStart: Long, dayEnd: Long): Double = 0.0
        override suspend fun getKwhSince(since: Long): Double = 0.0
        override suspend fun getHoursSince(since: Long): Double = 0.0
        override suspend fun getKwhBetween(from: Long, to: Long): Double = 0.0
        override suspend fun getSince(since: Long): List<IdleDrainEntity> = emptyList()
    }

    private class FakeAdbClient : AdbOnDeviceClient {
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun isConnected(): Boolean = false
        override suspend fun exec(cmd: String): String? = null
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = false
        override suspend fun spawnHelper(): Boolean = false
        override suspend fun killHelper(): Boolean = false
        override suspend fun readHelperLog(): String? = null
        override suspend fun helperHeartbeat(): Boolean = false
        override suspend fun shutdown() {}
    }

    // --- Factory ---

    private lateinit var settingsDao: FakeSettingsDao

    private fun buildViewModel(): SettingsViewModel {
        val ctx: Context = ApplicationProvider.getApplicationContext()

        settingsDao = FakeSettingsDao()
        val settingsRepo = SettingsRepository(settingsDao, mockk<LocalePreferences>(relaxed = true))

        val tripDao = StubTripDao()
        val tripPointDao = StubTripPointDao()
        val tripRepo = TripRepository(tripDao, tripPointDao)

        val chargeDao = StubChargeDao()
        val chargeRepo = ChargeRepository(chargeDao, StubChargePointDao())
        val idleDrainDao = StubIdleDrainDao()

        val httpClient = OkHttpClient()
        val energyReader = EnergyDataReader(ctx)
        val historyImporter = HistoryImporter(
            ctx, energyReader, tripRepo, tripDao, tripPointDao, idleDrainDao,
            settingsRepo, com.bydmate.app.data.repository.LastSessionRepository(ctx)
        )

        val insightsClient = OpenRouterClient(httpClient)
        val insightsManager = InsightsManager(ctx, insightsClient, tripDao, idleDrainDao, chargeDao, settingsRepo)

        val backupManager = mockk<BackupManager>(relaxed = true)
        val voiceModelManager = mockk<com.bydmate.app.voice.VoiceModelManager>(relaxed = true)
        val ttsModelManager = mockk<com.bydmate.app.voice.TtsModelManager>(relaxed = true)
        val ttsEngine = mockk<com.bydmate.app.voice.TtsEngine>(relaxed = true)
        val gigaAmModelManager = mockk<com.bydmate.app.voice.GigaAmModelManager>(relaxed = true)
        val continuousAsr = mockk<com.bydmate.app.voice.ContinuousAsr>(relaxed = true)
        val voiceController = mockk<com.bydmate.app.voice.VoiceController>(relaxed = true)

        return SettingsViewModel(
            appContext = ctx,
            settingsRepository = settingsRepo,
            tripRepository = tripRepo,
            chargeRepository = chargeRepo,
            updateChecker = UpdateChecker(httpClient),
            historyImporter = historyImporter,
            energyDataReader = energyReader,
            idleDrainDao = idleDrainDao,
            tripPointDao = tripPointDao,
            insightsManager = insightsManager,
            adbOnDeviceClient = FakeAdbClient(),
            localePreferences = LocalePreferences(ctx),
            backupManager = backupManager,
            chargingStateStore = com.bydmate.app.data.charging.ChargingStateStore(settingsRepo),
            catchUpJournal = com.bydmate.app.data.charging.CatchUpJournal(settingsRepo),
            voiceModelManager = voiceModelManager,
            ttsModelManager = ttsModelManager,
            ruStressMarker = com.bydmate.app.voice.RuStressMarker { null },
            gigaAmModelManager = gigaAmModelManager,
            continuousAsr = continuousAsr,
            ttsEngine = ttsEngine,
            voiceController = voiceController,
            seatChannelStore = seatChannelStore,
            helperClient = helperClient,
            helperBootstrap = helperBootstrap,
            agentOrchestrator = agentOrchestrator,
            llmConnectionResolver = llmConnectionResolver,
            openRouterClient = openRouterClient,
            placeRepository = mockk(relaxed = true),
        )
    }

    // --- Tests ---

    @Test fun `saveZaiApiKey persists and updates state`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveZaiApiKey("zk")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("zk", vm.uiState.value.zaiApiKey)
        assertEquals("zk", settingsDao.map[SettingsRepository.KEY_ZAI_API_KEY])
    }

    @Test fun `applyCustomPreset fills name url and model but not the key`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveCustomApiKey("existing-key")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.applyCustomPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("DeepSeek", state.customName)
        assertEquals("https://api.deepseek.com/v1", state.customBaseUrl)
        assertEquals("deepseek-chat", state.customModel)
        assertEquals("existing-key", state.customApiKey)
    }

    @Test fun `selectPrimaryConn persists agent_primary_conn`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectPrimaryConn("zai")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("zai", vm.uiState.value.primaryConn)
        assertEquals("zai", settingsDao.map[SettingsRepository.KEY_AGENT_PRIMARY_CONN])
    }

    @Test fun `selectFallbackConn empty clears fallback`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectFallbackConn("zai")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectFallbackConn("")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("", vm.uiState.value.fallbackConn)
        assertEquals("", settingsDao.map[SettingsRepository.KEY_AGENT_FALLBACK_CONN])
    }

    @Test fun `agentConnConfigured follows primary connection`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectPrimaryConn("zai")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(!vm.uiState.value.agentConnConfigured)

        vm.saveZaiApiKey("zk")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.agentConnConfigured)
    }

    @Test fun `testConnection reports round trip on success`() = runTest {
        val conn = LlmConnection(id = "zai", label = "z.ai", baseUrl = "https://api.z.ai/api/paas/v4", apiKey = "zk", model = "glm-4.7-flash")
        coEvery { llmConnectionResolver.get("zai") } returns conn
        val message = JSONObject().put("role", "assistant").put("content", "готов")
        coEvery { openRouterClient.chatRaw(conn.baseUrl, conn.apiKey, conn.model, any<JSONArray>(), null) } returns
            Result.success(message)

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.testConnection("zai")
        testDispatcher.scheduler.advanceUntilIdle()

        val result = vm.uiState.value.connTestResults["zai"]
        assertTrue(result != null)
        // Locale-agnostic: assert the "check_ok" branch ran (elapsed-seconds figure present),
        // not the "not configured" branch (Robolectric's default locale is en_US, not ru).
        assertTrue(result!!.contains(Regex("""\d+\.\d""")))
        assertEquals(null, vm.uiState.value.connTestRunning)
        coVerify { openRouterClient.chatRaw(conn.baseUrl, conn.apiKey, conn.model, any<JSONArray>(), null) }
    }

    @Test fun `testConnection on unconfigured connection reports not configured`() = runTest {
        coEvery { llmConnectionResolver.get("custom") } returns null

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.testConnection("custom")
        testDispatcher.scheduler.advanceUntilIdle()

        val ctx: Context = ApplicationProvider.getApplicationContext()
        val expected = ctx.getString(com.bydmate.app.R.string.settings_conn_not_configured)
        assertEquals(expected, vm.uiState.value.connTestResults["custom"])
        assertEquals(null, vm.uiState.value.connTestRunning)
    }

    // --- Wave O T10 fix: default-fill race + model name ---

    @Test fun `saveOpenRouterApiKey default-fill does not clobber concurrent selectModel`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.openRouterModel)

        // Queue the save coroutine without running it yet
        vm.saveOpenRouterApiKey("sk-key")
        // Before the coroutine runs, user selects a model (sync state update + queued persist)
        vm.selectModel(OpenRouterModel(id = "meta/llama-4", name = "Llama 4", pricingPrompt = 0.0))
        // Now run both coroutines to completion
        testDispatcher.scheduler.advanceUntilIdle()

        // Default-fill must NOT have fired: user's explicit choice wins
        assertEquals("meta/llama-4", vm.uiState.value.openRouterModel)
        assertEquals("meta/llama-4", settingsDao.map[SettingsRepository.KEY_OPENROUTER_MODEL])
    }

    @Test fun `saveOpenRouterApiKey default-fill sets openRouterModelName`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.openRouterModel)

        vm.saveOpenRouterApiKey("sk-key")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SettingsViewModel.DEFAULT_OPENROUTER_MODEL, vm.uiState.value.openRouterModel)
        assertEquals("gemini-3.1-flash-lite", vm.uiState.value.openRouterModelName)
    }

    @Test fun `testConnection second tap while running is ignored`() = runTest {
        val conn = LlmConnection(id = "zai", label = "z.ai", baseUrl = "https://api.z.ai/api/paas/v4", apiKey = "zk", model = "glm-4.7-flash")
        coEvery { llmConnectionResolver.get("zai") } returns conn
        val gate = CompletableDeferred<Unit>()
        coEvery { openRouterClient.chatRaw(any(), any(), any(), any<JSONArray>(), null) } coAnswers {
            gate.await()
            Result.success(JSONObject().put("role", "assistant").put("content", "готов"))
        }

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.testConnection("zai")
        testDispatcher.scheduler.runCurrent() // in flight, suspended on gate
        assertEquals("zai", vm.uiState.value.connTestRunning)

        vm.testConnection("zai") // second tap while running — must be ignored
        testDispatcher.scheduler.runCurrent()

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { openRouterClient.chatRaw(any(), any(), any(), any<JSONArray>(), null) }
    }

    // --- Wave O T10: default OpenRouter model ---

    @Test fun `saveOpenRouterApiKey with empty model auto-fills DEFAULT_OPENROUTER_MODEL`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.openRouterModel)

        vm.saveOpenRouterApiKey("sk-test-key")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SettingsViewModel.DEFAULT_OPENROUTER_MODEL, vm.uiState.value.openRouterModel)
        assertEquals(SettingsViewModel.DEFAULT_OPENROUTER_MODEL, settingsDao.map[SettingsRepository.KEY_OPENROUTER_MODEL])
    }

    @Test fun `saveOpenRouterApiKey with existing model leaves model unchanged`() = runTest {
        val vm = buildViewModel()
        // Pre-populate model before loadSettings coroutine runs
        settingsDao.map[SettingsRepository.KEY_OPENROUTER_MODEL] = "my-chosen-model"
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("my-chosen-model", vm.uiState.value.openRouterModel)

        vm.saveOpenRouterApiKey("sk-test-key")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("my-chosen-model", vm.uiState.value.openRouterModel)
        assertEquals("my-chosen-model", settingsDao.map[SettingsRepository.KEY_OPENROUTER_MODEL])
    }

    // --- Wave O T11: custom connection model list + Russian network errors ---

    @Test fun `loadCustomModels fetches and stores model list`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveCustomBaseUrl("https://ai-gateway.vercel.sh/v1")
        vm.saveCustomApiKey("vercel-test-key")
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery {
            openRouterClient.fetchModelsFromUrl("https://ai-gateway.vercel.sh/v1", "vercel-test-key")
        } returns Result.success(listOf("openai/gpt-4o-mini", "openai/gpt-4o"))

        vm.loadCustomModels()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("openai/gpt-4o-mini", "openai/gpt-4o"), vm.uiState.value.customModelList)
        assertEquals(null, vm.uiState.value.customModelsError)
    }

    @Test fun `loadCustomModels with UnknownHostException shows Russian DNS error`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveCustomBaseUrl("https://bad-host.invalid/v1")
        vm.saveCustomApiKey("test-key")
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery {
            openRouterClient.fetchModelsFromUrl("https://bad-host.invalid/v1", "test-key")
        } returns Result.failure(java.net.UnknownHostException("bad-host.invalid"))

        vm.loadCustomModels()
        testDispatcher.scheduler.advanceUntilIdle()

        val ctx: Context = ApplicationProvider.getApplicationContext()
        val expected = ctx.getString(com.bydmate.app.R.string.settings_error_dns)
        assertEquals(expected, vm.uiState.value.customModelsError)
        assertTrue(vm.uiState.value.customModelList.isEmpty())
    }

    // --- Wave O T11 fix round 1: unwrap causes + stale list ---

    @Test fun `loadCustomModels with wrapped UnknownHostException shows Russian DNS error`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveCustomBaseUrl("https://bad-host.invalid/v1")
        vm.saveCustomApiKey("test-key")
        testDispatcher.scheduler.advanceUntilIdle()

        // Wrapped exception: IOException("wrap", UnknownHostException(...))
        val wrapped = java.io.IOException("wrap", java.net.UnknownHostException("bad-host.invalid"))
        coEvery {
            openRouterClient.fetchModelsFromUrl("https://bad-host.invalid/v1", "test-key")
        } returns Result.failure(wrapped)

        vm.loadCustomModels()
        testDispatcher.scheduler.advanceUntilIdle()

        val ctx: Context = ApplicationProvider.getApplicationContext()
        val expected = ctx.getString(com.bydmate.app.R.string.settings_error_dns)
        assertEquals(expected, vm.uiState.value.customModelsError)
    }

    @Test fun `loadCustomModels with LlmHttpException 403 shows HTTP 403 not LLM HTTP`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveCustomBaseUrl("https://api.example.com/v1")
        vm.saveCustomApiKey("bad-key")
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery {
            openRouterClient.fetchModelsFromUrl("https://api.example.com/v1", "bad-key")
        } returns Result.failure(com.bydmate.app.data.remote.LlmHttpException(403))

        vm.loadCustomModels()
        testDispatcher.scheduler.advanceUntilIdle()

        val error = vm.uiState.value.customModelsError ?: ""
        assertTrue("error should contain 'HTTP 403': $error", error.contains("HTTP 403"))
        assertTrue("error must not contain 'LLM HTTP': $error", !error.contains("LLM HTTP"))
    }

    @Test fun `loadCustomModels on error clears stale model list`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveCustomBaseUrl("https://api.example.com/v1")
        vm.saveCustomApiKey("test-key")
        testDispatcher.scheduler.advanceUntilIdle()

        // First load succeeds — seeds the list
        coEvery {
            openRouterClient.fetchModelsFromUrl("https://api.example.com/v1", "test-key")
        } returns Result.success(listOf("model-a", "model-b"))
        vm.loadCustomModels()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("model-a", "model-b"), vm.uiState.value.customModelList)

        // Second load fails — stale list must be cleared immediately (while loading)
        coEvery {
            openRouterClient.fetchModelsFromUrl("https://api.example.com/v1", "test-key")
        } returns Result.failure(java.io.IOException("timeout"))
        vm.loadCustomModels()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("stale list must be empty on error", vm.uiState.value.customModelList.isEmpty())
        assertTrue("error must be set", vm.uiState.value.customModelsError != null)
    }

    @Test fun `saveCustomBaseUrl clears stale customModelList`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveCustomBaseUrl("https://api.example.com/v1")
        vm.saveCustomApiKey("test-key")
        testDispatcher.scheduler.advanceUntilIdle()

        // Seed a model list
        coEvery {
            openRouterClient.fetchModelsFromUrl("https://api.example.com/v1", "test-key")
        } returns Result.success(listOf("model-x"))
        vm.loadCustomModels()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("model-x"), vm.uiState.value.customModelList)

        // Change the URL — list must be cleared immediately
        vm.saveCustomBaseUrl("https://other-provider.com/v1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("model list must be cleared on URL change", vm.uiState.value.customModelList.isEmpty())
        assertEquals(null, vm.uiState.value.customModelsError)
    }
}
