package com.bydmate.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

@Composable
fun PlaceEditDialog(
    initial: PlaceEntity?,
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, lat: Double, lon: Double, radiusM: Int) -> Unit
) {
    var nameText by remember { mutableStateOf(initial?.name ?: "") }
    var latText by remember { mutableStateOf(if (initial != null) initial.lat.toString() else "") }
    var lonText by remember { mutableStateOf(if (initial != null) initial.lon.toString() else "") }
    var radiusText by remember { mutableStateOf(initial?.radiusM?.toString() ?: "50") }

    // Validation
    val nameValid = nameText.trim().isNotBlank() && nameText.trim().length <= 40
    val latValue = latText.toDoubleOrNull()
    val latValid = latValue != null && latValue in -90.0..90.0
    val lonValue = lonText.toDoubleOrNull()
    val lonValid = lonValue != null && lonValue in -180.0..180.0
    val radiusValid = radiusText.toIntOrNull() != null
    val canSave = nameValid && latValid && lonValid && radiusValid

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentGreen,
        unfocusedBorderColor = CardBorder,
        focusedLabelColor = AccentGreen,
        unfocusedLabelColor = TextSecondary,
        cursorColor = AccentGreen
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                text = if (initial == null) "Новое место" else "Редактировать место",
                color = TextPrimary,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Name field
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { if (it.length <= 40) nameText = it },
                    label = { Text("Название") },
                    singleLine = true,
                    isError = nameText.isNotEmpty() && !nameValid,
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors
                )

                // Latitude field
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Широта") },
                    singleLine = true,
                    isError = latText.isNotEmpty() && !latValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors
                )

                // Longitude field
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("Долгота") },
                    singleLine = true,
                    isError = lonText.isNotEmpty() && !lonValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors
                )

                Text(
                    text = "Позже можно будет выбрать на карте",
                    color = TextMuted,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(0.dp))

                // Radius field
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it },
                    label = { Text("Радиус, м") },
                    singleLine = true,
                    isError = radiusText.isNotEmpty() && !radiusValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(8.dp),
                    colors = fieldColors
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) {
                        val radius = radiusText.toInt().coerceIn(20, 500)
                        onSave(initial?.id, nameText.trim(), latValue!!, lonValue!!, radius)
                    }
                },
                enabled = canSave
            ) {
                Text("Сохранить", color = if (canSave) AccentGreen else TextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextSecondary)
            }
        }
    )
}
