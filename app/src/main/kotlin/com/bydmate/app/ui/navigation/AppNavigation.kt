package com.bydmate.app.ui.navigation

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.ui.charges.ChargesScreen
import com.bydmate.app.ui.automation.AutomationScreen
import com.bydmate.app.ui.places.PlacesScreen
import com.bydmate.app.ui.dashboard.DashboardScreen
import com.bydmate.app.ui.settings.SettingsScreen
import com.bydmate.app.ui.settings.UpdateDialog
import com.bydmate.app.ui.settings.UpdateState
import com.bydmate.app.ui.theme.*
import com.bydmate.app.ui.trips.TripsScreen
import com.bydmate.app.ui.welcome.WelcomeScreen

enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Главная", Icons.Outlined.Home),
    Trips("trips", "Поездки", Icons.Outlined.DirectionsCar),
    Charges("charges", "Зарядки", Icons.Outlined.BatteryChargingFull),
    Automation("automation", "Автоматизация", Icons.Outlined.Bolt),
    Settings("settings", "Настройки", Icons.Outlined.Settings)
}

@Composable
fun AppNavigation(
    settingsRepository: SettingsRepository,
    updateChecker: UpdateChecker
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        startDestination = if (settingsRepository.isSetupCompleted()) "dashboard" else "welcome"
    }

    if (startDestination == null) return // Loading

    // Автоматическая проверка обновлений при запуске приложения.
    // UpdateChecker сам throttle-ит запросы (10 мин между реальными походами в GitHub).
    val autoCheckContext = LocalContext.current
    val autoCheckScope = rememberCoroutineScope()
    var autoUpdateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var autoUpdateState by remember { mutableStateOf<UpdateState?>(null) }
    LaunchedEffect(Unit) {
        if (!UpdateChecker.isAutoCheckEnabled(autoCheckContext)) return@LaunchedEffect
        try {
            val info = updateChecker.checkForUpdate(autoCheckContext, forceCheck = false)
            if (info != null) {
                autoUpdateInfo = info
                autoUpdateState = UpdateState.Available(version = info.version, notes = info.releaseNotes)
            }
        } catch (_: Exception) {
            // тихо игнорируем — оффлайн, rate-limit и т.п.
        }
    }
    autoUpdateState?.let { dialogState ->
        val currentVersion = runCatching {
            autoCheckContext.packageManager.getPackageInfo(autoCheckContext.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        UpdateDialog(
            currentVersion = currentVersion,
            state = dialogState,
            onCheck = {
                val info = autoUpdateInfo ?: return@UpdateDialog
                autoUpdateState = UpdateState.Downloading(info.version, "Скачивание: 0%")
                autoCheckScope.launch {
                    try {
                        updateChecker.downloadAndInstall(autoCheckContext, info) { progress ->
                            autoUpdateState = UpdateState.Downloading(info.version, progress)
                        }
                    } catch (e: Exception) {
                        autoUpdateState = UpdateState.Error(e.message ?: "Download failed")
                    }
                }
            },
            onDismiss = {
                autoUpdateState = null
                autoUpdateInfo = null
            }
        )
    }

    // Post-install reminder: первый запуск новой версии → напомнить про Disable background Apps.
    val currentAppVersion = remember {
        runCatching {
            autoCheckContext.packageManager.getPackageInfo(autoCheckContext.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }
    var showPostInstallReminder by remember {
        mutableStateOf(UpdateChecker.getLastSeenVersion(autoCheckContext) != currentAppVersion)
    }
    if (showPostInstallReminder) {
        PostInstallReminderDialog(
            version = currentAppVersion,
            onDismiss = {
                UpdateChecker.setLastSeenVersion(autoCheckContext, currentAppVersion)
                showPostInstallReminder = false
            }
        )
    }

    val isWelcome = currentDestination?.route == "welcome"

    Scaffold(
        containerColor = NavyDark,
        bottomBar = {
            if (!isWelcome) {
                NavigationBar(
                    containerColor = NavBarBackground
                ) {
                    Screen.entries.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentGreen,
                                selectedTextColor = AccentGreen,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = NavIndicator
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination!!,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("welcome") {
                WelcomeScreen(
                    onComplete = {
                        navController.navigate("dashboard") {
                            popUpTo("welcome") { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen()
            }
            composable(Screen.Trips.route) { TripsScreen() }
            composable(Screen.Charges.route) {
                ChargesScreen(onNavigateSettings = { navController.navigate(Screen.Settings.route) })
            }
            composable(Screen.Automation.route) { AutomationScreen() }
            composable(Screen.Settings.route) { SettingsScreen(onNavigateToPlaces = { navController.navigate("places") }) }
            composable("places") { PlacesScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

@Composable
private fun PostInstallReminderDialog(version: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = MutableInteractionSource()
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .clickable { /* absorb */ }
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("BYDMate обновлён до v$version", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Проверьте, что фоновая работа не заблокирована:\n\n" +
                            "Настройки DiLink → General → Disable background Apps → BYDMate → OFF\n\n" +
                            "Если переключатель включён (ON), DiLink может прибить сервис BYDMate — тогда поездки и GPS не будут писаться.",
                        color = TextSecondary, fontSize = 14.sp
                    )
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
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Понятно", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
