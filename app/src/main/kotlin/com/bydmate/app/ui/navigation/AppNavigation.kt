package com.bydmate.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bydmate.app.ui.charges.ChargesScreen
import com.bydmate.app.ui.dashboard.DashboardScreen
import com.bydmate.app.ui.map.MapScreen
import com.bydmate.app.ui.settings.SettingsScreen
import com.bydmate.app.ui.theme.*
import com.bydmate.app.ui.trips.TripsScreen

enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Home", Icons.Outlined.Home),
    Trips("trips", "Trips", Icons.Outlined.DirectionsCar),
    Charges("charges", "Charges", Icons.Outlined.BatteryChargingFull),
    Map("map", "Map", Icons.Outlined.Map),
    Settings("settings", "Settings", Icons.Outlined.Settings)
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        containerColor = NavyDark,
        bottomBar = {
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
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen() }
            composable(Screen.Trips.route) { TripsScreen() }
            composable(Screen.Charges.route) { ChargesScreen() }
            composable(Screen.Map.route) { MapScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
