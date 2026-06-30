package com.bydmate.app.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurfaceElevated

/**
 * Pure window geometry for the expandable button overlay. Android-free so it can
 * be unit-tested without instrumentation. All pixel values are post-scale device
 * pixels; dp constants are the logical design values.
 */
object WidgetButtonLayout {
    const val PANEL_WIDTH_DP = 260
    const val PANEL_HEIGHT_DP = 108
    const val BUTTON_DP = 50
    const val GAP_DP = 12

    data class WindowBox(val x: Int, val y: Int, val width: Int, val height: Int)

    /** One pocket = one button plus the gap between button and panel edge. */
    fun pocketPx(buttonPx: Int, gapPx: Int): Int = buttonPx + gapPx

    /**
     * Returns the window box when the panel is expanded. The window grows by one
     * pocket downward only (a single bottom row of buttons); its width and its
     * top-left x stay fixed, so the panel never shifts horizontally when the
     * buttons slide out. The result is clamped to the screen so no button is cut
     * off near the bottom edge.
     *
     * @param collapsedX  current window x (px) while collapsed
     * @param collapsedY  current window y (px) while collapsed
     * @param panelWpx    collapsed panel width in pixels
     * @param panelHpx    collapsed panel height in pixels
     * @param buttonPx    button size in pixels
     * @param gapPx       gap between button row and panel edge in pixels
     * @param screenW     screen width in pixels
     * @param screenH     screen height in pixels
     */
    fun expandedWindow(
        collapsedX: Int,
        collapsedY: Int,
        panelWpx: Int,
        panelHpx: Int,
        buttonPx: Int,
        gapPx: Int,
        screenW: Int,
        screenH: Int,
    ): WindowBox {
        val pocket = pocketPx(buttonPx, gapPx)
        val width = panelWpx
        val height = panelHpx + pocket
        val (cx, cy) = DragGestureLogic.clampToScreen(
            x = collapsedX,
            y = collapsedY,
            widgetWidth = width,
            widgetHeight = height,
            screenWidth = screenW,
            screenHeight = screenH,
        )
        return WindowBox(x = cx, y = cy, width = width, height = height)
    }
}

/**
 * The 4-button overlay layer that fills the expanded window.
 *
 * Layout: buttons 1–4 form a single bottom row spanning the panel width
 * (space-between), sitting in the pocket below the panel.
 *
 * Each button is tucked up behind the panel's bottom edge while hidden and slides
 * down into the pocket as it fades in when [expanded] becomes true. Stagger index
 * staggers the tween so buttons pop in sequence.
 */
@Composable
fun WidgetButtonPanel(
    expanded: Boolean,
    scaleFactor: Float,
    onButtonClick: (Int) -> Unit,
) {
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density * scaleFactor,
        fontScale = baseDensity.fontScale,
    )

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        val button = WidgetButtonLayout.BUTTON_DP.dp
        val gap = WidgetButtonLayout.GAP_DP.dp
        val pocket = button + gap
        val panelW = WidgetButtonLayout.PANEL_WIDTH_DP.dp
        val panelH = WidgetButtonLayout.PANEL_HEIGHT_DP.dp

        Box(modifier = Modifier.size(width = panelW, height = panelH + pocket)) {
            // Bottom row (buttons 1–4): spans the panel width in the pocket below.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .width(panelW)
                    .height(button),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ButtonCell(number = 1, expanded = expanded, staggerIndex = 0, onClick = onButtonClick)
                ButtonCell(number = 2, expanded = expanded, staggerIndex = 1, onClick = onButtonClick)
                ButtonCell(number = 3, expanded = expanded, staggerIndex = 2, onClick = onButtonClick)
                ButtonCell(number = 4, expanded = expanded, staggerIndex = 3, onClick = onButtonClick)
            }
        }
    }
}

/**
 * A single tappable button cell. It is tucked up behind the panel's bottom edge
 * while hidden (offset up, alpha 0) and slides down into the pocket as it fades in.
 * [staggerIndex] delays the animation start so buttons fan out in turn.
 */
@Composable
private fun ButtonCell(
    number: Int,
    expanded: Boolean,
    staggerIndex: Int,
    onClick: (Int) -> Unit,
) {
    // progress: 0 = hidden behind panel edge, 1 = fully visible in position.
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 240, delayMillis = staggerIndex * 40),
        label = "buttonSlide$number",
    )
    val slidePx = WidgetButtonLayout.BUTTON_DP
    // Displaced up behind the panel when hidden, slides down into the pocket.
    val dy = ((1f - progress) * slidePx).dp

    Box(
        modifier = Modifier
            .size(WidgetButtonLayout.BUTTON_DP.dp)
            .offset(y = -dy)
            .alpha(progress)
            .background(CardSurfaceElevated, RoundedCornerShape(12.dp))
            .border(1.5.dp, CardBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = expanded) { onClick(number) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            color = AccentGreen,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}
