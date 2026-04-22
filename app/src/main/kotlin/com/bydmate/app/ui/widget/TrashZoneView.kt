package com.bydmate.app.ui.widget

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextMuted

/**
 * Drop target for hiding the floating widget. Renders as a circular halo
 * at bottom-center of the screen. Gets visibly bigger + solid when [active].
 */
@Composable
fun TrashZoneView(active: Boolean) {
    val haloAlpha by animateFloatAsState(targetValue = if (active) 0.35f else 0.18f, label = "halo-alpha")
    val innerSize by animateDpAsState(targetValue = if (active) 64.dp else 48.dp, label = "inner-size")
    val innerAlpha by animateFloatAsState(targetValue = if (active) 0.85f else 0.40f, label = "inner-alpha")

    Column(
        modifier = Modifier.padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(SocRed.copy(alpha = haloAlpha), SocRed.copy(alpha = 0f)),
                        radius = 96f,
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .background(SocRed.copy(alpha = if (active) 0.7f else 0.0f), CircleShape)
                    .border(1.5.dp, SocRed.copy(alpha = innerAlpha), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    fontSize = 22.sp,
                    color = SocRed.copy(alpha = if (active) 1f else 0.9f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (active) "отпустить — виджет скрыт" else "перетащить сюда, чтобы скрыть",
            fontSize = 11.sp,
            color = if (active) SocRed else TextMuted,
            fontFamily = FontFamily.Monospace,
        )
    }
}
