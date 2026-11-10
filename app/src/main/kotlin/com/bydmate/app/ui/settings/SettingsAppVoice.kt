package com.bydmate.app.ui.settings

import android.content.Intent
import android.net.Uri
import com.bydmate.app.data.local.LocalePreferences
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.agent.SHOW_CONTACTS_PERMISSION_REQUEST
import com.bydmate.app.data.remote.OpenRouterModel
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.theme.*
import com.bydmate.app.util.APP_LANGUAGES
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.bydmate.app.cluster.DEFAULT_VOICE_KEYCODE
import com.bydmate.app.voice.AgentPersona
import com.bydmate.app.voice.TtsGender
import com.bydmate.app.voice.TtsVoiceCatalog
import com.bydmate.app.voice.online.TtsRouter
import java.util.Locale

@Composable
internal fun AppSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val lang by viewModel.appLanguage.collectAsState()
    LanguageBlock(currentLang = lang, onLanguageChange = viewModel::setAppLanguage)
    val fontScale by viewModel.fontScale.collectAsState()
    FontScaleBlock(current = fontScale, onChange = viewModel::setFontScale)

    var showDonate by remember { mutableStateOf(false) }
    if (showDonate) {
        DonateDialog(entry = DonateEntry.SETTINGS, onDismiss = { showDonate = false })
    }

    SectionHeader(text = stringResource(R.string.settings_app_units_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingChipRow(
                title = stringResource(R.string.settings_app_distance_label),
                options = listOf(stringResource(R.string.settings_unit_km), stringResource(R.string.settings_unit_miles)),
                selectedIndex = if (state.units == "km") 0 else 1,
                onSelect = { idx -> viewModel.saveUnits(if (idx == 0) "km" else "miles") },
            )
            SettingDivider()
            SettingChipRow(
                title = stringResource(R.string.settings_app_currency_label),
                options = SettingsRepository.CURRENCIES.map { it.code },
                selectedIndex = SettingsRepository.CURRENCIES.indexOfFirst { it.code == state.currency }.coerceAtLeast(0),
                onSelect = { idx -> viewModel.saveCurrency(SettingsRepository.CURRENCIES[idx].code) },
            )
            SettingDivider()
            SettingChipRow(
                title = stringResource(R.string.settings_map_tile_source_label),
                options = listOf("OpenStreetMap", "Amap"),
                selectedIndex = if (state.mapTileSource == "osm") 0 else 1,
                onSelect = { idx -> viewModel.saveMapTileSource(if (idx == 0) "osm" else "amap") },
            )
        }
    }

    SectionHeader(text = stringResource(R.string.settings_update_dialog_title))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = stringResource(R.string.settings_update_check_toggle_label),
                traceId = "update_check",
                description = stringResource(R.string.settings_update_check_toggle_description),
                checked = state.autoCheckUpdates,
                onCheckedChange = { viewModel.setAutoCheckUpdates(it) },
            )
            SettingActionRow(
                title = stringResource(R.string.settings_update_check_button),
                description = "BYDMate v${state.appVersion}",
                buttonLabel = stringResource(R.string.settings_update_check_button),
                onClick = { viewModel.showUpdateDialog() },
            )
        }
    }

    SectionHeader(text = stringResource(R.string.settings_donate_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.settings_donate_card_body), color = TextSecondary, fontSize = 14.sp)
            Button(
                onClick = { showDonate = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)
            ) {
                Text(stringResource(R.string.settings_donate_button), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    SectionHeader(text = stringResource(R.string.settings_about_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        val context = LocalContext.current
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.settings_copyright), color = TextSecondary, fontSize = 14.sp)
            Text(
                text = "github.com/AndyShaman/BYDMate",
                color = AccentBlue,
                fontSize = 14.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/AndyShaman/BYDMate")))
                }
            )
            Text(
                text = stringResource(R.string.settings_weather_attribution),
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun VoiceSettingsContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onNavigateToVoiceJournal: () -> Unit,
    onNavigateToAgentChat: () -> Unit,
    onNavigateToVoiceUserPhrases: () -> Unit,
) {
    val context = LocalContext.current

    // Tracks which action requested the RECORD_AUDIO permission so the onResult
    // callback dispatches the right operation (ENABLE toggle).
    var pendingVoiceAction by remember { mutableStateOf("") }  // "ENABLE" | ""

    // RECORD_AUDIO permission launcher — requested on enable
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingVoiceAction) {
                "ENABLE" -> viewModel.setVoiceEnabled(true)
            }
        }
        pendingVoiceAction = ""
    }

    fun hasAudioPerm() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun hasContactsPerm() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    // READ_CONTACTS permission for call_contact — standard runtime dialog only, no ADB.
    var contactsPermGranted by remember { mutableStateOf(hasContactsPerm()) }
    val contactsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> contactsPermGranted = granted }

    // --- Section 1: Агент (enable toggle, name, persona, gender, debug tools) ---
    SectionHeader(text = stringResource(R.string.settings_agent_section_header))

    // Agent enable toggle
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.agent_enable_title),
                traceId = "agent_enable",
                description = stringResource(R.string.agent_enable_desc),
                checked = state.agentEnabled,
                onCheckedChange = { viewModel.setAgentEnabled(it) },
            )
        }
    }

    // Agent identity: display/wake name + persona (spoken-reply style) + gender
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsTextField(
                label = stringResource(R.string.settings_agent_name_label),
                value = state.agentName,
                onValueChange = { viewModel.setAgentName(it) },
                keyboardType = KeyboardType.Text,
            )
            SettingHint(stringResource(R.string.settings_agent_name_hint))
            SettingDivider()
            val personaIds = listOf(AgentPersona.SNARKY.id, AgentPersona.NAVIGATOR.id, AgentPersona.ENGINEER.id)
            SettingChipRow(
                title = stringResource(R.string.settings_agent_persona_label),
                options = listOf(
                    stringResource(R.string.settings_persona_snarky),
                    stringResource(R.string.settings_persona_navigator),
                    stringResource(R.string.settings_persona_engineer),
                ),
                selectedIndex = personaIds.indexOf(state.agentPersona).coerceAtLeast(0),
                onSelect = { viewModel.setAgentPersona(personaIds[it]) },
            )
            SettingDivider()
            val genderIds = listOf("m", "f")
            SettingChipRow(
                title = stringResource(R.string.settings_agent_gender_label),
                description = stringResource(R.string.settings_agent_gender_hint),
                options = listOf(
                    stringResource(R.string.settings_agent_gender_male),
                    stringResource(R.string.settings_agent_gender_female),
                ),
                selectedIndex = genderIds.indexOf(state.agentGender).coerceAtLeast(0),
                onSelect = { viewModel.setAgentGender(genderIds[it]) },
            )
            SettingDivider()
            // #190/#200: the map app every route/search command opens (voice agent and automation).
            val routeNavigatorIds = listOf(
                com.bydmate.app.data.automation.RouteNavigatorUris.YANDEX,
                com.bydmate.app.data.automation.RouteNavigatorUris.DGIS,
                com.bydmate.app.data.automation.RouteNavigatorUris.MAPS,
                com.bydmate.app.data.automation.RouteNavigatorUris.WAZE,
            )
            SettingChipRow(
                title = stringResource(R.string.settings_route_navigator_label),
                description = stringResource(R.string.settings_route_navigator_hint),
                options = listOf(
                    stringResource(R.string.settings_route_navigator_yandex),
                    stringResource(R.string.settings_route_navigator_dgis),
                    stringResource(R.string.settings_route_navigator_maps),
                    stringResource(R.string.settings_route_navigator_waze),
                ),
                selectedIndex = routeNavigatorIds.indexOf(state.routeNavigator).coerceAtLeast(0),
                onSelect = { viewModel.setRouteNavigator(routeNavigatorIds[it]) },
            )
        }
    }

    // Driver memory: what the agent remembered about the driver, plus a way to wipe it
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Facts can appear or vanish while Settings is closed -- reload them on entry.
            LaunchedEffect(Unit) { viewModel.refreshAgentMemory() }
            SettingActionRow(
                title = stringResource(R.string.settings_agent_memory_title),
                description = stringResource(R.string.settings_agent_memory_hint),
                buttonLabel = stringResource(R.string.settings_agent_memory_forget_all),
                onClick = { viewModel.forgetAgentMemory() },
                enabled = state.agentMemoryFacts.isNotEmpty(),
            )
            if (state.agentMemoryFacts.isEmpty()) {
                Text(
                    stringResource(R.string.settings_agent_memory_empty),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            } else {
                state.agentMemoryFacts.forEach { fact ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "\u2022 $fact",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.forgetAgentFact(fact) }) {
                            Text(stringResource(R.string.settings_agent_memory_forget_one), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Agent debug tools
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            val agentConfigured = state.agentConnConfigured
            SettingActionRow(
                title = stringResource(R.string.settings_agent_debug_row_title),
                description = stringResource(R.string.settings_agent_debug_row_desc),
                buttonLabel = if (state.modelTestRunning)
                    stringResource(R.string.agent_test_model_running)
                else
                    stringResource(R.string.agent_test_model_button),
                onClick = { viewModel.testAgentModel() },
                enabled = agentConfigured && !state.modelTestRunning,
                secondButtonLabel = stringResource(R.string.agent_chat_open),
                onSecondClick = onNavigateToAgentChat,
                secondButtonEnabled = state.agentEnabled,
            )
            state.modelTestResult?.let {
                Text(it, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            SettingDivider()
            SettingHint(stringResource(R.string.agent_model_shared_caption))
        }
    }

    // --- Section 2: Ответы агента (TTS toggle + per-voice list, filtered by agent gender) ---
    SectionHeader(text = stringResource(R.string.settings_tts_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_tts_toggle),
                traceId = "tts_toggle",
                description = stringResource(R.string.settings_tts_subtitle),
                checked = state.ttsEnabled,
                onCheckedChange = { viewModel.setTtsEnabled(it) },
            )
        }
    }

    // Voice list: local voices matching the current agent gender, then the online voices
    // (Gemini via OpenRouter, MiniMax). Selecting any row sets tts_source.
    val currentGender = if (state.agentGender == "f") TtsGender.FEMALE else TtsGender.MALE
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TtsVoiceCatalog.byGender(currentGender).forEach { voice ->
                TtsVoiceRow(voice = voice, state = state, viewModel = viewModel)
            }
            OnlineTtsVoiceRow(
                id = "gemini",
                label = "Gemini",
                description = stringResource(R.string.settings_tts_voice_online_gemini_desc),
                badge = stringResource(R.string.settings_tts_voice_online_gemini_badge),
                enabled = true,
                state = state, viewModel = viewModel,
            )
            OnlineTtsVoiceRow(
                id = "minimax",
                label = "MiniMax",
                description = stringResource(R.string.settings_tts_voice_online_minimax_desc),
                badge = stringResource(R.string.settings_tts_voice_online_minimax_badge),
                enabled = state.minimaxKeySet,
                state = state, viewModel = viewModel,
            )
        }
    }
    SettingHint(stringResource(R.string.settings_tts_online_fallback_hint))

    // --- Ключи для онлайн-голосов: OpenRouter hint (key lives in AI connections) + MiniMax
    // provider/price/key ---
    SectionHeader(text = stringResource(R.string.settings_tts_keys_header))
    SettingHint(stringResource(R.string.settings_tts_keys_openrouter_hint))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MiniMax", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            val providerIds = listOf("official", "fal", "replicate")
            SettingChipRow(
                title = stringResource(R.string.settings_minimax_provider_label),
                options = listOf(
                    stringResource(R.string.settings_minimax_provider_official),
                    stringResource(R.string.settings_minimax_provider_fal),
                    stringResource(R.string.settings_minimax_provider_replicate),
                ),
                selectedIndex = providerIds.indexOf(state.minimaxProvider).coerceAtLeast(0),
                onSelect = { viewModel.setMinimaxProvider(providerIds[it]) },
            )
            SettingHint(stringResource(R.string.settings_minimax_price_hint))
            MiniMaxKeyField(keySet = state.minimaxKeySet, onSave = { viewModel.setMinimaxKey(it) })
        }
    }

    // --- Section 3: Настройки локального голоса (speed/liveliness sliders + preview) ---
    SectionHeader(text = stringResource(R.string.settings_tts_tuning_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            var ratePct by remember(state.ttsRate) { mutableStateOf((state.ttsRate * 100).roundToInt()) }
            SettingSliderRow(
                title = stringResource(R.string.settings_tts_rate_label),
                description = stringResource(R.string.settings_tts_rate_hint),
                value = ratePct.toFloat(),
                onValueChange = { ratePct = it.roundToInt() },
                valueRange = 70f..140f,
                valueLabel = String.format(Locale.US, "%.2f", ratePct / 100f),
                steps = 13,
                onValueChangeFinished = { viewModel.setTtsRate(ratePct / 100f) },
            )
            SettingDivider()
            var liveliness by remember(state.ttsLiveliness) { mutableStateOf(state.ttsLiveliness) }
            SettingSliderRow(
                title = stringResource(R.string.settings_tts_liveliness_label),
                description = stringResource(R.string.settings_tts_liveliness_hint),
                value = liveliness.toFloat(),
                onValueChange = { liveliness = it.roundToInt() },
                valueRange = 0f..100f,
                valueLabel = "$liveliness%",
                steps = 49,
                onValueChangeFinished = { viewModel.setTtsLiveliness(liveliness) },
            )
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_tts_preview_button),
                buttonLabel = stringResource(R.string.settings_tts_preview_button),
                onClick = { viewModel.previewVoice() },
                style = SettingButtonStyle.Secondary,
            )
        }
    }
    SettingHint(stringResource(R.string.settings_voice_volume_hint))

    // --- Section 4: Распознавание речи (GigaAM) ---
    SectionHeader(text = stringResource(R.string.settings_asr_gigaam_title))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            val gigaAmDownloading = state.gigaAmDownloadProgress >= 0
            if (gigaAmDownloading) {
                // The unpack pulls nothing over the network and runs for minutes on a
                // DiLink 3; labelling it "Downloading" is what made it read as a hang.
                val phaseLabel = when (state.gigaAmDownloadPhase) {
                    com.bydmate.app.voice.GigaAmModelManager.Phase.UNPACK ->
                        R.string.settings_voice_model_unpacking
                    else -> R.string.settings_voice_model_downloading
                }
                Text(
                    stringResource(phaseLabel, state.gigaAmDownloadProgress),
                    color = TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AccentGreen,
                )
            } else {
                SettingStatusRow(
                    title = "GigaAM",
                    ok = state.gigaAmModelReady,
                )
                SettingDivider()
                if (state.gigaAmModelReady) {
                    SettingActionRow(
                        title = stringResource(R.string.settings_asr_gigaam_ready),
                        buttonLabel = stringResource(R.string.settings_asr_gigaam_delete),
                        onClick = { viewModel.deleteGigaAmModel() },
                        style = SettingButtonStyle.Warning,
                    )
                } else {
                    if (state.gigaAmDownloadFailed) {
                        val shortfall = state.gigaAmSpaceShortfall
                        Text(
                            if (shortfall != null) {
                                stringResource(
                                    R.string.settings_asr_gigaam_no_space,
                                    shortfall.requiredMb,
                                    shortfall.availableMb,
                                )
                            } else {
                                stringResource(R.string.settings_asr_gigaam_download_failed)
                            },
                            color = SocRed, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    SettingActionRow(
                        title = "GigaAM",
                        buttonLabel = stringResource(
                            R.string.settings_asr_gigaam_download,
                            com.bydmate.app.voice.GigaAmModelManager.MODEL_SIZE_LABEL,
                        ),
                        onClick = { viewModel.downloadGigaAmModel() },
                        style = SettingButtonStyle.Primary,
                    )
                }
            }
        }
    }

    // --- Section 5: Кнопка и микрофон ---
    SectionHeader(text = stringResource(R.string.settings_voice_button_mic_header))

    // Voice commands toggle
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_voice_enable_label),
                traceId = "voice_enable",
                description = stringResource(R.string.settings_voice_enable_description),
                checked = state.voiceEnabled,
                onCheckedChange = { on ->
                    if (on && !hasAudioPerm()) {
                        pendingVoiceAction = "ENABLE"
                        audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.setVoiceEnabled(on)
                    }
                },
            )
            SettingToggleRow(
                title = stringResource(R.string.settings_voice_close_after_command_label),
                traceId = "close_after_command",
                description = stringResource(R.string.settings_voice_close_after_command_description),
                checked = state.closeAfterCommand,
                onCheckedChange = { viewModel.setCloseAfterCommand(it) },
            )
        }
    }

    // Steering-button assignment (reuses LearnButtonDialog from DisplaySection)
    var learningVoiceKey by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            val keyLabel = steeringButtonLabel(
                if (state.voiceKeycode == 0) DEFAULT_VOICE_KEYCODE else state.voiceKeycode
            )
            SettingValueRow(
                title = stringResource(R.string.settings_voice_button_label),
                value = stringResource(R.string.settings_voice_button_current, keyLabel),
                onClick = { learningVoiceKey = true },
            )
        }
    }

    if (learningVoiceKey) {
        LearnButtonDialog(
            onSaveWithCompanions = { code, companions ->
                viewModel.saveVoiceKeycode(code, companions)
                learningVoiceKey = false
            },
            onDismiss = { learningVoiceKey = false },
            occupiedReason = splitKeyOccupiedReason(context),
        )
    }

    // Contacts permission (call_contact tool)
    if (SHOW_CONTACTS_PERMISSION_REQUEST) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                SettingStatusRow(
                    title = stringResource(R.string.settings_voice_contacts_label),
                    description = stringResource(R.string.settings_voice_contacts_description),
                    ok = contactsPermGranted,
                    buttonLabel = if (!contactsPermGranted) stringResource(R.string.settings_voice_contacts_grant) else null,
                    onClick = if (!contactsPermGranted) {
                        { contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS) }
                    } else null,
                )
            }
        }
    }

    SettingHint(stringResource(R.string.settings_voice_a11y_hint))

    // Voice journal (last MAX voice sessions: what was heard and how it went)
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingActionRow(
                title = stringResource(R.string.settings_voice_journal_entry_title),
                description = stringResource(R.string.settings_voice_journal_entry_description),
                buttonLabel = stringResource(R.string.settings_voice_journal_entry_title),
                onClick = onNavigateToVoiceJournal,
                style = SettingButtonStyle.Secondary,
            )
            if (SHOW_VOICE_USER_PHRASES) {
                SettingActionRow(
                    title = stringResource(R.string.settings_voice_user_phrases_title),
                    description = stringResource(R.string.settings_voice_user_phrases_description),
                    buttonLabel = stringResource(R.string.settings_voice_user_phrases_button),
                    onClick = onNavigateToVoiceUserPhrases,
                    style = SettingButtonStyle.Secondary,
                )
            }
        }
    }

    // --- Section 6: Штатный помощник BYD (moved verbatim from AppSection) ---
    SectionHeader(text = stringResource(R.string.settings_voice_native_assistant_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_voice_disable_native_label),
                traceId = "voice_disable_native",
                description = stringResource(R.string.settings_voice_disable_native_description),
                checked = state.disableNativeAssistant,
                onCheckedChange = { viewModel.toggleDisableNativeAssistant(it) },
            )
            SettingHint(stringResource(R.string.settings_voice_native_assistant_reboot_note))
        }
    }
    if (state.showDisableNativeAssistantDialog) {
        AppAlertDialog(
            onDismissRequest = { viewModel.dismissDisableNativeAssistantDialog() },
            containerColor = CardSurface,
            title = {
                Text(stringResource(R.string.settings_voice_disable_native_confirm_title), color = TextPrimary)
            },
            text = {
                Text(
                    stringResource(R.string.settings_voice_disable_native_confirm_body),
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDisableNativeAssistant() }) {
                    Text(stringResource(R.string.settings_voice_disable_native_confirm_button), color = AccentGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDisableNativeAssistantDialog() }) {
                    Text(stringResource(R.string.settings_cancel_button), color = TextSecondary)
                }
            },
        )
    }
}

/** One row in the "Ответы агента" voice list: radio button, name + description + "Локально"
 *  badge, and per-voice download/ready/delete controls. A voice with no model on disk cannot
 *  be selected. */
@Composable
private fun TtsVoiceRow(
    voice: com.bydmate.app.voice.TtsVoice,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val ready = voice.id in state.ttsReadyVoices
    val downloadProgress = state.ttsDownloadProgress[voice.id]
    val failed = voice.id in state.ttsDownloadFailed
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = state.ttsSource == TtsRouter.OFFLINE && state.ttsVoice == voice.id,
            enabled = ready,
            onClick = {
                viewModel.setTtsVoice(voice.id)
                viewModel.setTtsSource(TtsRouter.OFFLINE)
            },
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentGreen,
                unselectedColor = TextMuted,
            ),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(voice.labelRes),
                    color = if (ready) TextPrimary else TextMuted,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.settings_tts_voice_local_badge),
                    color = TextSecondary, fontSize = 10.sp,
                    modifier = Modifier
                        .background(CardSurface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                stringResource(
                    when (voice.engine) {
                        com.bydmate.app.voice.TtsVoiceEngine.PIPER -> R.string.settings_tts_voice_desc_piper
                        com.bydmate.app.voice.TtsVoiceEngine.VITS_MULTI -> R.string.settings_tts_voice_desc_vits
                        com.bydmate.app.voice.TtsVoiceEngine.SUPERTONIC -> R.string.settings_tts_voice_desc_supertonic
                    }
                ),
                color = TextSecondary, fontSize = 12.sp,
            )
            if (!ready) {
                Text(
                    stringResource(R.string.settings_tts_voice_locked_hint),
                    color = TextMuted, fontSize = 11.sp,
                )
            }
        }
        when {
            downloadProgress != null -> Text(
                stringResource(R.string.settings_voice_model_downloading, downloadProgress),
                color = TextSecondary, fontSize = 12.sp,
            )
            ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_tts_voice_ready), color = AccentGreen, fontSize = 12.sp)
                Text(" · ", color = TextMuted, fontSize = 12.sp)
                Text(
                    stringResource(R.string.settings_tts_model_delete),
                    color = SocRed, fontSize = 12.sp,
                    modifier = Modifier.clickable { viewModel.deleteTtsVoice(voice.id) },
                )
            }
            else -> Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(R.string.settings_tts_voice_download_label, voice.sizeMb),
                    color = AccentGreen, fontSize = 12.sp,
                    modifier = Modifier.clickable { viewModel.downloadTtsVoice(voice.id) },
                )
                if (failed) {
                    Text(
                        stringResource(R.string.settings_tts_download_failed),
                        color = SocRed, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/** One row in the voice list for an online TTS backend (Gemini/MiniMax): radio button,
 *  name + description + an optional badge. Unlike [TtsVoiceRow] there is no download/ready
 *  state -- [enabled] gates selection instead (MiniMax until its key is saved). */
@Composable
private fun OnlineTtsVoiceRow(
    id: String,
    label: String,
    description: String,
    badge: String?,
    enabled: Boolean,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = state.ttsSource == id,
            enabled = enabled,
            onClick = { viewModel.setTtsSource(id) },
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentGreen,
                unselectedColor = TextMuted,
            ),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    label,
                    color = if (enabled) TextPrimary else TextMuted,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                if (badge != null) {
                    Text(
                        badge,
                        color = TextSecondary, fontSize = 10.sp,
                        modifier = Modifier
                            .background(CardSurface, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(description, color = TextSecondary, fontSize = 12.sp)
        }
    }
}


@Composable
internal fun SmartHomeSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionHeader(text = "Умный дом")
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = "Polling",
                traceId = "alice_polling",
                checked = state.aliceEnabled,
                onCheckedChange = { viewModel.toggleAlice(it) },
            )
            SettingsTextField(
                label = "Endpoint URL",
                value = state.aliceEndpoint,
                onValueChange = { viewModel.updateAliceEndpoint(it) },
                keyboardType = KeyboardType.Uri
            )
            SettingsTextField(
                label = "API Key",
                value = state.aliceApiKey,
                onValueChange = { viewModel.updateAliceApiKey(it) },
                keyboardType = KeyboardType.Password,
                secret = true
            )
            SettingActionRow(
                title = "Сохранить",
                buttonLabel = "Сохранить",
                onClick = { viewModel.saveAliceSettings() },
                style = SettingButtonStyle.Primary,
                enabled = state.aliceEndpoint.isNotBlank() && state.aliceApiKey.isNotBlank(),
            )
            state.aliceSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
            SettingHint("Polling опрашивает Worker каждую секунду\nи выполняет команды через D+ API")
        }
    }
}

@Composable
private fun LanguageBlock(
    currentLang: String,
    onLanguageChange: (String) -> Unit
) {
    // No Activity.recreate(): MainActivity listens to LocalePreferences,
    // mutates Resources.configuration in place, and re-provides
    // LocalConfiguration so every stringResource recomposes on next frame.
    val langCodes = APP_LANGUAGES.map { it.first }
    val langLabels = APP_LANGUAGES.map { it.second }
    SectionHeader(text = stringResource(R.string.settings_language_title))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingChipRow(
                title = stringResource(R.string.settings_language_title),
                options = langLabels,
                selectedIndex = langCodes.indexOf(currentLang).coerceAtLeast(0),
                onSelect = { idx -> if (langCodes[idx] != currentLang) onLanguageChange(langCodes[idx]) },
            )
        }
    }
}

@Composable
private fun FontScaleBlock(
    current: Float,
    onChange: (Float) -> Unit
) {
    val scales = LocalePreferences.FONT_SCALES
    val labels = listOf(
        stringResource(R.string.settings_font_scale_normal),
        stringResource(R.string.settings_font_scale_large),
        stringResource(R.string.settings_font_scale_xlarge),
    )
    SectionHeader(text = stringResource(R.string.settings_font_scale_title))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingChipRow(
                title = stringResource(R.string.settings_font_scale_title),
                description = stringResource(R.string.settings_font_scale_desc),
                options = labels,
                selectedIndex = scales.indexOf(current).coerceAtLeast(0),
                onSelect = { idx -> if (scales[idx] != current) onChange(scales[idx]) },
            )
        }
    }
}

/** Preset for the custom connection card: fills URL and model, key stays the user's. */
internal data class CustomPreset(val name: String, val baseUrl: String, val model: String, val hintRes: Int)

internal val CUSTOM_PRESETS = listOf(
    CustomPreset("Cloudflare", "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1",
        "@cf/zai-org/glm-4.7-flash", R.string.settings_preset_hint_cloudflare),
    CustomPreset("Vercel", "https://ai-gateway.vercel.sh/v1",
        "openai/gpt-oss-120b", R.string.settings_preset_hint_vercel),
    CustomPreset("Kimi", "https://api.moonshot.ai/v1", "", R.string.settings_preset_hint_kimi),
    CustomPreset("NanoGPT", "https://nano-gpt.com/api/subscription/v1", "", R.string.settings_preset_hint_nanogpt),
    CustomPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", R.string.settings_preset_hint_deepseek),
    CustomPreset("Mistral", "https://api.mistral.ai/v1", "", R.string.settings_preset_hint_mistral),
)

@Composable
internal fun ConnectionCard(
    title: String,
    configured: Boolean,
    testResult: String?,
    testRunning: Boolean,
    onTest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(if (configured) AccentGreen else TextMuted, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    stringResource(
                        if (configured) R.string.settings_conn_configured
                        else R.string.settings_conn_not_configured
                    ),
                    color = if (configured) AccentGreen else TextMuted, fontSize = 12.sp
                )
            }
            content()
            OutlinedButton(
                onClick = onTest,
                enabled = configured && !testRunning,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    stringResource(
                        if (testRunning) R.string.settings_conn_checking else R.string.settings_conn_check
                    ),
                    fontSize = 13.sp
                )
            }
            testResult?.let {
                Text(it, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
internal fun ModelSelectionCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    // Options: configured connections as id -> "label · model" pairs.
    // stringResource is resolved OUTSIDE buildList (its lambda is not composable).
    val customTitle = stringResource(R.string.settings_conn_custom_title)
    val options = buildList {
        if (state.openRouterConfigured) add("openrouter" to "OpenRouter · ${state.openRouterModelName.ifBlank { state.openRouterModel }}")
        if (state.zaiConfigured) add("zai" to "z.ai · glm-4.7-flash")
        if (state.customConfigured) add("custom" to "${state.customName.ifBlank { customTitle }} · ${state.customModel}")
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.settings_models_header),
                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
            )
            ConnDropdown(
                label = stringResource(R.string.settings_model_primary),
                options = options,
                selectedId = state.primaryConn.ifBlank { "openrouter" },
                allowNone = false,
                onSelect = { viewModel.selectPrimaryConn(it) },
            )
            ConnDropdown(
                label = stringResource(R.string.settings_model_fallback),
                options = options,
                selectedId = state.fallbackConn,
                allowNone = true,
                onSelect = { viewModel.selectFallbackConn(it) },
            )
            SettingHint(stringResource(R.string.settings_models_hint))
        }
    }
}

@Composable
private fun ConnDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    allowNone: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.settings_model_none)
    val selectedLabel = options.firstOrNull { it.first == selectedId }?.second
        ?: if (allowNone) noneLabel else options.firstOrNull()?.second ?: noneLabel
    Column {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedLabel, fontSize = 13.sp, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ScaledDialogContent {
                    if (allowNone) {
                        DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(""); expanded = false })
                    }
                    options.forEach { (id, text) ->
                        DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); expanded = false })
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchStatusCard(state: SettingsUiState) {
    val searchOk = state.exaApiKey.isNotBlank() ||
        (state.primaryConn.ifBlank { "openrouter" } != "custom" && state.agentConnConfigured)
    val source = when {
        state.exaApiKey.isNotBlank() -> stringResource(R.string.settings_search_source_exa)
        state.primaryConn.ifBlank { "openrouter" } != "custom" && state.agentConnConfigured ->
            stringResource(R.string.settings_search_source_native)
        else -> stringResource(R.string.settings_search_source_none)
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingStatusRow(
                title = stringResource(R.string.settings_search_status_title),
                description = source,
                ok = searchOk,
            )
            SettingHint(stringResource(R.string.settings_search_status_hint))
        }
    }
}

/** MiniMax API key field. The real key lives only in SettingsRepository -- [keySet] (never
 *  the key itself) comes back from the ViewModel, so a saved key shows as a placeholder
 *  instead of being echoed into the field. */
@Composable
private fun MiniMaxKeyField(keySet: Boolean, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it; onSave(it) },
        label = { Text(stringResource(R.string.settings_conn_api_key_label)) },
        placeholder = if (keySet) {
            { Text(stringResource(R.string.settings_minimax_key_saved_placeholder), color = TextMuted) }
        } else null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentGreen,
            unfocusedBorderColor = CardBorder,
            focusedLabelColor = PrimaryColor,
            unfocusedLabelColor = TextSecondary,
            cursorColor = PrimaryColor,
        ),
    )
}

@Composable
internal fun CustomModelPickerDialog(
    models: List<String>,
    loading: Boolean,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ScaledDialogContent {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .heightIn(max = 500.dp)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_model_picker_title),
                        color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (loading) {
                        Text(
                            stringResource(R.string.settings_model_picker_loading),
                            color = TextSecondary, fontSize = 14.sp
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            items(models) { id ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(id) }
                                        .background(
                                            if (id == selectedId) AccentGreen.copy(alpha = 0.15f)
                                            else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        id,
                                        color = if (id == selectedId) AccentGreen else TextPrimary,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelPickerDialog(
    models: List<OpenRouterModel>,
    loading: Boolean,
    selectedId: String,
    onSelect: (OpenRouterModel) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ScaledDialogContent {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .heightIn(max = 500.dp)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_model_picker_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.settings_model_picker_search_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor = AccentGreen,
                            unfocusedLabelColor = TextSecondary,
                            cursorColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (loading) {
                        Text(stringResource(R.string.settings_model_picker_loading), color = TextSecondary, fontSize = 14.sp)
                    } else {
                        val filtered = if (searchQuery.isBlank()) models
                        else models.filter { it.name.contains(searchQuery, ignoreCase = true) ||
                            it.id.contains(searchQuery, ignoreCase = true) }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            items(filtered) { model ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(model) }
                                        .background(
                                            if (model.id == selectedId) AccentGreen.copy(alpha = 0.15f)
                                            else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        model.name,
                                        color = if (model.id == selectedId) AccentGreen else TextPrimary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    Text(
                                        if (model.pricingPrompt == 0.0) "FREE"
                                        else "${"%.2f".format(model.pricingPrompt)}$/M",
                                        color = if (model.pricingPrompt == 0.0) AccentGreen else TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
