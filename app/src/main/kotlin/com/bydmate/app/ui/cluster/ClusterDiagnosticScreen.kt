package com.bydmate.app.ui.cluster

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterDiagnosticScreen(
    onBack: () -> Unit,
    viewModel: ClusterDiagnosticViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val captured by viewModel.capturedKeyEvent.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика приборки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Дисплеи и режим приборки", fontSize = 16.sp)
                    Button(onClick = { viewModel.refresh() }) { Text("Обновить") }
                    if (state.loading) Text("Чтение…")
                    state.displaysError?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
                    state.displays.forEach { d ->
                        Text(
                            "id=${d.id}  ${d.name}  ${d.width}x${d.height}  dpi=${d.densityDpi}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }
                    if (state.featuresProbed) {
                        Text("Feature ID:", fontSize = 14.sp)
                        state.featureReadings.forEach { (id, value) ->
                            Text(
                                "$id = ${value ?: "нет данных"}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Кнопка руля", fontSize = 16.sp)
                    Text(
                        "Сервис: ${if (viewModel.isServiceConnected) "RUNNING" else "выключен"}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                    Button(onClick = {
                        // DiLink has no Accessibility settings activity — startActivity would crash.
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                "На DiLink нет экрана Спецвозможностей. Сервис включается через ADB (Phase 0).",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }) { Text("Открыть Спецвозможности") }

                    val cap = captured
                    Text(
                        if (cap == null) "Последнее событие: —"
                        else "keycode=${cap.keyCode}  action=${cap.action}  long=${cap.isLongPress}  repeat=${cap.repeatCount}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                    Text(
                        "Лев. звезда: 305 / удержание 306 · Прав.: 351 / 352 · Карусель: 309",
                        fontSize = 12.sp,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Поглощать (return true)", modifier = Modifier.weight(1f))
                        var consume by remember { mutableStateOf(viewModel.consumeEvents) }
                        Switch(checked = consume, onCheckedChange = {
                            consume = it
                            viewModel.consumeEvents = it
                        })
                    }
                    Text(
                        "Удержи кнопку при включённом тумблере — проверь, открывается ли нативное меню.",
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
