package com.bydmate.app.ui.settings

import android.content.Context
import com.bydmate.app.cluster.ClusterEntryPoint
import com.bydmate.app.cluster.ClusterProjectionManager
import dagger.hilt.android.EntryPointAccessors
import com.bydmate.app.ui.widget.LeftTapMode
import com.bydmate.app.ui.widget.WidgetPreferences
import androidx.compose.material.icons.outlined.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.cluster.readSteeringKeyOwners
import com.bydmate.app.cluster.splitKeyChooserOwner
import com.bydmate.app.cluster.steeringKeyOwner
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.ui.automation.steeringKeyOwnerText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import com.bydmate.app.R
import com.bydmate.app.ui.components.AppLaunchPickerDialog
import com.bydmate.app.ui.theme.*
import com.bydmate.app.split.Split37Engine
import com.bydmate.app.split.SplitFreeformVerdict
import com.bydmate.app.split.SplitKeyRuleVerdict
import com.bydmate.app.split.SplitRole
import com.bydmate.app.split.splitKeyRuleVerdict
import com.bydmate.app.split.applyPick

/**
 * «Разделение экрана» settings section.
 *
 * Controls the split-screen 1/3+2/3 feature:
 *   1. Master toggle — writes to SplitPreferences and realigns enable_freeform_support via
 *      ClusterProjectionManager.realignFreeformFlag().
 *   2. Reboot hint — shown when freeform was not yet active at the time split was enabled
 *      (flag is read once at boot). Mirrors the projection section's mechanism.
 *   3. Clear-last-pair row — lets the user discard the saved pair so the next launch
 *      opens the picker instead of the saved apps.
 *   4. «Как запускать» — see [SplitLaunchBlock].
 *
 * The app rows edit the MENU pair; a launch with other apps changes only the session pair, which
 * the next process (car power cycle) forgets (SplitPreferences).
 */
@Composable
internal fun SplitSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ClusterEntryPoint::class.java)
    }
    val splitPrefs = remember { entryPoint.splitPreferences() }
    val clusterPrefs = remember {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    var splitEnabled by remember { mutableStateOf(splitPrefs.isFeatureEnabled()) }
    var nativeMode by remember { mutableStateOf(splitPrefs.isNativeModeEnabled()) }
    var hasLastPair by remember { mutableStateOf(splitPrefs.getMenuPair() != null) }
    var splitRebootPending by remember {
        mutableStateOf(clusterPrefs.getBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false))
    }
    // Latched by the split runtime, never from this screen, so a read on entering composition is
    // enough. bootCount is only consulted by noteUnavailable(), hence the stub.
    val splitFreeformUnsupported = remember {
        SplitFreeformVerdict(clusterPrefs, bootCount = { -1 }).unsupported()
    }
    // Platformized firmware (OTA V1.6): the native 3:7 split is the only mechanism there, so the
    // screen states the fact instead of offering a choice that has one outcome.
    val split37Firmware = remember { Split37Engine.isPlatformizedFirmware() }
    // #385: below Android 12 (DiLink 3 and 4) no split mechanism works, so neither the reboot
    // advice nor the native mechanism is worth offering there.
    val splitSdkUnavailable = remember { android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S }
    var clearStatus by remember { mutableStateOf<String?>(null) }
    // "?" badge on the section header; the text it toggles is the first block inside the card.
    var howToOpen by remember { mutableStateOf(false) }

    // App pair picker state. Initialized from the stored pair; updated on every pick or clear.
    var displayWidePkg by remember { mutableStateOf(splitPrefs.getMenuPair()?.widePkg) }
    var displayNarrowPkg by remember { mutableStateOf(splitPrefs.getMenuPair()?.narrowPkg) }
    // Non-null while a picker dialog is open; identifies which role is being configured.
    var openPicker by remember { mutableStateOf<SplitRole?>(null) }

    SectionHeader(
        text = stringResource(R.string.settings_section_split_title),
        onHelp = { howToOpen = !howToOpen },
    )
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // How-to text lives inside the card (like every other SettingHint) even though the
            // "?" that toggles it sits on the section header just above.
            if (howToOpen) {
                SettingHint(text = stringResource(R.string.settings_split_howto_body))
                SettingDivider()
            }
            SettingToggleRow(
                title = stringResource(R.string.settings_split_enable_title),
                traceId = "split_enable",
                description = stringResource(R.string.settings_split_enable_desc),
                checked = splitEnabled,
                onCheckedChange = { enabled ->
                    splitPrefs.setFeatureEnabled(enabled)
                    splitEnabled = enabled
                    if (enabled) {
                        // Arm the reboot hint when enabling split while freeform is not yet live
                        // (direct projection is off → flag was 0). Mirrors the projection section's
                        // KEY_FREEFORM_REBOOT_PENDING mechanism for the split consumer.
                        ClusterProjectionManager.armSplitRebootHintIfNeeded(context, splitEnabled = true)
                        splitRebootPending = clusterPrefs.getBoolean(
                            ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false)
                    } else {
                        ClusterProjectionManager.clearSplitRebootHint(context)
                        splitRebootPending = false
                    }
                    // force=true on explicit disable: bypass the passive-user guard so the flag
                    // is written to 0 even when no projection transport has been chosen (Fix 2).
                    ClusterProjectionManager.realignFreeformFlag(
                        context, entryPoint.helperClient(), entryPoint.helperBootstrap(),
                        force = !enabled)
                },
            )
            if (splitEnabled && split37Firmware) {
                SettingDivider()
                SettingHint(text = stringResource(R.string.settings_split_mechanism_platformized_hint))
            } else if (splitEnabled) {
                SettingDivider()
                // Mechanism selector: our own freeform panes vs handing the pair to the
                // firmware's split. Native is the fallback for firmwares that gate freeform.
                SettingChipRow(
                    title = stringResource(R.string.settings_split_mechanism_title),
                    options = listOf(
                        stringResource(R.string.settings_split_mechanism_freeform),
                        stringResource(R.string.settings_split_mechanism_native),
                    ),
                    selectedIndex = if (nativeMode) 1 else 0,
                    onSelect = { index ->
                        val native = index == 1
                        if (native != nativeMode) {
                            splitPrefs.setNativeModeEnabled(native)
                            nativeMode = native
                        }
                    },
                )
                if (nativeMode) {
                    SettingHint(text = stringResource(R.string.settings_split_mechanism_native_hint))
                }
            }
            // Reboot hint: shown when split was enabled while freeform was not yet active.
            // enable_freeform_support is read once at boot, so a restart is required. Once the
            // firmware is proven to ignore the flag (#139) the reboot advice is wrong — say so,
            // and point at the native mechanism, which does not depend on that flag. Both hints
            // are about freeform only, so the native mechanism — and a firmware that only has
            // the native one — hides them.
            if (splitEnabled && splitSdkUnavailable) {
                SettingHint(text = stringResource(R.string.split_freeform_unsupported_hint))
            } else if (splitEnabled && !nativeMode && !split37Firmware &&
                (splitFreeformUnsupported || splitRebootPending)) {
                val hint = if (splitFreeformUnsupported) {
                    stringResource(R.string.split_freeform_unsupported_hint) + " " +
                        stringResource(R.string.settings_split_try_native_hint)
                } else {
                    stringResource(R.string.split_freeform_reboot_hint)
                }
                SettingHint(text = hint)
            }
            if (splitEnabled) {
                SettingDivider()
                SettingValueRow(
                    title = stringResource(R.string.settings_split_wide_app_title),
                    value = displayWidePkg?.let { resolveAppLabel(context, it) }
                        ?: stringResource(R.string.settings_split_app_not_selected),
                    onClick = {
                        // Refresh display from the stored pair so the exclusion set and
                        // current-app highlight reflect any external change (e.g. overlay picker).
                        splitPrefs.getMenuPair()?.let {
                            displayWidePkg = it.widePkg
                            displayNarrowPkg = it.narrowPkg
                        }
                        openPicker = SplitRole.WIDE
                    },
                )
                SettingDivider()
                SettingValueRow(
                    title = stringResource(R.string.settings_split_narrow_app_title),
                    value = displayNarrowPkg?.let { resolveAppLabel(context, it) }
                        ?: stringResource(R.string.settings_split_app_not_selected),
                    onClick = {
                        splitPrefs.getMenuPair()?.let {
                            displayWidePkg = it.widePkg
                            displayNarrowPkg = it.narrowPkg
                        }
                        openPicker = SplitRole.NARROW
                    },
                )
            }
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_split_clear_pair_title),
                description = stringResource(R.string.settings_split_clear_pair_desc),
                buttonLabel = stringResource(R.string.settings_split_clear_pair_title),
                onClick = {
                    splitPrefs.clearMenuPair()
                    hasLastPair = false
                    displayWidePkg = null
                    displayNarrowPkg = null
                    clearStatus = context.getString(R.string.settings_split_pair_cleared)
                },
                style = SettingButtonStyle.Secondary,
                enabled = hasLastPair && splitEnabled,
            )
            clearStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
        }
    }

    if (splitEnabled) SplitLaunchBlock(viewModel)

    // Picker for the wide (2/3) app.
    if (openPicker == SplitRole.WIDE) {
        val excludedPkg = displayNarrowPkg
        AppLaunchPickerDialog(
            currentPackage = splitPrefs.getMenuPair()?.widePkg ?: "",
            excludedPackages = if (excludedPkg != null) setOf(excludedPkg) else emptySet(),
            onDismiss = { openPicker = null },
            onSelect = { pkg, _ ->
                val result = applyPick(
                    stored = splitPrefs.getMenuPair(),
                    pendingOther = displayNarrowPkg,
                    role = SplitRole.WIDE,
                    pkg = pkg,
                )
                if (result != null) {
                    splitPrefs.saveMenuPair(result)
                    hasLastPair = true
                    displayWidePkg = result.widePkg
                    displayNarrowPkg = result.narrowPkg
                } else {
                    displayWidePkg = pkg
                }
                clearStatus = null
                openPicker = null
            },
        )
    }

    // Picker for the narrow (1/3) app.
    if (openPicker == SplitRole.NARROW) {
        val excludedPkg = displayWidePkg
        AppLaunchPickerDialog(
            currentPackage = splitPrefs.getMenuPair()?.narrowPkg ?: "",
            excludedPackages = if (excludedPkg != null) setOf(excludedPkg) else emptySet(),
            onDismiss = { openPicker = null },
            onSelect = { pkg, _ ->
                val result = applyPick(
                    stored = splitPrefs.getMenuPair(),
                    pendingOther = displayWidePkg,
                    role = SplitRole.NARROW,
                    pkg = pkg,
                )
                if (result != null) {
                    splitPrefs.saveMenuPair(result)
                    hasLastPair = true
                    displayWidePkg = result.widePkg
                    displayNarrowPkg = result.narrowPkg
                } else {
                    displayNarrowPkg = pkg
                }
                clearStatus = null
                openPicker = null
            },
        )
    }
}

/**
 * Voice and projection learn dialogs: the split key is taken, as in the rule editor; otherwise the
 * earlier filter stage would win and the split key would silently stop working. Only the split
 * owner is checked here, so these dialogs keep accepting what they accepted before.
 */
internal fun splitKeyOccupiedReason(context: Context): (Int) -> String? = { code ->
    val owners = readSteeringKeyOwners(context)
        .copy(voiceEnabled = false, knobEnabled = false, projectionEnabled = false)
    steeringKeyOwner(code, owners)?.let { steeringKeyOwnerText(context, it) }
}

/**
 * «Как запускать» block of the split section: the steering key that toggles the split, the
 * widget's left-third tap (a switch with restore over the Widget section's own setting), and where
 * voice and automation fit in. The key's learn dialog refuses keys of other features and of rules
 * that do something else; split rules already on the key are offered for turning off.
 */
@Composable
private fun SplitLaunchBlock(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ClusterEntryPoint::class.java)
    }
    val splitPrefs = remember { entryPoint.splitPreferences() }
    val widgetPrefs = remember { WidgetPreferences(context) }
    val scope = rememberCoroutineScope()
    var splitKey by remember { mutableIntStateOf(splitPrefs.getSteeringKeyCode()) }
    // Rules are read when the learn dialog opens: occupancy and conflicts are checked against them.
    var rulesForKey by remember { mutableStateOf<List<RuleEntity>>(emptyList()) }
    var learningSplitKey by remember { mutableStateOf(false) }
    // Key, its companion codes, and the split rules on the key.
    var pendingKeyConflict by remember { mutableStateOf<Triple<Int, Set<Int>, List<RuleEntity>>?>(null) }
    // Non-null while asking whether the split tap may replace the app the left tap opens now.
    var replaceAppLabel by remember { mutableStateOf<String?>(null) }
    val widgetOn by widgetPrefs.enabledFlow().collectAsStateWithLifecycle(initialValue = widgetPrefs.isEnabled())
    val leftTap by widgetPrefs.leftTapAppFlow().collectAsStateWithLifecycle(
        initialValue = WidgetPreferences.LeftTapAppState(
            enabled = widgetPrefs.isLeftTapZoningEnabled(),
            packageName = widgetPrefs.getLeftTapAppPackage(),
            label = widgetPrefs.getLeftTapAppLabel(),
            mode = widgetPrefs.getLeftTapMode(),
        ),
    )

    SectionHeader(text = stringResource(R.string.settings_split_launch_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingActionRow(
                title = stringResource(R.string.settings_split_key_title),
                description = if (splitKey > 0) steeringButtonLabel(splitKey)
                else stringResource(R.string.settings_split_key_none),
                buttonLabel = stringResource(R.string.settings_split_key_assign),
                onClick = {
                    // The key is caught by our a11y filter; on a car with no projection, voice or
                    // knob it may not be bound yet, and the learn dialog would never see a press.
                    ClusterProjectionManager.enableStarControl(entryPoint.helperClient(), entryPoint.helperBootstrap())
                    scope.launch {
                        rulesForKey = viewModel.automationRulesSnapshot()
                        learningSplitKey = true
                    }
                },
                secondButtonLabel = stringResource(R.string.settings_split_key_remove).takeIf { splitKey > 0 },
                onSecondClick = {
                    viewModel.clearSplitKey()
                    splitKey = 0
                },
            )
            SettingDivider()
            SettingToggleRow(
                title = stringResource(R.string.settings_split_tap_title),
                traceId = "split_widget_tap",
                checked = leftTap.enabled && leftTap.mode == LeftTapMode.SPLIT,
                onCheckedChange = { on ->
                    if (on && widgetPrefs.leftTapOpensApp()) replaceAppLabel = widgetPrefs.getLeftTapAppLabel()
                    else widgetPrefs.setSplitTapOn(on)
                },
            )
            // The widget itself is switched on only in its own section.
            if (!widgetOn) SettingHint(text = stringResource(R.string.settings_split_tap_widget_off_hint))
            SettingDivider()
            SettingHint(text = stringResource(R.string.settings_split_voice_line))
            SettingHint(text = stringResource(R.string.settings_split_automation_line))
        }
    }

    if (learningSplitKey) {
        LearnButtonDialog(
            onSaveWithCompanions = { code, companions ->
                learningSplitKey = false
                when (val verdict = splitKeyRuleVerdict(code, rulesForKey)) {
                    is SplitKeyRuleVerdict.Conflicts -> pendingKeyConflict = Triple(code, companions, verdict.rules)
                    // Occupied never reaches onSave: occupiedReason below keeps the dialog waiting.
                    else -> {
                        viewModel.saveSplitKey(code, companions, emptyList())
                        splitKey = code
                    }
                }
            },
            onDismiss = { learningSplitKey = false },
            occupiedReason = { code ->
                splitKeyChooserOwner(code, readSteeringKeyOwners(context))
                    ?.let { steeringKeyOwnerText(context, it) }
                    ?: (splitKeyRuleVerdict(code, rulesForKey) as? SplitKeyRuleVerdict.Occupied)
                        ?.let { context.getString(R.string.settings_split_key_occupied_rule, it.ruleName) }
            },
        )
    }

    pendingKeyConflict?.let { (code, companions, rules) ->
        // Tapping outside saves nothing: the key stays as it was.
        AppAlertDialog(
            onDismissRequest = { pendingKeyConflict = null },
            containerColor = CardSurface,
            text = {
                Text(
                    if (rules.size == 1) {
                        stringResource(R.string.settings_split_key_rule_conflict_one, rules.first().name)
                    } else {
                        stringResource(R.string.settings_split_key_rule_conflict_many, rules.joinToString(", ") { it.name })
                    },
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveSplitKey(code, companions, rules.map { it.id })
                    splitKey = code
                    pendingKeyConflict = null
                }) { Text(stringResource(R.string.settings_split_key_rule_disable), color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.saveSplitKey(code, companions, emptyList())
                    splitKey = code
                    pendingKeyConflict = null
                }) { Text(stringResource(R.string.settings_split_key_rule_keep), color = TextSecondary) }
            },
        )
    }

    replaceAppLabel?.let { label ->
        AppAlertDialog(
            onDismissRequest = { replaceAppLabel = null },
            containerColor = CardSurface,
            text = {
                Text(
                    stringResource(R.string.settings_split_tap_replace_text, label),
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    widgetPrefs.setSplitTapOn(true)
                    replaceAppLabel = null
                }) { Text(stringResource(R.string.settings_split_tap_replace_confirm), color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = { replaceAppLabel = null }) {
                    Text(stringResource(R.string.settings_split_tap_replace_cancel), color = TextSecondary)
                }
            },
        )
    }
}

internal sealed interface LearnUiState {
    data object Waiting : LearnUiState
    data class Rejected(val keyCode: Int) : LearnUiState
    /** Assignable key, but already taken by another feature; [reason] explains which one. */
    data class Occupied(val keyCode: Int, val reason: String) : LearnUiState
    data class Captured(val keyCode: Int, val companions: Set<Int> = emptySet()) : LearnUiState
    data object TimedOut : LearnUiState
}

/** A capture is occupied when its primary code or any companion the same press sent is taken. */
internal fun captureOccupiedReason(keyCode: Int, companions: Set<Int>, occupiedReason: (Int) -> String?): String? =
    occupiedReason(keyCode) ?: companions.firstNotNullOfOrNull(occupiedReason)

/**
 * Learn-the-button dialog. Puts SteeringWheelKeyService into learn mode while open and collects the
 * captured key from its StateFlow (same process). States: Waiting → (Rejected/Occupied loop) →
 * Captured (confirm) / TimedOut. learnMode is always cleared on dispose.
 *
 * [occupiedReason] lets a caller veto an otherwise assignable key: a non-null text means "this key
 * already does something else" and is shown while the dialog keeps waiting for another key.
 *
 * [onSaveWithCompanions], when set, replaces [onSave]: the caller also takes the other keycodes the
 * same press sent (shown as "label (304 + 327)"). Without it companions are neither shown nor saved.
 */
@Composable
internal fun LearnButtonDialog(
    onSave: (Int) -> Unit = {},
    onDismiss: () -> Unit,
    occupiedReason: (Int) -> String? = { null },
    onSaveWithCompanions: ((Int, Set<Int>) -> Unit)? = null,
) {
    var state by remember { mutableStateOf<LearnUiState>(LearnUiState.Waiting) }

    // Clear learn mode AND the captured value on close, so a later reopen can't latch a stale capture.
    DisposableEffect(Unit) {
        onDispose {
            SteeringWheelKeyService.learnMode = false
            SteeringWheelKeyService.capturedKey.value = null
        }
    }

    // Arm capture, then react ONLY to fresh emissions. The StateFlow replays its current value to a
    // new collector, so we reset it to null first and filterNotNull() drops both that reset and any
    // stale value left from a previous session — reopening therefore always starts at Waiting.
    LaunchedEffect(Unit) {
        SteeringWheelKeyService.capturedKey.value = null
        SteeringWheelKeyService.learnMode = true
        SteeringWheelKeyService.capturedKey
            .filterNotNull()
            .collect { r ->
                val occupied = if (r.assignable) captureOccupiedReason(r.keyCode, r.companions, occupiedReason) else null
                state = when {
                    !r.assignable -> LearnUiState.Rejected(r.keyCode)
                    // The service clears learn mode on capture; re-arm so the next press is caught.
                    occupied != null -> {
                        SteeringWheelKeyService.learnMode = true
                        LearnUiState.Occupied(r.keyCode, occupied)
                    }
                    else -> {
                        SteeringWheelKeyService.learnMode = false
                        LearnUiState.Captured(
                            r.keyCode, if (onSaveWithCompanions != null) r.companions else emptySet())
                    }
                }
            }
    }

    // Timeout while still waiting/rejected (no assignable capture yet).
    LaunchedEffect(state) {
        if (state.isWaitingForKey()) {
            delay(10_000)
            if (state.isWaitingForKey()) {
                SteeringWheelKeyService.learnMode = false
                state = LearnUiState.TimedOut
            }
        }
    }

    // "Again": re-arm. The collector above keeps running (keyed on Unit), so just reset + Waiting.
    val restart = {
        SteeringWheelKeyService.capturedKey.value = null
        SteeringWheelKeyService.learnMode = true
        state = LearnUiState.Waiting
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.learn_button_dialog_title)) },
        text = {
            when (val s = state) {
                is LearnUiState.Waiting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen)
                    Text(stringResource(R.string.learn_button_waiting))
                }
                is LearnUiState.Rejected -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.learn_button_rejected))
                    Text(steeringButtonLabel(s.keyCode), color = TextSecondary, fontSize = 12.sp)
                }
                is LearnUiState.Occupied -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.reason)
                    Text(
                        "${steeringButtonLabel(s.keyCode)} (${s.keyCode})",
                        color = TextSecondary, fontSize = 12.sp,
                    )
                }
                is LearnUiState.Captured -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.learn_button_captured))
                    Text(
                        "${steeringButtonLabel(s.keyCode)} " +
                            "(${(listOf(s.keyCode) + s.companions.sorted()).joinToString(" + ")})",
                        color = TextPrimary, fontWeight = FontWeight.Medium,
                    )
                }
                is LearnUiState.TimedOut -> Text(stringResource(R.string.learn_button_timeout))
            }
        },
        confirmButton = {
            when (val s = state) {
                is LearnUiState.Captured -> TextButton(onClick = {
                    if (onSaveWithCompanions != null) onSaveWithCompanions(s.keyCode, s.companions)
                    else onSave(s.keyCode)
                }) {
                    Text(stringResource(R.string.learn_button_save))
                }
                is LearnUiState.TimedOut -> TextButton(onClick = restart) {
                    Text(stringResource(R.string.learn_button_again))
                }
                else -> {}
            }
        },
        dismissButton = {
            when (state) {
                is LearnUiState.Captured -> TextButton(onClick = restart) {
                    Text(stringResource(R.string.learn_button_again))
                }
                else -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.learn_button_cancel))
                }
            }
        },
    )
}

/** True while the dialog is still expecting a key press (nothing accepted yet). */
private fun LearnUiState.isWaitingForKey(): Boolean =
    this is LearnUiState.Waiting || this is LearnUiState.Rejected || this is LearnUiState.Occupied
