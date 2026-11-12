package com.bydmate.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import com.bydmate.app.camera.BlindSpotPositionOverlay
import com.bydmate.app.camera.BlindSpotPreferences
import com.bydmate.app.camera.blindSpotClusterDisplay
import com.bydmate.app.cluster.ClusterEntryPoint
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.cluster.CENTER_OFFSET_PCT
import com.bydmate.app.cluster.MAX_OFFSET_PCT
import com.bydmate.app.cluster.MAX_PROJECTION_PCT
import com.bydmate.app.cluster.MIN_OFFSET_PCT
import com.bydmate.app.cluster.MIN_PROJECTION_PCT
import com.bydmate.app.cluster.MIN_SCALE_PCT
import com.bydmate.app.cluster.MAX_SCALE_PCT
import com.bydmate.app.cluster.DEFAULT_SCALE_PCT
import com.bydmate.app.cluster.NAVI_PACKAGE
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.roundToInt
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.ui.widget.LeftTapMode
import com.bydmate.app.ui.widget.WidgetController
import com.bydmate.app.ui.widget.WidgetPreferences
import androidx.compose.material.icons.outlined.Home
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bydmate.app.ui.components.AppAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import com.bydmate.app.cluster.DEFAULT_TRIGGER_KEYCODE
import com.bydmate.app.R
import com.bydmate.app.ui.components.AppLaunchPickerDialog
import com.bydmate.app.ui.components.MultiAppPickerDialog
import com.bydmate.app.ui.theme.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.bydmate.app.hud.HudController
import com.bydmate.app.split.SplitFreeformVerdict

@Composable
internal fun WidgetSection() {
    val context = LocalContext.current
    val prefs = remember { WidgetPreferences(context) }
    val enabled by prefs.enabledFlow().collectAsStateWithLifecycle(initialValue = prefs.isEnabled())
    // Read split feature state once at composition time to gate the left-tap mode chip.
    val splitFeatureEnabled = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ClusterEntryPoint::class.java)
            .splitPreferences().isFeatureEnabled()
    }
    val alpha by prefs.alphaFlow().collectAsStateWithLifecycle(initialValue = prefs.getAlpha())
    val scale by prefs.scaleFlow().collectAsStateWithLifecycle(initialValue = prefs.getScale())
    val leftTapApp by prefs.leftTapAppFlow().collectAsStateWithLifecycle(
        initialValue = WidgetPreferences.LeftTapAppState(
            enabled = prefs.isLeftTapZoningEnabled(),
            packageName = prefs.getLeftTapAppPackage(),
            label = prefs.getLeftTapAppLabel(),
        ),
    )
    val buttonsEnabled by prefs.buttonsEnabledFlow().collectAsStateWithLifecycle(
        initialValue = prefs.isButtonsEnabled(),
    )
    val hideOnYoutube by prefs.hideOnYoutubeFlow()
        .collectAsStateWithLifecycle(initialValue = prefs.isHideOnYoutube())
    val homeOnly by prefs.homeOnlyFlow()
        .collectAsStateWithLifecycle(initialValue = prefs.isHomeOnly())
    val hideInApps by prefs.hideInAppsFlow()
        .collectAsStateWithLifecycle(initialValue = prefs.getHideInApps())
    var showLeftTapPicker by remember { mutableStateOf(false) }
    var showHideInAppsPicker by remember { mutableStateOf(false) }

    SectionHeader(text = stringResource(R.string.settings_widget_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = stringResource(R.string.settings_widget_show_soc_label),
                traceId = "widget_show_soc",
                description = stringResource(R.string.settings_widget_show_desc),
                checked = enabled,
                onCheckedChange = { requested ->
                    if (requested) {
                        if (AndroidSettings.canDrawOverlays(context)) {
                            prefs.setEnabled(true)
                            WidgetController.attach(context, "settings_toggle_on")
                        } else {
                            val intent = Intent(
                                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        }
                    } else {
                        prefs.setEnabled(false)
                        WidgetController.detach("settings_toggle_off")
                    }
                },
            )
            SettingHint(text = stringResource(R.string.settings_widget_hints))
            SettingToggleRow(
                title = stringResource(R.string.settings_widget_hide_youtube_label),
                traceId = "widget_hide_youtube",
                description = stringResource(R.string.settings_widget_hide_youtube_description),
                checked = hideOnYoutube,
                onCheckedChange = { prefs.setHideOnYoutube(it) },
            )
            SettingToggleRow(
                title = stringResource(R.string.settings_widget_home_only_label),
                traceId = "widget_home_only",
                description = stringResource(R.string.settings_widget_home_only_description),
                checked = homeOnly,
                onCheckedChange = { prefs.setHomeOnly(it) },
            )
            SettingValueRow(
                title = stringResource(R.string.settings_widget_hide_apps_label),
                description = stringResource(R.string.settings_widget_hide_apps_description),
                value = if (hideInApps.isEmpty()) {
                    stringResource(R.string.settings_widget_hide_apps_none)
                } else {
                    stringResource(R.string.settings_widget_hide_apps_count, hideInApps.size)
                },
                onClick = { showHideInAppsPicker = true },
            )
            SettingSliderRow(
                title = stringResource(R.string.settings_widget_opacity_label),
                value = alpha,
                onValueChange = {
                    if (enabled) WidgetController.setPreviewMode(context, true)
                    prefs.setAlpha(it)
                },
                valueRange = 0.3f..1.0f,
                valueLabel = "${(alpha * 100).toInt()}%",
                enabled = enabled,
            )
            SettingSliderRow(
                title = stringResource(R.string.settings_widget_scale_label),
                value = scale,
                onValueChange = {
                    if (enabled) WidgetController.setPreviewMode(context, true)
                    prefs.setScale(it)
                },
                valueRange = WidgetPreferences.SCALE_MIN..WidgetPreferences.SCALE_MAX,
                valueLabel = "${(scale * 100).toInt()}%",
                enabled = enabled,
            )
            SettingActionRow(
                title = stringResource(R.string.settings_widget_reset_position_button),
                description = stringResource(R.string.settings_widget_reset_position_desc),
                buttonLabel = stringResource(R.string.settings_widget_reset_position_button),
                onClick = {
                    prefs.resetPosition()
                    if (enabled && AndroidSettings.canDrawOverlays(context)) {
                        WidgetController.detach("reset_position")
                        WidgetController.attach(context, "reset_position")
                    }
                },
                style = SettingButtonStyle.Secondary,
                enabled = enabled,
            )
        }
    }

    SectionHeader(text = stringResource(R.string.settings_widget_tap_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = stringResource(R.string.settings_widget_left_tap_zoning_label),
                traceId = "widget_left_tap_zoning",
                description = stringResource(R.string.settings_widget_left_tap_zoning_description),
                checked = leftTapApp.enabled,
                onCheckedChange = { prefs.setLeftTapZoningEnabled(it) },
                enabled = enabled,
            )
            if (leftTapApp.enabled) {
                SettingChipRow(
                    title = stringResource(R.string.settings_widget_left_tap_mode_label),
                    options = listOf(
                        stringResource(R.string.settings_widget_left_tap_mode_app),
                        stringResource(R.string.settings_widget_left_tap_mode_split),
                    ),
                    selectedIndex = if (leftTapApp.mode == LeftTapMode.APP) 0 else 1,
                    onSelect = { idx ->
                        if (leftTapModeSelectable(idx, splitFeatureEnabled)) {
                            prefs.setLeftTapMode(if (idx == 0) LeftTapMode.APP else LeftTapMode.SPLIT)
                        }
                    },
                    // Stays enabled with split off if SPLIT is still the saved mode, so the
                    // user has a way to switch out of it (#188).
                    enabled = leftTapModeChipEnabled(enabled, splitFeatureEnabled, leftTapApp.mode),
                )
            }
            SettingToggleRow(
                title = stringResource(R.string.settings_widget_buttons_label),
                traceId = "widget_buttons",
                description = stringResource(R.string.settings_widget_buttons_description),
                checked = buttonsEnabled,
                onCheckedChange = { prefs.setButtonsEnabled(it) },
                enabled = enabled,
            )
            SettingValueRow(
                title = stringResource(R.string.settings_widget_left_tap_label),
                description = stringResource(R.string.settings_widget_left_tap_description),
                value = leftTapApp.label,
                onClick = { showLeftTapPicker = true },
                enabled = leftTapApp.enabled && leftTapApp.mode == LeftTapMode.APP && enabled,
            )
        }
    }

    if (showLeftTapPicker) {
        AppLaunchPickerDialog(
            currentPackage = leftTapApp.packageName,
            onDismiss = { showLeftTapPicker = false },
            onSelect = { pkg, label ->
                prefs.setLeftTapApp(pkg, label)
                showLeftTapPicker = false
            },
            showMinimizeToggle = false,
        )
    }

    if (showHideInAppsPicker) {
        MultiAppPickerDialog(
            title = stringResource(R.string.settings_widget_hide_apps_label),
            selectedPackages = hideInApps,
            onDismiss = { showHideInAppsPicker = false },
            onConfirm = { picked ->
                prefs.setHideInApps(picked)
                showHideInAppsPicker = false
            },
        )
    }
}

/**
 * Whether the "left tap mode" chip row should be interactive. With the split feature off the
 * row still stays enabled if SPLIT is the saved mode, so the user has a way to switch out of
 * it instead of being stuck (#188).
 */
internal fun leftTapModeChipEnabled(
    widgetEnabled: Boolean,
    splitFeatureEnabled: Boolean,
    mode: LeftTapMode,
): Boolean = widgetEnabled && (splitFeatureEnabled || mode == LeftTapMode.SPLIT)

/**
 * Whether a chip tap (idx 0 = APP, idx 1 = SPLIT) should be applied. APP is always accepted;
 * SPLIT is ignored while the split feature is disabled.
 */
internal fun leftTapModeSelectable(idx: Int, splitFeatureEnabled: Boolean): Boolean =
    idx == 0 || splitFeatureEnabled

@Composable
internal fun DisplaySection() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ClusterEntryPoint::class.java)
    }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, false))
    }
    var widthPct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_WIDTH_PCT, MAX_PROJECTION_PCT))
    }
    var heightPct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_HEIGHT_PCT, MAX_PROJECTION_PCT))
    }
    var offsetXPct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_OFFSET_X_PCT, CENTER_OFFSET_PCT))
    }
    var offsetYPct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_OFFSET_Y_PCT, CENTER_OFFSET_PCT))
    }
    var scalePct by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_SCALE_PCT, DEFAULT_SCALE_PCT))
    }
    var targetPkg by remember {
        mutableStateOf(prefs.getString(ClusterProjectionManager.KEY_TARGET_PACKAGE, NAVI_PACKAGE) ?: NAVI_PACKAGE)
    }
    var targetLabel by remember {
        mutableStateOf(
            prefs.getString(ClusterProjectionManager.KEY_TARGET_LABEL, null)
                ?: resolveAppLabel(context, targetPkg)
        )
    }
    var pickingApp by remember { mutableStateOf(false) }
    var learning by remember { mutableStateOf(false) }
    var triggerKey by remember {
        mutableStateOf(prefs.getInt(ClusterProjectionManager.KEY_TRIGGER_KEYCODE, DEFAULT_TRIGGER_KEYCODE))
    }
    var autoContainer by remember {
        mutableStateOf(prefs.getBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, true))
    }
    var rebootPending by remember {
        mutableStateOf(prefs.getBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, false))
    }
    // Latched by the projection runtime, never from this screen, so a read on entering composition
    // is enough. bootCount is only consulted by noteUnavailable(), hence the stub.
    val freeformUnsupported = remember {
        SplitFreeformVerdict(prefs, bootCount = { -1 }).unsupported()
    }
    var directProjection by remember {
        mutableStateOf(ClusterProjectionManager.isDirectProjectionEnabled(context))
    }
    // #194: a car whose cluster only the daemon sees always runs the direct transport.
    val directForced = remember { ClusterProjectionManager.isDirectForced(context) }
    val modeRow = projectionModeRow(directProjection, directForced)
    var extendedConfirmOpen by remember { mutableStateOf(false) }
    var modeHelpOpen by remember { mutableStateOf(false) }
    var autoContainerHelpOpen by remember { mutableStateOf(false) }
    var preferFullDisplay by remember {
        mutableStateOf(ClusterProjectionManager.isPreferFullDisplay(context))
    }

    // Persist the new size and re-apply it live. reproject() is a no-op unless we are actively
    // projecting, so a tweak while OFF just lands in prefs and shows on the next star press.
    val applyGeometry: () -> Unit = {
        prefs.edit()
            .putInt(ClusterProjectionManager.KEY_WIDTH_PCT, widthPct)
            .putInt(ClusterProjectionManager.KEY_HEIGHT_PCT, heightPct)
            .putInt(ClusterProjectionManager.KEY_OFFSET_X_PCT, offsetXPct)
            .putInt(ClusterProjectionManager.KEY_OFFSET_Y_PCT, offsetYPct)
            .putInt(ClusterProjectionManager.KEY_SCALE_PCT, scalePct)
            .apply()
        ClusterProjectionManager.reproject(
            context, entryPoint.helperClient(), entryPoint.helperBootstrap())
    }

    SectionHeader(text = stringResource(R.string.settings_section_display_title))
    SettingCollapsibleCard(
        title = stringResource(R.string.settings_display_card_mirror_title),
        subtitle = if (enabled) {
            stringResource(
                R.string.settings_display_card_mirror_sub_app,
                stringResource(R.string.settings_display_state_on),
                targetLabel,
            )
        } else {
            stringResource(
                R.string.settings_display_card_mirror_sub,
                stringResource(R.string.settings_display_state_off),
            )
        },
        checked = enabled,
        onCheckedChange = {
            enabled = it
            prefs.edit().putBoolean(ClusterProjectionManager.KEY_MIRROR_ENABLED, it).apply()
            // Turning the switch on self-enables our a11y key filter via the daemon, so star
            // control works on a clean install with no ADB (DiLink has no a11y settings UI).
            if (it) {
                ClusterProjectionManager.enableStarControl(
                    entryPoint.helperClient(), entryPoint.helperBootstrap())
            }
        },
    ) {
        // Auto power-on toggle: when enabled, ClusterProjectionManager wakes the cluster compositor
        // before sending the projection window.
        SettingToggleRow(
            title = stringResource(R.string.settings_cluster_auto_container_title),
            traceId = "cluster_auto_container",
            checked = autoContainer,
            onCheckedChange = {
                autoContainer = it
                prefs.edit().putBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, it).apply()
            },
            onHelp = { autoContainerHelpOpen = !autoContainerHelpOpen },
        )
        if (autoContainerHelpOpen) {
            SettingHint(text = stringResource(R.string.settings_cluster_auto_container_desc))
        }
        SettingDivider()
        // Transport selector: direct freeform (agent/HUD can see the navigator) vs the
        // pre-3.6 VirtualDisplay pipeline. VD also returns the system freeform flag to its
        // factory value — the fix for third-party projection apps broken by a stale flag.
        if (modeRow == ProjectionModeRow.OnlyExtended) {
            SettingInfoRow(
                title = stringResource(R.string.settings_projection_mode_title),
                description = stringResource(R.string.settings_projection_mode_only_extended),
            )
        } else {
            SettingChipRow(
                title = stringResource(R.string.settings_projection_mode_title),
                options = listOf(
                    stringResource(R.string.settings_projection_mode_vd),
                    stringResource(R.string.settings_projection_mode_direct),
                ),
                selectedIndex = if (directProjection) 1 else 0,
                onSelect = { index ->
                    val direct = index == 1
                    if (direct != directProjection) {
                        if (direct) {
                            // Extended transport changes a system window setting - informed
                            // consent first: what changes, why, and how to restore factory.
                            extendedConfirmOpen = true
                        } else {
                            directProjection = false
                            rebootPending = false
                            ClusterProjectionManager.setDirectProjectionEnabled(
                                context, false, entryPoint.helperClient(), entryPoint.helperBootstrap())
                        }
                    }
                },
                onHelp = { modeHelpOpen = !modeHelpOpen },
            )
            if (modeHelpOpen) {
                SettingHint(text = stringResource(R.string.settings_projection_mode_help))
            }
        }
        SettingDivider()
        // Which cluster projection surface to render on: the native mini band ("..._1", default)
        // or the full cluster ("..._0"). Only the pref is written — like the transport chip, it
        // applies at the next projection start, never to a live projection.
        SettingToggleRow(
            title = stringResource(R.string.settings_cluster_full_display_title),
            traceId = "cluster_full_display",
            description = stringResource(R.string.settings_cluster_full_display_desc),
            checked = preferFullDisplay,
            onCheckedChange = {
                preferFullDisplay = it
                ClusterProjectionManager.setPreferFullDisplay(context, it)
            },
        )
        if (extendedConfirmOpen) {
            AppAlertDialog(
                onDismissRequest = { extendedConfirmOpen = false },
                containerColor = CardSurface,
                title = {
                    Text(
                        stringResource(R.string.projection_extended_confirm_title),
                        color = TextPrimary,
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.projection_extended_confirm_body),
                        color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        extendedConfirmOpen = false
                        directProjection = true
                        ClusterProjectionManager.setDirectProjectionEnabled(
                            context, true, entryPoint.helperClient(), entryPoint.helperBootstrap())
                    }) { Text(stringResource(R.string.projection_extended_confirm_enable), color = AccentGreen) }
                },
                dismissButton = {
                    TextButton(onClick = { extendedConfirmOpen = false }) {
                        Text(stringResource(R.string.projection_extended_confirm_cancel), color = TextSecondary)
                    }
                },
            )
        }
        // Once the firmware is proven to ignore the freeform flag (#139) the reboot advice is
        // wrong — say so instead.
        if (directProjection && (freeformUnsupported || rebootPending)) {
            SettingHint(text = stringResource(
                if (freeformUnsupported) R.string.cluster_direct_unsupported_hint
                else R.string.settings_cluster_direct_reboot_hint
            ))
        }
        // Trigger-button row — only meaningful while the feature (and thus the a11y service) is on.
        if (enabled) {
            SettingDivider()
            SettingValueRow(
                title = stringResource(R.string.settings_display_button_title),
                value = steeringButtonLabel(triggerKey),
                onClick = { learning = true },
            )
        }
        SettingDivider()
        // App to project — defaults to Yandex Navi. Takes effect on the next star press, not live.
        SettingValueRow(
            title = stringResource(R.string.settings_display_app_title),
            value = targetLabel,
            onClick = { pickingApp = true },
        )
        // Defaults (100/100 size, centered, scale 100) reproduce the plain fullscreen projection, so
        // cars without a native mini zone (e.g. Leopard 3) need no tuning. The offset sliders only
        // matter once the window is smaller than the panel; on Sea Lion 07 they let the user move the
        // window into the native mini-cluster zone (#48).
        SettingSubhead(text = stringResource(R.string.settings_display_window_subhead))
        SettingSliderRow(
            title = stringResource(R.string.settings_display_size_width),
            description = stringResource(R.string.settings_display_size_width_desc),
            value = widthPct.toFloat(),
            onValueChange = { widthPct = it.roundToInt() },
            valueRange = MIN_PROJECTION_PCT.toFloat()..MAX_PROJECTION_PCT.toFloat(),
            valueLabel = "${widthPct}%",
            steps = (MAX_PROJECTION_PCT - MIN_PROJECTION_PCT) / 2 - 1,
            enabled = enabled,
            onValueChangeFinished = applyGeometry,
        )
        SettingDivider()
        SettingSliderRow(
            title = stringResource(R.string.settings_display_size_height),
            description = stringResource(R.string.settings_display_size_height_desc),
            value = heightPct.toFloat(),
            onValueChange = { heightPct = it.roundToInt() },
            valueRange = MIN_PROJECTION_PCT.toFloat()..MAX_PROJECTION_PCT.toFloat(),
            valueLabel = "${heightPct}%",
            steps = (MAX_PROJECTION_PCT - MIN_PROJECTION_PCT) / 2 - 1,
            enabled = enabled,
            onValueChangeFinished = applyGeometry,
        )
        SettingDivider()
        SettingSliderRow(
            title = stringResource(R.string.settings_display_offset_x),
            description = stringResource(R.string.settings_display_offset_x_desc),
            value = offsetXPct.toFloat(),
            onValueChange = { offsetXPct = it.roundToInt() },
            valueRange = MIN_OFFSET_PCT.toFloat()..MAX_OFFSET_PCT.toFloat(),
            valueLabel = "${offsetXPct}%",
            steps = (MAX_OFFSET_PCT - MIN_OFFSET_PCT) / 2 - 1,
            enabled = enabled,
            onValueChangeFinished = applyGeometry,
        )
        SettingDivider()
        SettingSliderRow(
            title = stringResource(R.string.settings_display_offset_y),
            description = stringResource(R.string.settings_display_offset_y_desc),
            value = offsetYPct.toFloat(),
            onValueChange = { offsetYPct = it.roundToInt() },
            valueRange = MIN_OFFSET_PCT.toFloat()..MAX_OFFSET_PCT.toFloat(),
            valueLabel = "${offsetYPct}%",
            steps = (MAX_OFFSET_PCT - MIN_OFFSET_PCT) / 2 - 1,
            enabled = enabled,
            onValueChangeFinished = applyGeometry,
        )
        SettingDivider()
        SettingSliderRow(
            title = stringResource(R.string.settings_display_scale),
            description = stringResource(R.string.settings_display_scale_desc),
            value = scalePct.toFloat(),
            onValueChange = { scalePct = it.roundToInt() },
            valueRange = MIN_SCALE_PCT.toFloat()..MAX_SCALE_PCT.toFloat(),
            valueLabel = "${scalePct}%",
            steps = (MAX_SCALE_PCT - MIN_SCALE_PCT) / 2 - 1,
            enabled = enabled,
            onValueChangeFinished = applyGeometry,
        )
        // #121: the VD transport scales by sizing the render buffer, direct mode by a density
        // override on the cluster display. That override is only ever set while no window of the
        // app is on the display (it kills Qt apps like 2GIS otherwise), so in direct mode a new
        // value lands on the next send to the cluster rather than right away.
        if (modeRow.effectiveDirect) {
            SettingHint(text = stringResource(R.string.settings_display_scale_direct_hint))
        }
    }

    // HUD navigation output (factory head-up display via the SOME/IP bus). Uses the same
    // ClusterEntryPoint as projection - HudController is a @Singleton behind it.
    val hudController = remember { entryPoint.hudController() }
    var hudEnabled by remember { mutableStateOf(hudController.isEnabled()) }
    var hudSpeedSign by remember { mutableStateOf(hudController.isSpeedSignEnabled()) }
    var hudMode by remember { mutableStateOf(hudController.mode()) }
    val hudStatus by hudController.status.collectAsState()

    SettingCollapsibleCard(
        title = stringResource(R.string.settings_display_card_hud_title),
        subtitle = stringResource(
            R.string.settings_display_card_hud_sub,
            stringResource(
                if (hudEnabled) R.string.settings_display_state_on
                else R.string.settings_display_state_off
            ),
        ),
        checked = hudEnabled,
        onCheckedChange = {
            hudEnabled = it
            hudController.setEnabled(it)
        },
    ) {
        if (hudEnabled) {
            // No gateway (#301): the hints go to the cluster card, the glass settings do not apply.
            if (hudStatus != HudController.Status.CLUSTER_ONLY) {
                SettingToggleRow(
                    title = stringResource(R.string.settings_hud_speed_sign_title),
                    traceId = "hud_speed_sign",
                    description = stringResource(R.string.settings_hud_speed_sign_desc),
                    checked = hudSpeedSign,
                    onCheckedChange = {
                        hudSpeedSign = it
                        hudController.setSpeedSignEnabled(it)
                    },
                )
                SettingDivider()
                // «Способ вывода на стекло» (#266): way 1 (default) never raises the car's navigation
                // status, ways 2 and 3 do and add their channels (HudWayChannels).
                val hudWays = listOf(HudController.MODE_GLASS_ONLY, HudController.MODE_NAVI_STATUS, HudController.MODE_LMCN)
                SettingChipRow(
                    title = stringResource(R.string.settings_hud_mode_title),
                    options = listOf(
                        stringResource(R.string.settings_hud_mode_1),
                        stringResource(R.string.settings_hud_mode_2),
                        stringResource(R.string.settings_hud_mode_3),
                    ),
                    selectedIndex = hudWays.indexOf(hudMode).coerceAtLeast(0),
                    onSelect = { idx ->
                        val mode = hudWays[idx]
                        if (mode != hudMode) {
                            Trace.event(TraceArea.USER, "choice", "id" to "hud_mode", "mode" to mode)
                            hudMode = mode
                            hudController.setMode(mode)
                        }
                    },
                )
                SettingHint(text = stringResource(R.string.settings_hud_mode_pick_hint))
                SettingHint(text = stringResource(R.string.settings_hud_mode_1_desc))
                SettingHint(text = stringResource(R.string.settings_hud_mode_2_desc))
                HudTrialHint(
                    text = stringResource(R.string.settings_hud_mode_3_desc),
                    tag = stringResource(R.string.settings_hud_mode_trial),
                )
                SettingDivider()
            }
            SettingStatusRow(
                title = when (hudStatus) {
                    HudController.Status.ON -> stringResource(R.string.settings_hud_status_on)
                    HudController.Status.UNSUPPORTED -> stringResource(R.string.settings_hud_status_unsupported)
                    HudController.Status.BIND_FAILED -> stringResource(R.string.settings_hud_status_bind_failed)
                    HudController.Status.CLUSTER_ONLY -> stringResource(R.string.settings_hud_status_cluster_only)
                    else -> stringResource(R.string.settings_hud_status_connecting)
                },
                ok = hudStatus == HudController.Status.ON || hudStatus == HudController.Status.CLUSTER_ONLY,
            )
        }
        if (hudStatus != HudController.Status.CLUSTER_ONLY) {
            SettingHint(text = stringResource(R.string.settings_hud_hint))
        }
        // Outside the switch: the check is meant to run with HUD output turned off too.
        SettingDivider()
        HudCheckRow(onWayChosen = { hudMode = it })
    }

    if (learning) {
        LearnButtonDialog(
            onSave = { code ->
                triggerKey = code
                ClusterProjectionManager.setTriggerKeyCode(context, code)
                learning = false
            },
            onDismiss = { learning = false },
            occupiedReason = splitKeyOccupiedReason(context),
        )
    }

    if (pickingApp) {
        AppLaunchPickerDialog(
            currentPackage = targetPkg,
            onDismiss = { pickingApp = false },
            onSelect = { newPkg, newLabel ->
                targetPkg = newPkg
                targetLabel = newLabel
                prefs.edit()
                    .putString(ClusterProjectionManager.KEY_TARGET_PACKAGE, newPkg)
                    .putString(ClusterProjectionManager.KEY_TARGET_LABEL, newLabel)
                    .apply()
                pickingApp = false
            },
        )
    }

    BlindSpotCard()

    // Instrument music card: mirrors Yandex music, which the stock controller leaves blank.
    // ClusterMusicBridge reads the flag on every poll, so no restart is needed.
    var clusterMusicCard by remember {
        mutableStateOf(prefs.getBoolean(ClusterProjectionManager.KEY_CLUSTER_MUSIC_CARD, false))
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.settings_cluster_music_card_title),
                traceId = "cluster_music_card",
                description = stringResource(R.string.settings_cluster_music_card_desc),
                checked = clusterMusicCard,
                onCheckedChange = {
                    clusterMusicCard = it
                    prefs.edit().putBoolean(ClusterProjectionManager.KEY_CLUSTER_MUSIC_CARD, it).apply()
                },
            )
        }
    }
}

/**
 * «Слепые зоны»: turn signal → blind-spot camera. Reads and writes the feature's own
 * SharedPreferences file directly, like the projection cards above; BlindSpotController
 * re-reads it on every tick, so a change lands without a restart. Turning the switch on asks
 * for CAMERA — the AVM stack refuses to open the preview without it (as on the probe screen).
 */
@Composable
private fun BlindSpotCard() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(BlindSpotPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var enabled by remember { mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_ENABLED, false)) }
    var thresholdKmh by remember {
        mutableStateOf(prefs.getInt(
            BlindSpotPreferences.KEY_THRESHOLD_KMH, BlindSpotPreferences.DEFAULT_THRESHOLD_KMH))
    }
    var pipWidthPct by remember {
        mutableStateOf(prefs.getInt(
            BlindSpotPreferences.KEY_PIP_WIDTH_PCT, BlindSpotPreferences.DEFAULT_PIP_WIDTH_PCT))
    }
    var bsdGlow by remember { mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_BSD_GLOW, true)) }
    var rotate90 by remember {
        mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_PIP_ROTATE_90, false))
    }
    var bothOnMain by remember {
        mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_BOTH_ON_MAIN, false))
    }
    var bothOnCluster by remember {
        mutableStateOf(prefs.getBoolean(BlindSpotPreferences.KEY_BOTH_ON_CLUSTER, false))
    }
    // Same lookup the controller routes by: without a projection panel there is no cluster to offer.
    val hasClusterDisplay = remember { blindSpotClusterDisplay(context) != null }

    // Drag-to-place preview: it lives in a WindowManager overlay, so leaving the screen has to
    // take it down explicitly. The state follows the window rather than the clicks — the overlay
    // also goes away on its own idle timer. One window at a time, so the side being placed is
    // the state (null = nothing on screen).
    val placingState = remember { mutableStateOf<BlindSpotPositionOverlay.Side?>(null) }
    var placing by placingState
    val positionOverlay = remember {
        BlindSpotPositionOverlay().apply { onHidden = { placingState.value = null } }
    }
    // MainActivity survives configuration changes, so onDispose alone never fires when the driver
    // goes Home — an opaque touchable window would stay over whatever is on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) positionOverlay.hide()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            positionOverlay.hide()
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* denial is answered by the pipeline itself: the camera simply never opens */ }

    SettingCollapsibleCard(
        title = stringResource(R.string.settings_blindspot_header),
        subtitle = stringResource(
            R.string.settings_display_card_blindspot_sub,
            stringResource(
                if (enabled) R.string.settings_display_state_on
                else R.string.settings_display_state_off
            ),
        ),
        checked = enabled,
        onCheckedChange = {
            enabled = it
            BlindSpotPreferences.setEnabled(prefs, it)
            if (it && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
            // Switching off disables the "Готово" row too, so the overlay has to be taken
            // down here or nothing on screen can dismiss it.
            if (!it) positionOverlay.hide()
        },
    ) {
        SettingSliderRow(
            title = stringResource(R.string.settings_blindspot_threshold_title),
            description = stringResource(R.string.settings_blindspot_threshold_desc),
            value = thresholdKmh.toFloat(),
            onValueChange = { thresholdKmh = it.roundToInt() },
            valueRange = BlindSpotPreferences.MIN_THRESHOLD_KMH.toFloat()..
                BlindSpotPreferences.MAX_THRESHOLD_KMH.toFloat(),
            valueLabel = "$thresholdKmh ${stringResource(R.string.auto_unit_kmh)}",
            steps = (BlindSpotPreferences.MAX_THRESHOLD_KMH - BlindSpotPreferences.MIN_THRESHOLD_KMH) / 5 - 1,
            enabled = enabled,
            onValueChangeFinished = {
                val was = prefs.getInt(
                    BlindSpotPreferences.KEY_THRESHOLD_KMH, BlindSpotPreferences.DEFAULT_THRESHOLD_KMH)
                prefs.edit().putInt(BlindSpotPreferences.KEY_THRESHOLD_KMH, thresholdKmh).apply()
                Trace.event(TraceArea.USER, "slider", "id" to "blindspot_threshold", "value" to thresholdKmh, "was" to was)
            },
        )
        SettingDivider()
        SettingSliderRow(
            title = stringResource(R.string.settings_blindspot_pip_width_title),
            description = stringResource(R.string.settings_blindspot_pip_width_desc),
            value = pipWidthPct.toFloat(),
            onValueChange = { pipWidthPct = it.roundToInt() },
            valueRange = BlindSpotPreferences.MIN_PIP_WIDTH_PCT.toFloat()..
                BlindSpotPreferences.MAX_PIP_WIDTH_PCT.toFloat(),
            valueLabel = "${pipWidthPct}%",
            steps = (BlindSpotPreferences.MAX_PIP_WIDTH_PCT - BlindSpotPreferences.MIN_PIP_WIDTH_PCT) / 2 - 1,
            enabled = enabled,
            onValueChangeFinished = {
                prefs.edit().putInt(BlindSpotPreferences.KEY_PIP_WIDTH_PCT, pipWidthPct).apply()
                if (placing != null) positionOverlay.refreshSize()
            },
        )
        SettingDivider()
        SettingToggleRow(
            title = stringResource(R.string.settings_blindspot_rotate_title),
            traceId = "blindspot_rotate",
            description = stringResource(R.string.settings_blindspot_rotate_desc),
            checked = rotate90,
            onCheckedChange = {
                rotate90 = it
                prefs.edit().putBoolean(BlindSpotPreferences.KEY_PIP_ROTATE_90, it).apply()
                // Same path the width slider uses: the drag stand-in has to change shape with the
                // camera window, and the controller re-applies the geometry on its next tick.
                if (placing != null) positionOverlay.refreshSize()
            },
            enabled = enabled,
        )
        SettingDivider()
        SettingActionRow(
            title = stringResource(R.string.settings_blindspot_position_title),
            description = stringResource(R.string.settings_blindspot_position_desc),
            buttonLabel = stringResource(
                if (placing == BlindSpotPositionOverlay.Side.RIGHT) R.string.settings_blindspot_position_done
                else R.string.settings_blindspot_position_button
            ),
            onClick = {
                val side = BlindSpotPositionOverlay.Side.RIGHT
                // Read before hide(): hiding clears [placing] through onHidden, and a second
                // tap on the same row must close the window, not reopen it.
                val closing = placing == side
                positionOverlay.hide()
                if (!closing && positionOverlay.show(context, side)) placing = side
            },
            enabled = enabled,
        )
        SettingDivider()
        SettingToggleRow(
            title = stringResource(R.string.settings_blindspot_both_main_title),
            traceId = "blindspot_both_main",
            description = stringResource(R.string.settings_blindspot_both_main_desc),
            checked = bothOnMain,
            onCheckedChange = {
                bothOnMain = it
                // The two screen opt-ins exclude each other (#240): one write, so the controller
                // never sees both on.
                if (it) bothOnCluster = false
                val editor = prefs.edit().putBoolean(BlindSpotPreferences.KEY_BOTH_ON_MAIN, it)
                if (it) editor.putBoolean(BlindSpotPreferences.KEY_BOTH_ON_CLUSTER, false)
                editor.apply()
                // The left window only exists on the main screen while this is on; its placement
                // row goes away with it, and an open drag overlay would have nothing to dismiss it.
                if (!it && placing == BlindSpotPositionOverlay.Side.LEFT) positionOverlay.hide()
            },
            enabled = enabled,
        )
        // Only when the left camera is actually shown on the main screen — there is nothing to
        // place otherwise (it lives on the cluster panel).
        if (bothOnMain) {
            SettingDivider()
            SettingActionRow(
                title = stringResource(R.string.settings_blindspot_left_position_title),
                description = stringResource(R.string.settings_blindspot_left_position_desc),
                buttonLabel = stringResource(
                    if (placing == BlindSpotPositionOverlay.Side.LEFT) R.string.settings_blindspot_position_done
                    else R.string.settings_blindspot_position_button
                ),
                onClick = {
                    val side = BlindSpotPositionOverlay.Side.LEFT
                    val closing = placing == side
                    positionOverlay.hide()
                    if (!closing && positionOverlay.show(context, side)) placing = side
                },
                enabled = enabled,
            )
        }
        if (hasClusterDisplay) {
            SettingDivider()
            SettingToggleRow(
                title = stringResource(R.string.settings_blindspot_both_cluster_title),
                traceId = "blindspot_both_cluster",
                description = stringResource(R.string.settings_blindspot_both_cluster_desc),
                checked = bothOnCluster,
                onCheckedChange = {
                    bothOnCluster = it
                    if (it) bothOnMain = false
                    val editor = prefs.edit().putBoolean(BlindSpotPreferences.KEY_BOTH_ON_CLUSTER, it)
                    if (it) editor.putBoolean(BlindSpotPreferences.KEY_BOTH_ON_MAIN, false)
                    editor.apply()
                    // Turning the main-screen opt-in off takes the left placement row with it.
                    if (it && placing == BlindSpotPositionOverlay.Side.LEFT) positionOverlay.hide()
                },
                enabled = enabled,
            )
        }
        SettingDivider()
        SettingToggleRow(
            title = stringResource(R.string.settings_blindspot_glow_title),
            traceId = "blindspot_glow",
            description = stringResource(R.string.settings_blindspot_glow_desc),
            checked = bsdGlow,
            onCheckedChange = {
                bsdGlow = it
                prefs.edit().putBoolean(BlindSpotPreferences.KEY_BSD_GLOW, it).apply()
            },
            enabled = enabled,
        )
        SettingHint(text = stringResource(R.string.settings_blindspot_hint))
    }
}

/** App label for the cluster picker row; falls back to the package name when not installed/resolvable. */
internal fun resolveAppLabel(context: Context, pkg: String): String =
    try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

/** Human label for a steering-wheel keycode: known name, else "Кнопка (код N)". */
@Composable
internal fun steeringButtonLabel(keyCode: Int): String {
    val res = com.bydmate.app.cluster.knownButtonNameRes(keyCode)
    return if (res != 0) stringResource(res)
    else stringResource(R.string.steering_button_unknown, keyCode)
}

/** What the «Режим вывода на приборку» row shows: a fact on a forced car (#194), else the chooser. */
internal sealed interface ProjectionModeRow {
    /** The transport the projection actually uses, for the hints under the row. */
    val effectiveDirect: Boolean

    data object OnlyExtended : ProjectionModeRow {
        override val effectiveDirect = true
    }

    data class Chooser(val directSelected: Boolean) : ProjectionModeRow {
        override val effectiveDirect: Boolean get() = directSelected
    }
}

internal fun projectionModeRow(directChosen: Boolean, directForced: Boolean): ProjectionModeRow =
    if (directForced) ProjectionModeRow.OnlyExtended else ProjectionModeRow.Chooser(directChosen)
