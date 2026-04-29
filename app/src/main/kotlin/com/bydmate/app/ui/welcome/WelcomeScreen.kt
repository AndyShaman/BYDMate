package com.bydmate.app.ui.welcome

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.theme.*

@Composable
fun WelcomeScreen(
    viewModel: WelcomeViewModel = hiltViewModel(),
    onComplete: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isComplete) {
        if (state.isComplete) onComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Welcome to BYDMate!",
            color = AccentGreen,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Шаг ${state.step} из 3",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        when (state.step) {
            1 -> ModelStep(state, viewModel)
            2 -> TariffStep(state, viewModel)
            3 -> AutoStartStep(state, viewModel)
        }
    }
}

@Composable
private fun ModelStep(state: WelcomeUiState, viewModel: WelcomeViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard("Какая у тебя модель BYD?") {
            Text(
                "От этого зависит источник данных о поездках.\nLeopard 3 хранит их в встроенной базе BYD (energydata).\nSong и другие модели — только в DiPlus.",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ModelButton(
                    label = "Leopard 3",
                    sublabel = "BYD energydata",
                    selected = state.dataSource == "ENERGYDATA",
                    onClick = { viewModel.setDataSource("ENERGYDATA") },
                    modifier = Modifier.weight(1f)
                )
                ModelButton(
                    label = "Song / другая",
                    sublabel = "DiPlus TripInfo",
                    selected = state.dataSource == "DIPLUS",
                    onClick = { viewModel.setDataSource("DIPLUS") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Можно поменять позже в настройках.",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { viewModel.nextStep() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Text("Далее →", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ModelButton(
    label: String,
    sublabel: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AccentGreen else CardSurfaceElevated
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                color = if (selected) Color.White else TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                sublabel,
                color = if (selected) Color.White else TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TariffStep(state: WelcomeUiState, viewModel: WelcomeViewModel) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // LEFT: Battery & Currency
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Батарея") {
                WelcomeTextField(
                    label = "Ёмкость батареи (кВт·ч)",
                    value = state.batteryCapacity,
                    onValueChange = { viewModel.setBatteryCapacity(it) }
                )
                Text(
                    "Leopard 3: 72.9 кВт·ч (BEV) или 31.8 кВт·ч (PHEV)",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            SectionCard("Валюта") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    SettingsRepository.CURRENCIES.forEach { currency ->
                        WelcomeChip(
                            label = "${currency.symbol} ${currency.label}",
                            selected = state.currency == currency.code,
                            onClick = { viewModel.setCurrency(currency.code) }
                        )
                    }
                }
            }
        }

        // RIGHT: Tariffs
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Тарифы") {
                WelcomeTextField(
                    label = "Домашняя зарядка (${state.currencySymbol}/кВт·ч)",
                    value = state.homeTariff,
                    onValueChange = { viewModel.setHomeTariff(it) }
                )
                WelcomeTextField(
                    label = "Быстрая зарядка DC (${state.currencySymbol}/кВт·ч)",
                    value = state.dcTariff,
                    onValueChange = { viewModel.setDcTariff(it) }
                )
            }

            SectionCard("Расчёт стоимости поездок") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WelcomeChip("Домашний", state.tripCostMode == "home") { viewModel.setTripCostMode("home") }
                    WelcomeChip("DC", state.tripCostMode == "dc") { viewModel.setTripCostMode("dc") }
                    WelcomeChip("Свой", state.tripCostMode == "custom") { viewModel.setTripCostMode("custom") }
                }
                if (state.tripCostMode == "custom") {
                    WelcomeTextField(
                        label = "Свой тариф (${state.currencySymbol}/кВт·ч)",
                        value = state.customTariff,
                        onValueChange = { viewModel.setCustomTariff(it) }
                    )
                }
                Text(
                    "Стоимость = потребление × тариф. Можно изменить позже в настройках.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { viewModel.nextStep() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("Далее →", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AutoStartStep(state: WelcomeUiState, viewModel: WelcomeViewModel) {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // LEFT: Instructions
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Автозапуск на DiLink") {
                Text(
                    "Чтобы BYDMate запускался автоматически при включении магнитолы:",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("1. Нажмите кнопку справа", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("2. Найдите BYDMate в списке", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("3. Переключатель должен быть ВЫКЛЮЧЕН (OFF)", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "OFF = автозапуск РАЗРЕШЁН. Это чёрный список: отключённые в нём приложения МОГУТ запускаться сами.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Text(
                    "⚠ После обновления APK проверьте эту настройку!",
                    color = SocYellow,
                    fontSize = 12.sp
                )
            }

            SectionCard("Альтернатива: через DiLink+ (Di+)") {
                Text(
                    "Если автозапуск не сработал — добавьте в Di+:",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("Di+ → Predefined → Startup tasks", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(2.dp))
                val dilinkCommand = "打开应用com.bydmate.app"
                Text(
                    dilinkCommand,
                    color = AccentGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("DiLink+ command", dilinkCommand))
                        Toast.makeText(context, "Скопировано!", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "打开应用 = \"открыть приложение\" + имя пакета.\nДля двух приложений через точку с запятой:\n打开应用ru.yandex.yandexnavi;打开应用com.bydmate.app",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        }

        // RIGHT: Buttons
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard("Системные настройки") {
                Button(
                    onClick = {
                        val opened = runCatching {
                            val intent = Intent(Intent.ACTION_MAIN).apply {
                                setClassName(
                                    "com.byd.appstartmanagement",
                                    "com.byd.appstartmanagement.frame.AppStartManagement"
                                )
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }.isSuccess
                        if (!opened) {
                            runCatching {
                                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(fallback)
                            }
                            Toast.makeText(
                                context,
                                "Не удалось открыть настройки автозапуска DiLink. Открыты настройки приложения.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text("Открыть настройки автозапуска", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (state.isLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(color = AccentGreen)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(state.importStatus ?: "Загрузка...", color = TextSecondary, fontSize = 14.sp)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.prevStep() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("← Назад", color = TextSecondary, fontSize = 14.sp)
                    }
                    Button(
                        onClick = { viewModel.startBydMate() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Запустить BYDMate", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun WelcomeTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentGreen,
            unfocusedBorderColor = CardBorder,
            focusedLabelColor = AccentGreen,
            unfocusedLabelColor = TextSecondary,
            cursorColor = AccentGreen
        )
    )
}

@Composable
private fun WelcomeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
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
