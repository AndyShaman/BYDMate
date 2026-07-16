package com.bydmate.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.backup.BackupManager
import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripSummary
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.service.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.bydmate.app.data.vehicle.SeatChannel
import com.bydmate.app.data.vehicle.SeatChannelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.voice.online.TtsRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val seatChannelStore: SeatChannelStore = mockk(relaxed = true)
    private val helperClient: HelperClient = mockk(relaxed = true)
    // relaxed → ensureRunning() defaults to false; tests that need the daemon "up" stub it true.
    private val helperBootstrap: HelperBootstrap = mockk(relaxed = true)
    private val agentOrchestrator: AgentOrchestrator = mockk(relaxed = true)
    private val energyDataDeadDetector: EnergyDataDeadDetector = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Fake DAOs ---

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
        override suspend fun deleteByTripId(tripId: Long): Int = 0
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

    private class StubBatterySnapshotDao : BatterySnapshotDao {
        override fun getAll(): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override fun getRecent(limit: Int): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override suspend fun insert(snapshot: BatterySnapshotEntity): Long = 0L
        override suspend fun getLast(): BatterySnapshotEntity? = null
        override suspend fun getCount(): Int = 0
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

    private fun buildViewModel(
        updateChecker: UpdateChecker? = null,
        ttsModelManager: com.bydmate.app.voice.TtsModelManager? = null,
        ttsEngine: com.bydmate.app.voice.TtsEngine? = null,
        gigaAmModelManager: com.bydmate.app.voice.GigaAmModelManager? = null,
        llmConnectionResolver: LlmConnectionResolver? = null,
    ): SettingsViewModel {
        val ctx: Context = ApplicationProvider.getApplicationContext()

        settingsDao = FakeSettingsDao()
        val settingsRepo = SettingsRepository(settingsDao, mockk<LocalePreferences>(relaxed = true))

        val tripDao = StubTripDao()
        val tripPointDao = StubTripPointDao()
        val tripRepo = TripRepository(tripDao, tripPointDao, mockk<TripTombstoneDao>(relaxed = true), mockk<AppDatabase>(relaxed = true))

        val chargeDao = StubChargeDao()
        val chargeRepo = ChargeRepository(chargeDao, StubChargePointDao())
        val idleDrainDao = StubIdleDrainDao()

        val httpClient = OkHttpClient()
        val resolvedUpdateChecker = updateChecker ?: UpdateChecker(httpClient)

        val energyReader = EnergyDataReader(ctx)
        val historyImporter = HistoryImporter(
            ctx, energyReader, tripRepo, tripDao, tripPointDao, idleDrainDao,
            settingsRepo, com.bydmate.app.data.repository.LastSessionRepository(ctx),
            mockk<TripTombstoneDao>(relaxed = true)
        )

        val openRouterClient = OpenRouterClient(httpClient)
        val insightsManager = InsightsManager(ctx, openRouterClient, tripDao, idleDrainDao, chargeDao, settingsRepo)

        // Stub BackupManager: no AppDatabase available in unit tests, use mockk
        val backupManager = mockk<BackupManager>(relaxed = true)

        // Voice deps are not exercised by most settings tests; relaxed mocks keep them inert.
        val resolvedTtsModelManager = ttsModelManager ?: mockk(relaxed = true)
        val resolvedTtsEngine = ttsEngine ?: mockk<com.bydmate.app.voice.TtsEngine>(relaxed = true)
        val resolvedGigaAmModelManager = gigaAmModelManager ?: mockk(relaxed = true)
        val continuousAsr = mockk<com.bydmate.app.voice.ContinuousAsr>(relaxed = true)
        val voiceController = mockk<com.bydmate.app.voice.VoiceController>(relaxed = true)

        return SettingsViewModel(
            appContext = ctx,
            settingsRepository = settingsRepo,
            tripRepository = tripRepo,
            chargeRepository = chargeRepo,
            updateChecker = resolvedUpdateChecker,
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
            ttsModelManager = resolvedTtsModelManager,
            ruStressMarker = com.bydmate.app.voice.RuStressMarker { null },
            gigaAmModelManager = resolvedGigaAmModelManager,
            continuousAsr = continuousAsr,
            ttsEngine = resolvedTtsEngine,
            voiceController = voiceController,
            seatChannelStore = seatChannelStore,
            helperClient = helperClient,
            helperBootstrap = helperBootstrap,
            agentOrchestrator = agentOrchestrator,
            llmConnectionResolver = llmConnectionResolver ?: mockk(relaxed = true),
            openRouterClient = openRouterClient,
            placeRepository = mockk(relaxed = true),
            energyDataDeadDetector = energyDataDeadDetector,
        )
    }

    // --- Tests ---

    @Test
    fun `initial state has default battery capacity`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SettingsRepository.DEFAULT_BATTERY_CAPACITY, vm.uiState.value.batteryCapacity)
    }

    @Test
    fun `initial state has default home tariff`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SettingsRepository.DEFAULT_HOME_TARIFF, vm.uiState.value.homeTariff)
    }

    // Issue #23: pressing "Close" while an update is downloading must cancel the
    // download coroutine and clear the Downloading state. Without cancellation the
    // progress callback keeps re-emitting Downloading (re-opening the dialog) and
    // the download eventually fires the system install prompt unexpectedly.
    @Test
    fun `closing update dialog during download cancels job and clears downloading state`() = runTest {
        val uc = mockk<UpdateChecker>(relaxed = true)
        val info = UpdateChecker.UpdateInfo(
            version = "9.9.9",
            downloadUrl = "http://example.invalid/BYDMate.apk",
            releaseNotes = ""
        )
        coEvery { uc.checkForUpdate(any(), any()) } returns info
        val onProgressSlot = slot<(String) -> Unit>()
        coEvery { uc.downloadAndInstall(any(), any(), capture(onProgressSlot)) } coAnswers {
            onProgressSlot.captured.invoke("10%")
            delay(10_000)                       // still downloading...
            onProgressSlot.captured.invoke("100%") // must NOT run after the dialog is closed
        }

        val vm = buildViewModel(updateChecker = uc)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadUpdate()
        testDispatcher.scheduler.runCurrent() // start download, emit "10%", suspend on delay

        vm.hideUpdateDialog()
        testDispatcher.scheduler.advanceUntilIdle() // cancelled job must not resurrect state

        assertEquals(UpdateState.Idle, vm.uiState.value.updateDialogState)
    }

    @Test fun `resetSeatChannel sets winner to UNKNOWN`() = runTest {
        val vm = buildViewModel()
        vm.resetSeatChannel()
        verify { seatChannelStore.setWinner(SeatChannel.UNKNOWN) }
    }

    @Test fun `setDisableNativeAssistant updates state and applies via helper`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { helperClient.setAppHidden("com.byd.autovoice", true) } returns true

        vm.setDisableNativeAssistant(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.disableNativeAssistant)
        coVerify { helperClient.setAppHidden("com.byd.autovoice", true) }
    }

    @Test fun `setVoiceEnabled persists and mirrors into voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setVoiceEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.voiceEnabled)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertTrue(
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getBoolean(SettingsRepository.KEY_VOICE_ENABLED, false)
        )
    }

    /**
     * Fix (Finding 3b): enabling Voice must best-effort re-bind the a11y key service (the same
     * daemon call ClusterProjectionManager.enableStarControl uses) — otherwise the PTT key filter
     * (SteeringWheelKeyService) is never bound on a fresh install with no cluster-projection toggle.
     * The daemon must be bootstrapped FIRST: HelperClient only resolves an existing binder, so
     * without ensureRunning() the re-bind silently no-ops when the daemon is not up.
     */
    @Test fun `setVoiceEnabled true bootstraps daemon then triggers accessibility re-bind`() = runTest {
        coEvery { helperBootstrap.ensureRunning() } returns true
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setVoiceEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerifyOrder {
            helperBootstrap.ensureRunning()
            helperClient.enableAccessibilityService()
        }
    }

    @Test fun `setVoiceEnabled true skips a11y re-bind when daemon bootstrap fails`() = runTest {
        coEvery { helperBootstrap.ensureRunning() } returns false
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setVoiceEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { helperClient.enableAccessibilityService() }
    }

    @Test fun `setVoiceEnabled false does not touch daemon or accessibility re-bind`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setVoiceEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { helperBootstrap.ensureRunning() }
        coVerify(exactly = 0) { helperClient.enableAccessibilityService() }
    }

    @Test fun `setTtsEnabled persists and mirrors to voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setTtsEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.ttsEnabled)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertTrue(
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getBoolean(SettingsRepository.KEY_TTS_ENABLED, false)
        )
    }

    @Test fun `setTtsVoice persists, mirrors to voice prefs, and reloads the engine`() = runTest {
        val ttsModelManager = mockk<com.bydmate.app.voice.TtsModelManager>(relaxed = true)
        val ttsEngine = mockk<com.bydmate.app.voice.TtsEngine>(relaxed = true)
        coEvery { ttsModelManager.isReady(com.bydmate.app.voice.TtsVoiceCatalog.byId("irina")) } returns true

        val vm = buildViewModel(ttsModelManager = ttsModelManager, ttsEngine = ttsEngine)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setTtsVoice("irina")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("irina", vm.uiState.value.ttsVoice)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            "irina",
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("tts_voice", null)
        )
        verify(exactly = 1) { ttsEngine.reload() }
    }

    @Test fun `agent identity state flows initialize from voice prefs`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit()
            .putString("agent_name", "Лео")
            .putString("agent_persona", "snarky")
            .apply()

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Лео", vm.agentName.value)
        assertEquals("snarky", vm.agentPersona.value)
        assertEquals("Лео", vm.uiState.value.agentName)
        assertEquals("snarky", vm.uiState.value.agentPersona)
    }

    // Final review fix, finding 3: a legacy "denis"/"dmitri" id read verbatim into state left no
    // radio row selected in Settings, even though playback already resolves it via byId(). State
    // must resolve through the same catalog lookup so the migrated voice shows selected.
    @Test fun `state resolves a legacy persisted voice id to its migrated catalog id`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit()
            .putString("tts_voice", "denis")
            .apply()

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("dmitri", vm.uiState.value.ttsVoice)
    }

    @Test fun `setAgentName and setAgentPersona mirror to voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentName("Майк")
        vm.setAgentPersona("engineer")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Майк", vm.agentName.value)
        assertEquals("engineer", vm.agentPersona.value)
        assertEquals("Майк", vm.uiState.value.agentName)
        assertEquals("engineer", vm.uiState.value.agentPersona)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
        assertEquals("Майк", prefs.getString("agent_name", null))
        assertEquals("engineer", prefs.getString("agent_persona", null))
    }

    @Test fun `downloadTtsVoice success adds voice to ttsReadyVoices`() = runTest {
        val ttsModelManager = mockk<com.bydmate.app.voice.TtsModelManager>(relaxed = true)
        coEvery { ttsModelManager.download(any(), any()) } returns Result.success(Unit)
        coEvery { ttsModelManager.isReady(any()) } returns true

        val vm = buildViewModel(ttsModelManager = ttsModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadTtsVoice("alena")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(!vm.uiState.value.ttsDownloadProgress.containsKey("alena"))
        assertTrue("alena" in vm.uiState.value.ttsReadyVoices)
        assertTrue("alena" !in vm.uiState.value.ttsDownloadFailed)
    }

    @Test fun `downloadTtsVoice failure sets ttsDownloadFailed for that voice`() = runTest {
        val ttsModelManager = mockk<com.bydmate.app.voice.TtsModelManager>(relaxed = true)
        coEvery { ttsModelManager.download(any(), any()) } returns Result.failure(RuntimeException("boom"))
        coEvery { ttsModelManager.isReady(any()) } returns false

        val vm = buildViewModel(ttsModelManager = ttsModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadTtsVoice("artem")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("artem" in vm.uiState.value.ttsDownloadFailed)
        assertTrue("artem" !in vm.uiState.value.ttsReadyVoices)
    }

    /**
     * Fix: downloadTtsModel lacked the in-flight guard that downloadVoiceModel has
     * (`voiceDownloadProgress >= 0` early-return). A re-entrant call while a download is
     * already running must be a no-op — the manager's download() must be invoked only once.
     */
    @Test fun `downloadTtsVoice ignores re-entry for the same voice while in flight`() = runTest {
        val ttsModelManager = mockk<com.bydmate.app.voice.TtsModelManager>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { ttsModelManager.download(any(), any()) } coAnswers {
            gate.await()
            Result.success(Unit)
        }
        coEvery { ttsModelManager.isReady(any()) } returns true

        val vm = buildViewModel(ttsModelManager = ttsModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadTtsVoice("artem")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.downloadTtsVoice("artem") // re-entry while the first download is still in flight
        testDispatcher.scheduler.advanceUntilIdle()

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { ttsModelManager.download(any(), any()) }
    }

    /**
     * Per-voice download state must not clobber another voice's state: downloading "irina"
     * while "artem" is already ready (or mid-download) must leave artem's state untouched.
     */
    @Test fun `downloadTtsVoice for one voice does not affect another voice's state`() = runTest {
        val ttsModelManager = mockk<com.bydmate.app.voice.TtsModelManager>(relaxed = true)
        coEvery { ttsModelManager.isReady(com.bydmate.app.voice.TtsVoiceCatalog.byId("ruslan")) } returns true
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { ttsModelManager.download(com.bydmate.app.voice.TtsVoiceCatalog.byId("irina"), any()) } coAnswers {
            gate.await()
            Result.success(Unit)
        }
        coEvery { ttsModelManager.isReady(com.bydmate.app.voice.TtsVoiceCatalog.byId("irina")) } returns true

        val vm = buildViewModel(ttsModelManager = ttsModelManager)
        testDispatcher.scheduler.advanceUntilIdle()
        // ruslan is already ready from initial load (isReady stubbed true above)
        assertTrue("ruslan" in vm.uiState.value.ttsReadyVoices)

        vm.downloadTtsVoice("irina")
        testDispatcher.scheduler.advanceUntilIdle() // irina's download now in flight

        assertTrue("irina" in vm.uiState.value.ttsDownloadProgress)
        assertTrue("ruslan" !in vm.uiState.value.ttsDownloadProgress)
        assertTrue("ruslan" in vm.uiState.value.ttsReadyVoices) // untouched by irina's download

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("irina" in vm.uiState.value.ttsReadyVoices)
        assertTrue("ruslan" in vm.uiState.value.ttsReadyVoices)
    }

    /**
     * mark and sofia share the "supertonic-ru" model dir: downloading one must mark both
     * ready, since they share the same on-disk model.
     */
    @Test fun `downloadTtsVoice marks sibling voice ready when they share a modelDirId`() = runTest {
        val ttsModelManager = mockk<com.bydmate.app.voice.TtsModelManager>(relaxed = true)
        coEvery { ttsModelManager.download(any(), any()) } returns Result.success(Unit)
        coEvery { ttsModelManager.isReady(com.bydmate.app.voice.TtsVoiceCatalog.byId("mark")) } returns true

        val vm = buildViewModel(ttsModelManager = ttsModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadTtsVoice("mark")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("mark" in vm.uiState.value.ttsReadyVoices)
        assertTrue("sofia" in vm.uiState.value.ttsReadyVoices)
    }

    /**
     * Fix: deleteTtsModel did not cancel an active download job, so a stale download
     * completion could resurrect ready/progress state after delete. Must cancel the
     * voice's download job first, then delete, then reset state deterministically.
     */
    @Test fun `deleteTtsVoice cancels in-flight download`() = runTest {
        val ttsModelManager = mockk<com.bydmate.app.voice.TtsModelManager>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { ttsModelManager.download(any(), any()) } coAnswers {
            gate.await()
            Result.success(Unit)
        }
        coEvery { ttsModelManager.isReady(any()) } returns true

        val vm = buildViewModel(ttsModelManager = ttsModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadTtsVoice("artem")
        testDispatcher.scheduler.advanceUntilIdle() // download now in flight, awaiting gate

        vm.deleteTtsVoice("artem")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(!vm.uiState.value.ttsDownloadProgress.containsKey("artem"))
        assertTrue("artem" !in vm.uiState.value.ttsReadyVoices)
        assertTrue("artem" !in vm.uiState.value.ttsDownloadFailed)

        // Even if the (cancelled) download coroutine's suspended await were to resume, the
        // cancellation must have already torn it down — no stale write flips state back.
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(!vm.uiState.value.ttsDownloadProgress.containsKey("artem"))
        assertTrue("artem" !in vm.uiState.value.ttsReadyVoices)

        coVerify(exactly = 1) { ttsModelManager.delete(any()) }
    }

    /**
     * Fix: the onProgress lambda passed to ttsModelManager.download() wrote unconditionally
     * into _uiState. deleteTtsVoice() removes the voice's progress entry (idle), but a stale
     * progress delivery arriving afterward (from an already-cancelled download coroutine
     * still winding down) must not resurrect an in-progress state.
     */
    @Test fun `late progress callback after delete is ignored`() = runTest {
        val ttsModelManager = mockk<com.bydmate.app.voice.TtsModelManager>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val progressSlot = slot<(Int) -> Unit>()
        coEvery { ttsModelManager.download(any(), capture(progressSlot)) } coAnswers {
            gate.await()
            Result.success(Unit)
        }
        coEvery { ttsModelManager.isReady(any()) } returns true

        val vm = buildViewModel(ttsModelManager = ttsModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadTtsVoice("artem")
        testDispatcher.scheduler.advanceUntilIdle() // download now in flight, awaiting gate

        vm.deleteTtsVoice("artem")
        testDispatcher.scheduler.advanceUntilIdle()

        progressSlot.captured(55) // stale progress delivery after delete reset state to idle

        assertTrue(!vm.uiState.value.ttsDownloadProgress.containsKey("artem"))
    }

    @Test fun `setAgentGender switches selected local voice to its counterpart`() = runTest {
        val ttsEngine = mockk<com.bydmate.app.voice.TtsEngine>(relaxed = true)
        val vm = buildViewModel(ttsEngine = ttsEngine)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("dmitri", vm.uiState.value.ttsVoice) // default voice, male

        vm.setAgentGender("f")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("f", vm.uiState.value.agentGender)
        assertEquals("irina", vm.uiState.value.ttsVoice)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals("f", ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("agent_gender", null))
    }

    @Test fun `setAgentGender is a no-op on the voice when it already matches`() = runTest {
        val ttsEngine = mockk<com.bydmate.app.voice.TtsEngine>(relaxed = true)
        val vm = buildViewModel(ttsEngine = ttsEngine)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentGender("m") // already male (default voice is dmitri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("dmitri", vm.uiState.value.ttsVoice)
        verify(exactly = 0) { ttsEngine.reload() }
    }

    @Test fun `setTtsRate persists, mirrors to voice prefs, and reloads the engine`() = runTest {
        val ttsEngine = mockk<com.bydmate.app.voice.TtsEngine>(relaxed = true)
        val vm = buildViewModel(ttsEngine = ttsEngine)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setTtsRate(1.2f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1.2f, vm.uiState.value.ttsRate)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(1.2f, ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getFloat("tts_rate", -1f))
        verify(exactly = 1) { ttsEngine.reload() }
    }

    @Test fun `setTtsLiveliness persists, mirrors to voice prefs, and reloads the engine`() = runTest {
        val ttsEngine = mockk<com.bydmate.app.voice.TtsEngine>(relaxed = true)
        val vm = buildViewModel(ttsEngine = ttsEngine)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setTtsLiveliness(80)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(80, vm.uiState.value.ttsLiveliness)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(80, ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getInt("tts_liveliness", -1))
        verify(exactly = 1) { ttsEngine.reload() }
    }

    @Test fun `previewVoice reloads the engine and speaks the sample line via speakOffline`() = runTest {
        val ttsEngine = mockk<com.bydmate.app.voice.TtsEngine>(relaxed = true)
        val vm = buildViewModel(ttsEngine = ttsEngine)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.previewVoice()

        verify(exactly = 1) { ttsEngine.reload() }
        verify(exactly = 1) { ttsEngine.speakOffline(any()) }
    }

    // --- Wave N: online TTS source (Gemini/OpenAI via OpenRouter, MiniMax) ---

    @Test fun `initial ttsSource defaults to offline`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(TtsRouter.OFFLINE, vm.uiState.value.ttsSource)
    }

    @Test fun `setTtsSource persists an online id to voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setTtsSource("gemini")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("gemini", vm.uiState.value.ttsSource)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            "gemini",
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("tts_source", null)
        )
    }

    @Test fun `setTtsSource minimax is rejected while no key is saved`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setTtsSource("minimax")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(TtsRouter.OFFLINE, vm.uiState.value.ttsSource)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            null,
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("tts_source", null)
        )
    }

    @Test fun `setTtsSource minimax is accepted once a key is saved`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setMinimaxKey("mm-key")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setTtsSource("minimax")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("minimax", vm.uiState.value.ttsSource)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            "minimax",
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("tts_source", null)
        )
    }

    @Test fun `setMinimaxProvider persists via SettingsRepository`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setMinimaxProvider("fal")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("fal", vm.uiState.value.minimaxProvider)
        assertEquals("fal", settingsDao.map[SettingsRepository.KEY_MINIMAX_TTS_PROVIDER])
    }

    @Test fun `setMinimaxKey persists via SettingsRepository and flips minimaxKeySet`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(!vm.uiState.value.minimaxKeySet)

        vm.setMinimaxKey("mm-key")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.minimaxKeySet)
        assertEquals("mm-key", settingsDao.map[SettingsRepository.KEY_MINIMAX_TTS_KEY])
    }

    @Test fun `setMinimaxKey with a blank value clears minimaxKeySet`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setMinimaxKey("mm-key")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.minimaxKeySet)

        vm.setMinimaxKey("")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(!vm.uiState.value.minimaxKeySet)
    }

    @Test fun `setMinimaxKey with a blank value resets tts_source from minimax to offline`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setMinimaxKey("mm-key")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setTtsSource("minimax")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("minimax", vm.uiState.value.ttsSource)

        vm.setMinimaxKey("")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(TtsRouter.OFFLINE, vm.uiState.value.ttsSource)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            TtsRouter.OFFLINE,
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("tts_source", null)
        )
    }

    @Test fun `setMinimaxKey with a blank value leaves a non-minimax source untouched`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setMinimaxKey("mm-key")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setTtsSource("gemini")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setMinimaxKey("")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("gemini", vm.uiState.value.ttsSource)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            "gemini",
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("tts_source", null)
        )
    }

    // --- GigaAM v3 ASR (free-form Russian speech recognition, offline) ---

    @Test fun `initial state reflects gigaAmModelManager isReady`() = runTest {
        val gigaAmModelManager = mockk<com.bydmate.app.voice.GigaAmModelManager>(relaxed = true)
        coEvery { gigaAmModelManager.isReady() } returns true

        val vm = buildViewModel(gigaAmModelManager = gigaAmModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.gigaAmModelReady)
    }

    @Test fun `downloadGigaAmModel success flips gigaAmModelReady`() = runTest {
        val gigaAmModelManager = mockk<com.bydmate.app.voice.GigaAmModelManager>(relaxed = true)
        coEvery { gigaAmModelManager.download(any()) } returns Result.success(Unit)
        coEvery { gigaAmModelManager.isReady() } returns true

        val vm = buildViewModel(gigaAmModelManager = gigaAmModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadGigaAmModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(-1, vm.uiState.value.gigaAmDownloadProgress)
        assertTrue(vm.uiState.value.gigaAmModelReady)
        assertTrue(!vm.uiState.value.gigaAmDownloadFailed)
    }

    @Test fun `downloadGigaAmModel failure sets gigaAmDownloadFailed`() = runTest {
        val gigaAmModelManager = mockk<com.bydmate.app.voice.GigaAmModelManager>(relaxed = true)
        coEvery { gigaAmModelManager.download(any()) } returns Result.failure(RuntimeException("boom"))
        coEvery { gigaAmModelManager.isReady() } returns false

        val vm = buildViewModel(gigaAmModelManager = gigaAmModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadGigaAmModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.gigaAmDownloadFailed)
        assertTrue(!vm.uiState.value.gigaAmModelReady)
    }

    /** A re-entrant call while a download is already running must be a no-op. */
    @Test fun `downloadGigaAmModel ignores re-entry while in flight`() = runTest {
        val gigaAmModelManager = mockk<com.bydmate.app.voice.GigaAmModelManager>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { gigaAmModelManager.download(any()) } coAnswers {
            gate.await()
            Result.success(Unit)
        }
        coEvery { gigaAmModelManager.isReady() } returns true

        val vm = buildViewModel(gigaAmModelManager = gigaAmModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadGigaAmModel()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.downloadGigaAmModel() // re-entry while the first download is still in flight
        testDispatcher.scheduler.advanceUntilIdle()

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { gigaAmModelManager.download(any()) }
    }

    /** deleteGigaAmModel() must cancel an in-flight download so a stale completion can't
     *  resurrect ready/progress state after delete (same stale-guard as deleteTtsVoice). */
    @Test fun `deleteGigaAmModel cancels in-flight download`() = runTest {
        val gigaAmModelManager = mockk<com.bydmate.app.voice.GigaAmModelManager>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { gigaAmModelManager.download(any()) } coAnswers {
            gate.await()
            Result.success(Unit)
        }
        coEvery { gigaAmModelManager.isReady() } returns true

        val vm = buildViewModel(gigaAmModelManager = gigaAmModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadGigaAmModel()
        testDispatcher.scheduler.advanceUntilIdle() // download now in flight, awaiting gate

        vm.deleteGigaAmModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(-1, vm.uiState.value.gigaAmDownloadProgress)
        assertTrue(!vm.uiState.value.gigaAmModelReady)
        assertTrue(!vm.uiState.value.gigaAmDownloadFailed)

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(-1, vm.uiState.value.gigaAmDownloadProgress)
        assertTrue(!vm.uiState.value.gigaAmModelReady)
        assertTrue(!vm.uiState.value.gigaAmDownloadFailed)

        coVerify(exactly = 1) { gigaAmModelManager.delete() }
    }

    /** A stale progress delivery arriving after deleteGigaAmModel() reset progress to idle
     *  must not resurrect an in-progress state. */
    @Test fun `late gigaAm progress callback after delete is ignored`() = runTest {
        val gigaAmModelManager = mockk<com.bydmate.app.voice.GigaAmModelManager>(relaxed = true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val progressSlot = slot<(Int) -> Unit>()
        coEvery { gigaAmModelManager.download(capture(progressSlot)) } coAnswers {
            gate.await()
            Result.success(Unit)
        }
        coEvery { gigaAmModelManager.isReady() } returns true

        val vm = buildViewModel(gigaAmModelManager = gigaAmModelManager)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadGigaAmModel()
        testDispatcher.scheduler.advanceUntilIdle() // download now in flight, awaiting gate

        vm.deleteGigaAmModel()
        testDispatcher.scheduler.advanceUntilIdle()

        progressSlot.captured(55) // stale progress delivery after delete reset state to idle

        assertEquals(-1, vm.uiState.value.gigaAmDownloadProgress)
    }

    @Test fun `setAgentEnabled persists and updates state`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.agentEnabled)
    }

    @Test fun `setAgentName persists and mirrors into voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentName("Лео")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Лео", vm.uiState.value.agentName)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            "Лео",
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("agent_name", "")
        )
    }

    @Test fun `setAgentPersona persists and mirrors into voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentPersona("engineer")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("engineer", vm.uiState.value.agentPersona)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            "engineer",
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("agent_persona", "")
        )
    }

    @Test fun `testAgentModel on Answer sets result with seconds and answer prefix`() = runTest {
        coEvery { agentOrchestrator.ask(any()) } returns AgentResult.Answer("Заряд батареи 62 процента.")
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.testAgentModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val result = vm.uiState.value.modelTestResult
        assertTrue(result != null)
        assertTrue(result!!.contains(Regex("""\d+\.\d""")))
        assertTrue(result.contains("Заряд батареи 62 процента."))
        assertTrue(!vm.uiState.value.modelTestRunning)
    }

    @Test fun `testAgentModel on Error sets result with error prefix`() = runTest {
        coEvery { agentOrchestrator.ask(any()) } returns AgentResult.Error("Не получилось связаться с сервером")
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.testAgentModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val ctx: Context = ApplicationProvider.getApplicationContext()
        val expected = ctx.getString(
            com.bydmate.app.R.string.settings_error_with_message, "Не получилось связаться с сервером"
        )
        assertEquals(expected, vm.uiState.value.modelTestResult)
        assertTrue(!vm.uiState.value.modelTestRunning)
    }

    @Test fun `testAgentModel second tap while running is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { agentOrchestrator.ask(any()) } coAnswers {
            gate.await()
            AgentResult.Answer("ok")
        }
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.testAgentModel()
        testDispatcher.scheduler.runCurrent() // in flight, suspended on gate
        assertTrue(vm.uiState.value.modelTestRunning)

        vm.testAgentModel() // second tap while running — must be ignored
        testDispatcher.scheduler.runCurrent()

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { agentOrchestrator.ask(any()) }
    }

    // Wave O T3: stored "openai" tts_source must be migrated to "offline" on first load,
    // because the OpenAI TTS backend was removed (model not available on OpenRouter).
    @Test fun `stored openai tts_source is migrated to offline on load and prefs are rewritten`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit()
            .putString("tts_source", "openai")
            .apply()

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("offline", vm.uiState.value.ttsSource)
        assertEquals(
            "offline",
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("tts_source", null)
        )
    }

    @Test
    fun `resetTripSourceDetection delegates to detector`() {
        val vm = buildViewModel()
        vm.resetTripSourceDetection()
        verify(exactly = 1) { energyDataDeadDetector.reset() }
    }
}
