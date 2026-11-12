package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.mockk

/**
 * Builds [AgentTools] for tests: every dependency defaults to a relaxed mock,
 * a test passes by name only the mocks it configures (or wants strict).
 * One parameter per constructor argument of [AgentTools], hence the long list.
 */
@Suppress("LongParameterList")
fun agentTools(
    gate: VoiceGate = mockk(relaxed = true),
    batteryStateRepository: BatteryStateRepository = mockk(relaxed = true),
    rangeCalculator: RangeCalculator = mockk(relaxed = true),
    tripDao: TripDao = mockk(relaxed = true),
    chargeDao: ChargeDao = mockk(relaxed = true),
    actionDispatcher: ActionDispatcher = mockk(relaxed = true),
    ruleDao: RuleDao = mockk(relaxed = true),
    automationEngine: AutomationEngine = mockk(relaxed = true),
    placeRepository: PlaceRepository = mockk(relaxed = true),
    weatherClient: WeatherClient = mockk(relaxed = true),
    exaSearchClient: ExaSearchClient = mockk(relaxed = true),
    openRouterClient: OpenRouterClient = mockk(relaxed = true),
    settingsRepository: SettingsRepository = mockk(relaxed = true),
    contactLookup: ContactLookup = mockk(relaxed = true),
    context: Context = mockk(relaxed = true),
    clusterVoiceControl: ClusterVoiceControl = mockk(relaxed = true),
    chargerSearchClient: ChargerSearchClient = mockk(relaxed = true),
    insightsManager: InsightsManager = mockk(relaxed = true),
    zaiSearchClient: ZaiSearchClient = mockk(relaxed = true),
    llmConnections: LlmConnectionResolver = mockk(relaxed = true),
): AgentTools = AgentTools(
    gate, batteryStateRepository, rangeCalculator, tripDao, chargeDao, actionDispatcher, ruleDao,
    automationEngine, placeRepository, weatherClient, exaSearchClient, openRouterClient,
    settingsRepository, contactLookup, context, clusterVoiceControl, chargerSearchClient,
    insightsManager, zaiSearchClient, llmConnections,
)
