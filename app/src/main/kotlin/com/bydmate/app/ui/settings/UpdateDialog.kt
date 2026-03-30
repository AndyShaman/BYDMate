package com.bydmate.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.ui.theme.*

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val version: String, val notes: String) : UpdateState()
    data class Downloading(val progress: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

@Composable
fun UpdateDialog(
    currentVersion: String,
    state: UpdateState,
    onCheck: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .heightIn(max = 400.dp)
                    .clickable { /* absorb */ }
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Обновления", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Текущая версия: v$currentVersion", color = TextSecondary, fontSize = 14.sp)

                    Spacer(modifier = Modifier.height(4.dp))

                    when (state) {
                        is UpdateState.Idle -> {
                            Button(
                                onClick = onCheck,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                            ) {
                                Text("Проверить обновления", color = Color.White)
                            }
                        }
                        is UpdateState.Checking -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = AccentBlue, modifier = Modifier.padding(8.dp))
                                Text("Проверка...", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                        is UpdateState.UpToDate -> {
                            Text("✓ Установлена последняя версия", color = AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        is UpdateState.Available -> {
                            Text("Доступна версия: v${state.version}", color = AccentGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (state.notes.isNotBlank()) {
                                Text("Что нового:", color = TextSecondary, fontSize = 13.sp)
                                Text(state.notes, color = TextPrimary, fontSize = 13.sp)
                            }
                            Button(
                                onClick = onCheck,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                            ) {
                                Text("Скачать и установить", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        is UpdateState.Downloading -> {
                            Text(state.progress, color = AccentBlue, fontSize = 14.sp)
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = AccentBlue,
                                trackColor = CardSurfaceElevated
                            )
                        }
                        is UpdateState.Error -> {
                            Text("Ошибка: ${state.message}", color = SocRed, fontSize = 13.sp)
                            Button(
                                onClick = onCheck,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                            ) {
                                Text("Попробовать снова", color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Закрыть", color = TextSecondary)
                    }
                }
            }
        }
    }
}
