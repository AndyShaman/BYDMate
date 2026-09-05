package com.bydmate.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextSecondary

/** «?» toggle for the inline hint. Its own clickable consumes the tap, so it does
 *  not bubble to a clickable card body underneath. */
@Composable
fun HelpIcon(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(18.dp)
            .border(1.5.dp, TextMuted, CircleShape)
            .clickable(indication = null, interactionSource = source, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("?", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HintBlock(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceElevated, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}
