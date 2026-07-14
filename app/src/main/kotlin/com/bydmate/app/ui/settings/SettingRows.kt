package com.bydmate.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import com.bydmate.app.ui.components.bydSwitchColors
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/** Button styles for [SettingActionRow]. */
enum class SettingButtonStyle { Primary, Secondary, Warning }

/** Title (14sp Medium) + optional description (12sp) column shared by all rows. */
@Composable
private fun RowLabel(
    title: String,
    description: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(end = 12.dp)) {
        Text(
            text = title,
            color = if (enabled) TextPrimary else TextMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        if (description != null) {
            Text(
                text = description,
                color = if (enabled) TextSecondary else TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Row "title + hint + Switch". The whole row toggles. */
@Composable
fun SettingToggleRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(title, description, enabled, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = bydSwitchColors(),
        )
    }
}

/** Row "title + hint + slider with value label on the right". */
@Composable
fun SettingSliderRow(
    title: String,
    description: String? = null,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(title, description, enabled, Modifier.weight(1f))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = AccentGreen,
                activeTrackColor = AccentGreen,
                inactiveTrackColor = CardBorder,
            ),
            modifier = Modifier.width(280.dp),
        )
        Text(
            text = valueLabel,
            color = if (enabled) AccentGreen else TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}

/** Row "title + hint + one or two buttons on the right". */
@Composable
fun SettingActionRow(
    title: String,
    description: String? = null,
    buttonLabel: String,
    onClick: () -> Unit,
    style: SettingButtonStyle = SettingButtonStyle.Secondary,
    enabled: Boolean = true,
    secondButtonLabel: String? = null,
    onSecondClick: (() -> Unit)? = null,
    secondButtonEnabled: Boolean = enabled,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(title, description, enabled, Modifier.weight(1f))
        if (secondButtonLabel != null && onSecondClick != null) {
            SettingRowButton(secondButtonLabel, onSecondClick, SettingButtonStyle.Secondary, secondButtonEnabled)
            Spacer(modifier = Modifier.width(8.dp))
        }
        SettingRowButton(buttonLabel, onClick, style, enabled)
    }
}

@Composable
private fun SettingRowButton(
    label: String,
    onClick: () -> Unit,
    style: SettingButtonStyle,
    enabled: Boolean,
) {
    when (style) {
        SettingButtonStyle.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentGreen,
                contentColor = NavyDark,
            ),
        ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        SettingButtonStyle.Secondary -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CardSurfaceElevated,
                contentColor = TextPrimary,
            ),
            border = BorderStroke(1.dp, CardBorder),
        ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        SettingButtonStyle.Warning -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentOrange),
            border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.45f)),
        ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Row "title + hint + current value with chevron", opens a dialog/picker. */
@Composable
fun SettingValueRow(
    title: String,
    description: String? = null,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(title, description, enabled, Modifier.weight(1f))
        Text(
            text = value,
            color = if (enabled) AccentGreen else TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "›",
            color = TextMuted,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Row "title + hint + UnitChip group on the right" (scrolls horizontally if long). */
@Composable
fun SettingChipRow(
    title: String,
    description: String? = null,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(title, description, enabled, Modifier.weight(1f))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEachIndexed { index, label ->
                UnitChip(
                    label = label,
                    selected = index == selectedIndex,
                    onClick = { if (enabled) onSelect(index) },
                )
            }
        }
    }
}

/** Row with a green/orange status dot + optional button on the right. */
@Composable
fun SettingStatusRow(
    title: String,
    description: String? = null,
    ok: Boolean,
    buttonLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (ok) AccentGreen else AccentOrange, CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        RowLabel(title, description, enabled = true, Modifier.weight(1f))
        if (buttonLabel != null && onClick != null) {
            SettingRowButton(buttonLabel, onClick, SettingButtonStyle.Secondary, enabled = true)
        }
    }
}

/** Muted multi-line hint inside a card (replaces ad-hoc Text hints). */
@Composable
fun SettingHint(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

/** Thin divider between rows inside a settings card. */
@Composable
fun SettingDivider() {
    HorizontalDivider(color = CardBorder.copy(alpha = 0.55f), thickness = 1.dp)
}

@Composable
internal fun UnitChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Color.White,
            containerColor = CardSurfaceElevated,
            labelColor = TextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            enabled = true,
            selected = selected
        )
    )
}
