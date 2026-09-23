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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.bydmate.app.ui.components.AdbEnableSteps
import com.bydmate.app.ui.theme.*
import com.bydmate.app.util.APP_LANGUAGES

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
            stringResource(R.string.welcome_step_indicator, state.step, WelcomeViewModel.TOTAL_STEPS),
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        when (state.step) {
            1 -> LanguageStep(state, viewModel)
            2 -> TariffStep(state, viewModel)
            3 -> AdbStep(state, viewModel)
            4 -> AutoStartStep(state, viewModel)
        }
    }
}

@Composable
private fun LanguageStep(state: WelcomeUiState, viewModel: WelcomeViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(stringResource(R.string.welcome_language_title)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                APP_LANGUAGES.forEach { (code, label) ->
                    WelcomeChip(
                        label = label,
                        selected = state.language == code,
                        onClick = { if (state.language != code) viewModel.setLanguage(code) }
                    )
                }
            }
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

            Spacer(modifier = Modifier.weight(1f))

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
                    onClick = { viewModel.nextStep() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text(stringResource(R.string.welcome_next_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AdbStep(state: WelcomeUiState, viewModel: WelcomeViewModel) {
    var helpOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // LEFT: Instructions
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(stringResource(R.string.welcome_adb_title)) {
                Text(stringResource(R.string.welcome_adb_intro), color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                AdbEnableSteps()
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.welcome_adb_gives), color = TextMuted, fontSize = 11.sp)
            }

            SectionCard(stringResource(R.string.welcome_adb_no_dev_title)) {
                Text(stringResource(R.string.welcome_adb_no_dev_body), color = TextSecondary, fontSize = 13.sp)
            }
        }

        // RIGHT: Check + instructions + navigation
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(stringResource(R.string.welcome_adb_check_title)) {
                val (dotColor, stateText) = when (state.adbCheck) {
                    AdbCheck.Ok -> AccentGreen to stringResource(R.string.welcome_adb_state_ok)
                    AdbCheck.Failed -> SocRed to stringResource(R.string.welcome_adb_state_failed)
                    else -> TextMuted to stringResource(R.string.welcome_adb_state_not_checked)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(dotColor))
                    Text(stateText, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                if (state.adbCheck == AdbCheck.Ok) {
                    Text(stringResource(R.string.welcome_adb_ok_hint), color = TextSecondary, fontSize = 13.sp)
                } else {
                    Button(
                        onClick = { viewModel.checkAdb() },
                        enabled = state.adbCheck != AdbCheck.Checking,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            disabledContainerColor = AccentBlue.copy(alpha = 0.6f)
                        )
                    ) {
                        if (state.adbCheck == AdbCheck.Checking) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.welcome_adb_checking), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text(
                                stringResource(
                                    if (state.adbCheck == AdbCheck.Failed) R.string.welcome_adb_check_again_button
                                    else R.string.welcome_adb_check_button
                                ),
                                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(stringResource(R.string.welcome_adb_check_hint), color = TextMuted, fontSize = 11.sp)
                }
            }

            SectionCard(stringResource(R.string.welcome_adb_instruction_title)) {
                OutlinedButton(
                    onClick = { helpOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.welcome_adb_instruction_button), color = TextSecondary, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // «Далее» stays enabled: the check is advisory, the user may skip it.
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
                    onClick = { viewModel.nextStep() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text(stringResource(R.string.welcome_next_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Same long explanation as the Settings ADB-restore help.
    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            containerColor = CardSurface,
            title = {
                Text(stringResource(R.string.settings_adb_restore_help_title), color = TextPrimary)
            },
            text = {
                Text(
                    stringResource(R.string.settings_adb_restore_help_body),
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { helpOpen = false }) {
                    Text(stringResource(R.string.nav_autostart_dialog_button), color = AccentGreen)
                }
            },
        )
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
