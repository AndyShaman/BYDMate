package com.bydmate.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.ScaledDialogContent

// ============================================================================
// Pop-up dialog for card details
// ============================================================================

@Composable
internal fun CardDetailDialog(
    title: String? = null,
    borderColor: Color,
    onDismiss: () -> Unit,
    // False keeps the card open on taps inside it (e.g. reading instructions); outside still dismisses.
    dismissOnCardTap: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ScaledDialogContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { onDismiss() },
                contentAlignment = Alignment.CenterStart
            ) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                    border = androidx.compose.foundation.BorderStroke(2.dp, borderColor.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .padding(start = 22.dp, end = 16.dp)
                        .fillMaxWidth(0.4f)
                        .then(if (dismissOnCardTap) Modifier.clickable { onDismiss() } else Modifier)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (title != null) {
                            Text(title, color = borderColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        content()
                    }
                }
            }
        }
    }
}
