package com.bydmate.app.ui.automation

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.minimumInteractiveComponentSize
import kotlinx.coroutines.delay
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WifiTethering
import com.bydmate.app.util.appLocalizedContext
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.LaunchedEffect
import com.bydmate.app.data.telegram.TELEGRAM_REPORT_KIND
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.*
import java.text.NumberFormat

// --- Action Row ---

/**
 * One action: «Выполнить сейчас» (green triangle in a light circle) at the left before the
 * action, the action's own controls across the row, then the arrows and the orange bin at the
 * far end. Every button is 48x48dp.
 */
@Composable
internal fun ActionRow(
    index: Int,
    action: ActionDef,
    places: List<PlaceEntity>,
    tgBotConnected: Boolean,
    highlight: Boolean,
    showArrows: Boolean,
    onUpdate: (ActionDef) -> Unit,
    onTest: (ActionDef) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CardSurface, FIELD_SHAPE)
            .border(
                if (highlight) 1.5.dp else 1.dp,
                if (highlight) AccentOrange else AccentTeal.copy(alpha = 0.2f),
                FIELD_SHAPE,
            )
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.width(24.dp).height(MIN_TOUCH), contentAlignment = Alignment.CenterStart) {
            Text("${index + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
        }

        // "Выполнить сейчас" — fire this vehicle action immediately for live testing.
        // Param actions send their command; toggle actions go through the dispatcher so the
        // target is flipped from the live state. Result is shown via Toast + logcat line.
        val testable = (action.kind == "param" && action.command.isNotBlank()) ||
            (action.kind == "toggle" && !action.payload.isNullOrBlank())
        if (testable) {
            IconButton(onClick = { onTest(action) }, modifier = Modifier.size(MIN_TOUCH)) {
                Box(
                    Modifier.size(36.dp).background(AccentGreen.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PlayArrow, stringResource(R.string.auto_a11y_run_now), tint = AccentGreen, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
        }

        Box(Modifier.weight(1f).heightIn(min = MIN_TOUCH), contentAlignment = Alignment.CenterStart) {
            val fill = Modifier.fillMaxWidth()
            when (action.kind) {
                "notification", "notification_silent", "notification_sound" ->
                    NotificationActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "app_launch" ->
                    AppLaunchActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "app_close" ->
                    AppCloseActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "media_key" ->
                    MediaKeyActionControls(payload = action.payload, modifier = fill)
                "call" ->
                    CallActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "navigate" ->
                    NavigateActionControls(action = action, places = places, onUpdate = onUpdate, modifier = fill)
                "url" ->
                    UrlActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "yandex_music" ->
                    YandexMusicActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "delay" ->
                    DelayActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "media_volume" ->
                    MediaVolumeActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "sentry" ->
                    SentryActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "hotspot" ->
                    HotspotActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "screen" ->
                    ScreenActionControls(payload = action.payload, modifier = fill)
                "cluster_projection" ->
                    ClusterActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                // A toggle on sentry or the cluster goes back to its own three-state row, so the
                // user can return to Вкл/Выкл; every other target is edited by the target dropdown.
                "toggle" -> when (toggleRowSpecFor(action)) {
                    SENTRY_ROW ->
                        SentryActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                    CLUSTER_ROW ->
                        ClusterActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                    else ->
                        ToggleActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                }
                "speak" ->
                    SpeakActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "agent_query" ->
                    AgentQueryActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "split_screen" ->
                    SplitScreenActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                "split_screen_close", "split_screen_toggle" ->
                    SplitScreenStateActionControls(kind = action.kind, modifier = fill)
                TELEGRAM_REPORT_KIND ->
                    TelegramReportActionControls(
                        action = action, tgBotConnected = tgBotConnected, onUpdate = onUpdate, modifier = fill,
                    )
                else -> { // "param" (default); a graded command is edited as its level row
                    val level = LevelFamily.of(action.command)
                    if (level != null) LevelActionControls(action = action, level = level, onUpdate = onUpdate, modifier = fill)
                    else ParamActionControls(action = action, onUpdate = onUpdate, modifier = fill)
                }
            }
        }

        Spacer(Modifier.width(4.dp))
        Row(Modifier.height(MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
            RowButtons(showArrows, onMoveUp, onMoveDown, onDelete)
        }
    }
}

@Composable
private fun ParamActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lc = context.appLocalizedContext()
    val rearSeats = LocalRearSeats.current
    val entries = remember(lc, rearSeats) { catalogEntries(lc, rearSeats) }
    CatalogDropdown(
        selected = paramSelectedText(action, context),
        items = entries.map { it.label },
        categories = entries.map { lc.getString(it.categoryRes) },
        modifier = modifier,
        onSelect = { idx -> onUpdate(entries[idx].make(context)) }
    )
}

/** A graded command («Температура: 22 °C»): the catalog dropdown and its level controls in one line. */
@Composable
private fun LevelActionControls(
    action: ActionDef,
    level: LevelValue,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val lc = LocalContext.current.appLocalizedContext()
    val family = level.family
    LevelRow(
        icon = family.icon,
        value = level.value,
        range = family.range,
        step = family.step,
        valueText = family.valueText(level.value, lc),
        onValue = { v -> onUpdate(action.copy(command = family.command(v), displayName = family.displayName(v, lc))) },
        modifier = modifier,
    ) {
        ParamActionControls(action = action, onUpdate = onUpdate, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * One graded value in one line: icon, [header] (name or dropdown), minus, a slider with a tick
 * per step, plus and the value. Minus and plus are 48 dp and stop at the ends of [range]; the
 * value keeps a fixed minimum width so the slider does not jump when «выкл» becomes «3».
 */
@Composable
private fun LevelRow(
    icon: ImageVector,
    value: Int,
    range: IntRange,
    step: Int,
    valueText: String,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    Row(
        modifier.heightIn(min = MIN_TOUCH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { header() }
        LevelStepButton(Icons.Outlined.Remove, stringResource(R.string.auto_a11y_level_down), value > range.first) {
            onValue(levelStep(value, range, step, up = false))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> levelSnap(v, range, step).let { if (it != value) onValue(it) } },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            modifier = Modifier.weight(LEVEL_SLIDER_WEIGHT),
        )
        LevelStepButton(Icons.Outlined.Add, stringResource(R.string.auto_a11y_level_up), value < range.last) {
            onValue(levelStep(value, range, step, up = true))
        }
        Text(
            valueText,
            fontSize = EDITOR_TEXT,
            color = AccentTeal,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 64.dp),
        )
    }
}

/** The slider's share next to the name, so a long name does not squeeze it away. */
private const val LEVEL_SLIDER_WEIGHT = 1.2f

@Composable
private fun LevelStepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(MIN_TOUCH)
            .clip(FIELD_SHAPE)
            .background(CardSurfaceElevated)
            .border(1.5.dp, CardBorder, FIELD_SHAPE)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) AccentGreen else TextMuted, modifier = Modifier.size(24.dp))
    }
}

// Delay options, in ms
internal val DELAY_OPTION_MS = listOf(500L, 1000L, 2000L, 3000L, 5000L, 10000L, 30000L, 60000L)

internal fun delayAction(ms: Long, context: Context): ActionDef {
    val lc = context.appLocalizedContext()
    return ActionDef(
        command = "delay_$ms",
        displayName = lc.getString(R.string.auto_ui_act_wait, durationText(ms, lc)),
        kind = "delay",
        payload = ms.toString(),
    )
}

/**
 * «Подождать [5] [мин]»: the wait between two actions, a number plus its unit, up to 24 h.
 * An invalid number clears the payload, so the validator blocks saving and names the action.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DelayActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locale = context.appLocalizedContext().resources.configuration.locales[0]
    fun numberText(ms: Long): Pair<String, DelayUnit> {
        val (value, unit) = splitDelay(ms)
        val fmt = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3; isGroupingUsed = false }
        return fmt.format(value) to unit
    }
    val payloadMs = action.payload?.toLongOrNull()
    val initial = payloadMs?.let { numberText(it) }
    // The typed text lives here: our own update must not rewrite «1,» into «1».
    var text by remember { mutableStateOf(initial?.first.orEmpty()) }
    var unit by remember { mutableStateOf(initial?.second ?: DelayUnit.SECONDS) }
    LaunchedEffect(action.payload) {
        // A payload set from outside (a preset tile, another rule, a row moved into this unkeyed
        // slot) replaces the typed text; an empty one clears a number that is not this action's.
        when {
            payloadMs != null && payloadMs != parseDelayMs(text, unit) ->
                numberText(payloadMs).let { (t, u) -> text = t; unit = u }
            payloadMs == null && parseDelayMs(text, unit) != null -> text = ""
        }
    }
    fun push(newText: String, newUnit: DelayUnit) {
        text = newText
        unit = newUnit
        val ms = parseDelayMs(newText, newUnit)
        onUpdate(if (ms != null) delayAction(ms, context) else action.copy(payload = ""))
    }
    val units = DelayUnit.entries
    val unitLabels = listOf(R.string.auto_ui_unit_seconds, R.string.auto_ui_unit_minutes, R.string.auto_ui_unit_hours)
        .map { stringResource(it) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterVertically)) {
            Icon(Icons.Outlined.HourglassEmpty, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.auto_ui_wait), fontSize = EDITOR_TEXT, color = TextPrimary)
        }
        EditorField(
            value = text,
            onValueChange = { push(it, unit) },
            highlight = parseDelayMs(text, unit) == null,
            keyboardType = KeyboardType.Decimal,
            centered = true,
            modifier = Modifier.width(96.dp).align(Alignment.CenterVertically),
        )
        DropdownField(
            text = unitLabels[units.indexOf(unit)],
            items = unitLabels,
            onSelect = { i -> push(text, units[i]) },
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun MediaVolumeActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Max volume is device-dependent; read it once from the head unit's AudioManager.
    val maxVolume = remember {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
    }
    val current = (action.payload?.toIntOrNull() ?: 2).coerceIn(0, maxVolume)
    val label = stringResource(R.string.automation_action_media_volume_label)
    val namePrefix = stringResource(R.string.automation_action_media_volume)

    LevelRow(
        icon = Icons.Outlined.VolumeUp,
        value = current,
        range = 0..maxVolume,
        step = 1,
        valueText = "$current/$maxVolume",
        onValue = { lvl -> onUpdate(action.copy(payload = lvl.toString(), displayName = "$namePrefix: $lvl")) },
        modifier = modifier,
    ) {
        Text(label, fontSize = EDITOR_TEXT, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --- Sentry Action Controls ---

@Composable
private fun SentryActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    OnOffToggleControls(
        action = action,
        icon = Icons.Outlined.Shield,
        displayLabel = stringResource(R.string.automation_action_sentry),
        onLabel = stringResource(R.string.automation_action_sentry_on),
        offLabel = stringResource(R.string.automation_action_sentry_off),
        spec = SENTRY_ROW,
        context = context,
        onUpdate = onUpdate,
        modifier = modifier,
    )
}

/**
 * On / off / «переключить» for a kind that has its own control row and a state the car can be
 * asked for: the name on top, the three buttons below. The first two store the row's own kind
 * with payload "1"/"0" — also when the row currently holds a toggle, so «Переключить» is never a
 * one-way door; the third stores the very same `toggle` action the catalog entries store, so one
 * target has one storage shape.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OnOffToggleControls(
    action: ActionDef,
    icon: ImageVector,
    displayLabel: String,
    onLabel: String,
    offLabel: String,
    spec: ToggleRowSpec,
    context: Context,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggleLabel = stringResource(R.string.automation_action_toggle_short)
    val isToggle = action.kind == "toggle"
    Column(modifier = modifier) {
        Row(Modifier.heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = AccentTeal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(displayLabel, fontSize = EDITOR_TEXT, color = TextPrimary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateChip(onLabel, selected = !isToggle && action.payload == "1") {
                onUpdate(rowStateAction(spec, "1", "$displayLabel: $onLabel"))
            }
            StateChip(offLabel, selected = !isToggle && action.payload == "0") {
                onUpdate(rowStateAction(spec, "0", "$displayLabel: $offLabel"))
            }
            StateChip(toggleLabel, selected = isToggle) {
                onUpdate(
                    ActionDef(
                        command = "",
                        displayName = toggleDisplayName(context, spec.toggleTarget),
                        kind = "toggle",
                        payload = spec.toggleTarget,
                    )
                )
            }
        }
    }
}

/** «Включить / Отключить / Переключить»: green outline when chosen, 40dp to see, 48dp to touch. */
@Composable
private fun StateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = FIELD_HEIGHT)
            .clip(FIELD_SHAPE)
            .background(if (selected) AccentGreen.copy(alpha = 0.12f) else CardSurface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) AccentGreen else CardBorder, FIELD_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = EDITOR_TEXT, fontWeight = FontWeight.SemiBold,
            color = if (selected) AccentGreen else TextSecondary,
        )
    }
}

// --- Hotspot Action Controls ---

@Composable
private fun HotspotActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val isEnabled = action.payload == "1"
    val onLabel = stringResource(R.string.automation_action_hotspot_on)
    val offLabel = stringResource(R.string.automation_action_hotspot_off)
    val displayLabel = stringResource(R.string.automation_action_hotspot)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.WifiTethering, null, tint = AccentTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(displayLabel, fontSize = EDITOR_TEXT, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            if (isEnabled) onLabel else offLabel,
            fontSize = EDITOR_TEXT,
            color = if (isEnabled) AccentGreen else TextSecondary
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = isEnabled,
            onCheckedChange = { checked ->
                val payload = if (checked) "1" else "0"
                val name = if (checked) onLabel else offLabel
                onUpdate(action.copy(payload = payload, displayName = "$displayLabel: $name"))
            },
            colors = bydSwitchColors()
        )
    }
}

// --- Screen Action Controls (#275) ---

/** «Экран: выключить / включить»: nothing to configure, the row only names the step. */
@Composable
private fun ScreenActionControls(payload: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(CardSurface, RoundedCornerShape(6.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
            .heightIn(min = FIELD_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Tune, null, tint = AccentTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (payload == "1") R.string.automation_action_screen_on else R.string.automation_action_screen_off
            ),
            fontSize = 16.sp,
            color = TextPrimary,
            maxLines = 1,
        )
    }
}

// --- Cluster Projection Action Controls ---

@Composable
private fun ClusterActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    OnOffToggleControls(
        action = action,
        icon = Icons.Outlined.Speed,
        displayLabel = stringResource(R.string.automation_action_cluster_projection),
        onLabel = stringResource(R.string.automation_action_cluster_projection_on),
        offLabel = stringResource(R.string.automation_action_cluster_projection_off),
        spec = CLUSTER_ROW,
        context = context,
        onUpdate = onUpdate,
        modifier = modifier,
    )
}

// --- Toggle Action Controls ---

/**
 * Row for the "toggle" action: one dropdown picking what gets flipped, «Передний багажник,
 * переключить». The label comes from the target id, so it follows an in-app language switch;
 * displayName is rewritten on pick because that is what the journal and the dump show.
 */
@Composable
private fun ToggleActionControls(
    action: ActionDef,
    onUpdate: (ActionDef) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val current = action.payload ?: ActionDispatcher.TOGGLE_TRUNK
    val targetNames = ActionDispatcher.TOGGLE_TARGETS.map { target ->
        stringResource(
            R.string.auto_ui_act_toggle,
            stringResource(ActionDispatcher.toggleTargetNameRes(target) ?: R.string.automation_action_toggle),
        )
    }
    DropdownField(
        text = targetNames.getOrNull(ActionDispatcher.TOGGLE_TARGETS.indexOf(current)) ?: current,
        fillWidth = true,
        items = targetNames,
        onSelect = { i ->
            val target = ActionDispatcher.TOGGLE_TARGETS[i]
            onUpdate(action.copy(payload = target, displayName = toggleDisplayName(context, target)))
        },
        modifier = modifier,
    )
}
