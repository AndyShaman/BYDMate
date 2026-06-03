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
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
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
            stringResource(R.string.welcome_step_indicator, state.step),
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        when (state.step) {
            1 -> TariffStep(state, viewModel)
            2 -> AutoStartStep(state, viewModel)
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
            SectionCard(stringResource(R.string.welcome_battery_section_title)) {
                WelcomeTextField(
                    label = stringResource(R.string.settings_battery_capacity_label),
                    value = state.batteryCapacity,
                    onValueChange = { viewModel.setBatteryCapacity(it) }
                )
                Text(
                    stringResource(R.string.welcome_battery_capacity_hint),
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            SectionCard(stringResource(R.string.settings_app_currency_label)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    SettingsRepository.CURRENCIES.forEach { currency ->
                        WelcomeChip(
                            label = currency.code,
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
            SectionCard(stringResource(R.string.welcome_tariff_section_title)) {
                WelcomeTextField(
                    label = stringResource(R.string.welcome_tariff_home_label, state.currencySymbol),
                    value = state.homeTariff,
                    onValueChange = { viewModel.setHomeTariff(it) }
                )
                WelcomeTextField(
                    label = stringResource(R.string.welcome_tariff_dc_label, state.currencySymbol),
                    value = state.dcTariff,
                    onValueChange = { viewModel.setDcTariff(it) }
                )
            }

            SectionCard(stringResource(R.string.welcome_tariff_cost_section_title)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WelcomeChip(stringResource(R.string.welcome_tariff_home_chip), state.tripCostMode == "home") { viewModel.setTripCostMode("home") }
                    WelcomeChip("DC", state.tripCostMode == "dc") { viewModel.setTripCostMode("dc") }
                    WelcomeChip(stringResource(R.string.welcome_tariff_custom_chip), state.tripCostMode == "custom") { viewModel.setTripCostMode("custom") }
                }
                if (state.tripCostMode == "custom") {
                    WelcomeTextField(
                        label = stringResource(R.string.settings_tariff_custom_label, state.currencySymbol),
                        value = state.customTariff,
                        onValueChange = { viewModel.setCustomTariff(it) }
                    )
                }
                Text(
                    stringResource(R.string.welcome_tariff_cost_note),
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
                Text(stringResource(R.string.welcome_next_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
            SectionCard(stringResource(R.string.welcome_autostart_step_title)) {
                Text(
                    stringResource(R.string.welcome_autostart_step_description),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.welcome_autostart_instruction_1), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.welcome_autostart_instruction_2), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.welcome_autostart_instruction_3), color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.welcome_autostart_off_note),
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Text(
                    stringResource(R.string.welcome_autostart_apk_update_warning),
                    color = SocYellow,
                    fontSize = 12.sp
                )
            }

            SectionCard(stringResource(R.string.welcome_autostart_dilink_section_title)) {
                Text(
                    stringResource(R.string.welcome_autostart_dilink_hint),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.welcome_autostart_dilink_instruction), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(2.dp))
                val dilinkCommand = "打开应用com.bydmate.app"
                val copiedToast = stringResource(R.string.welcome_autostart_command_copied_toast)
                Text(
                    dilinkCommand,
                    color = AccentGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("DiLink+ command", dilinkCommand))
                        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.welcome_autostart_command_explanation),
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
            val openSettingsError = stringResource(R.string.welcome_autostart_open_settings_error)
            SectionCard(stringResource(R.string.welcome_system_settings_section_title)) {
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
                                openSettingsError,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(stringResource(R.string.welcome_autostart_open_settings_button), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (state.isLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(color = AccentGreen)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(state.importStatus ?: stringResource(R.string.welcome_loading_default), color = TextSecondary, fontSize = 14.sp)
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
                        Text(stringResource(R.string.welcome_back_button), color = TextSecondary, fontSize = 14.sp)
                    }
                    Button(
                        onClick = { viewModel.startBydMate() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text(stringResource(R.string.welcome_done_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
