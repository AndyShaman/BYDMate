package com.bydmate.app.ui.tech

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

/** Height of the car silhouette; its viewport is 160 x 260, so 1 viewport unit = 0.5 dp. */
private val CAR_HEIGHT = 130.dp
private const val CAR_VIEWPORT_W = 160f
private const val CAR_VIEWPORT_H = 260f

/** Wheel rectangles in viewport units (x, top, bottom), front then rear axle; width 22, rx 5. */
private const val WHEEL_W = 22f
private const val WHEEL_RX = 5f
private const val FRONT_TOP = 42f
private const val FRONT_BOTTOM = 90f
private const val REAR_TOP = 170f
private const val REAR_BOTTOM = 220f

/** Leader lines, viewport units: from the wheel side outwards at the axle's height. */
private const val FRONT_LEAD_Y = 66f
private const val REAR_LEAD_Y = 195f
private const val LEAD_REACH = 36f

/** Distance from the car's centre line to the inner edge of each tile. */
private val TILE_GAP = 62.dp

/**
 * Top view of the car (mock car B): a line runs from each wheel to its tile, so it is plain
 * which wheel each pressure belongs to. Tiles size to their text, so both lines show at any font
 * scale; the block grows when the tiles outgrow the car.
 */
@Composable
internal fun TyreTopView(state: TechPanelUiState) {
    val deviates = TechPanelVisuals.tyreDeviates(
        listOf(state.tirePressFL, state.tirePressFR, state.tirePressRL, state.tirePressRR)
    )
    Layout(
        content = {
            CarTopImage(deviates)
            TyreTile(state.tirePressFL, state.tyreTempFL, deviates[0])
            TyreTile(state.tirePressFR, state.tyreTempFR, deviates[1])
            TyreTile(state.tirePressRL, state.tyreTempRL, deviates[2])
            TyreTile(state.tirePressRR, state.tyreTempRR, deviates[3])
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val car = placeables[0]
        val tiles = placeables.drop(1)
        val k = car.height / CAR_VIEWPORT_H
        val frontY = FRONT_LEAD_Y * k
        val rearY = REAR_LEAD_Y * k
        // Tall tiles (font 1,3) may reach past the car: move the car down and grow instead of clipping.
        val top = maxOf(0f, tiles[0].height / 2f - frontY, tiles[1].height / 2f - frontY)
        val height = maxOf(
            car.height + top,
            rearY + top + tiles[2].height / 2f,
            rearY + top + tiles[3].height / 2f,
        )
        val centre = constraints.maxWidth / 2
        val gap = TILE_GAP.roundToPx()
        layout(constraints.maxWidth, height.roundToInt()) {
            fun y(axle: Float, h: Int) = (axle + top - h / 2f).roundToInt()
            car.place(centre - car.width / 2, top.roundToInt())
            tiles[0].place(centre - gap - tiles[0].width, y(frontY, tiles[0].height))
            tiles[1].place(centre + gap, y(frontY, tiles[1].height))
            tiles[2].place(centre - gap - tiles[2].width, y(rearY, tiles[2].height))
            tiles[3].place(centre + gap, y(rearY, tiles[3].height))
        }
    }
}

/** The silhouette with the leader lines under it and the wheels over it, wheels in the tile colour. */
@Composable
private fun CarTopImage(deviates: List<Boolean>) {
    Image(
        painter = painterResource(R.drawable.ic_tech_car_top),
        contentDescription = null,
        modifier = Modifier
            .size(width = CAR_HEIGHT * (CAR_VIEWPORT_W / CAR_VIEWPORT_H), height = CAR_HEIGHT)
            .drawWithContent {
                val k = size.height / CAR_VIEWPORT_H
                val line = 2f * k
                for (y in listOf(FRONT_LEAD_Y, REAR_LEAD_Y)) {
                    drawLine(CardBorder, Offset(0f, y * k), Offset(-LEAD_REACH * k, y * k), line)
                    drawLine(CardBorder, Offset(size.width, y * k), Offset(size.width + LEAD_REACH * k, y * k), line)
                }
                drawContent()
                val wheels = listOf(
                    0f to (FRONT_TOP to FRONT_BOTTOM),
                    CAR_VIEWPORT_W - WHEEL_W to (FRONT_TOP to FRONT_BOTTOM),
                    0f to (REAR_TOP to REAR_BOTTOM),
                    CAR_VIEWPORT_W - WHEEL_W to (REAR_TOP to REAR_BOTTOM),
                )
                wheels.forEachIndexed { i, (x, ys) ->
                    val topLeft = Offset(x * k, ys.first * k)
                    val wheel = Size(WHEEL_W * k, (ys.second - ys.first) * k)
                    val radius = CornerRadius(WHEEL_RX * k)
                    val (fill, stroke) = if (deviates[i]) SocYellow.copy(alpha = 0.45f) to SocYellow
                        else NavyDark to WHEEL_STROKE
                    drawRoundRect(fill, topLeft, wheel, radius)
                    drawRoundRect(stroke, topLeft, wheel, radius, style = Stroke(line))
                }
            }
    )
}

private val WHEEL_STROKE = Color(0xFF3A4D6B)

@Composable
private fun TyreTile(pressureKpa: Int?, tempC: Int?, deviates: Boolean) {
    val bar = pressureKpa?.let { it / 100.0 }
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(CardSurfaceElevated)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(if (deviates) SocYellow else AccentGreen)
        )
        Column(modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp)) {
            Text(
                bar?.let { stringResource(R.string.tech_value_tyre_pressure, it) } ?: DASH,
                color = TextPrimary,
                fontSize = 13.sp,
                // Tight lines as in the mock: the theme's 22sp would push front and rear tiles
                // into each other at font scale 1,3.
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                tempC?.let { stringResource(R.string.tech_value_deg, it) } ?: DASH,
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}
