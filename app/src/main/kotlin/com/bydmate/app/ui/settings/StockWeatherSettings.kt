package com.bydmate.app.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterEntryPoint
import com.bydmate.app.data.weather.StockWeatherState
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.theme.CardSurfaceElevated
import dagger.hilt.android.EntryPointAccessors
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
internal fun StockWeatherSettings() {
    val context = LocalContext.current
    val weather = remember { EntryPointAccessors.fromApplication(context.applicationContext, ClusterEntryPoint::class.java).stockWeatherManager() }
    val enabled by weather.preferences.enabled.collectAsStateWithLifecycle()
    val minutes by weather.preferences.intervalMinutes.collectAsStateWithLifecycle()
    val updatedAt by weather.preferences.lastSuccessMs.collectAsStateWithLifecycle()
    val state by weather.state.collectAsStateWithLifecycle()
    var interval by remember(minutes) { mutableStateOf(minutes.toFloat()) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) TrackingService.refreshLocationPermission(context)
        weather.preferences.setEnabled(granted)
    }
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.stock_weather_enable), traceId = "stock_weather",
                description = stringResource(R.string.stock_weather_description), checked = enabled,
                onCheckedChange = { on ->
                    if (on && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else weather.preferences.setEnabled(on)
                },
            )
            if (enabled) {
                SettingSliderRow(title = stringResource(R.string.stock_weather_interval),
                    value = interval, onValueChange = { interval = it }, valueRange = 5f..180f, steps = 34,
                    valueLabel = stringResource(R.string.stock_weather_minutes, interval.roundToInt()),
                    onValueChangeFinished = { weather.preferences.setIntervalMinutes(interval.roundToInt()) })
                val status = when (state) {
                    StockWeatherState.Disabled -> null
                    StockWeatherState.WaitingLocation -> R.string.stock_weather_wait_location
                    StockWeatherState.WaitingInternet -> R.string.stock_weather_wait_internet
                    StockWeatherState.NoLocationPermission -> R.string.stock_weather_no_permission
                    StockWeatherState.Updating -> R.string.stock_weather_updating
                    StockWeatherState.Updated -> R.string.stock_weather_updated
                    StockWeatherState.Unavailable -> R.string.stock_weather_unavailable
                    StockWeatherState.Failed -> R.string.stock_weather_failed
                }
                status?.let { SettingHint(stringResource(it)) }
                if (updatedAt > 0) SettingHint(stringResource(R.string.stock_weather_last_update,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updatedAt))))
                SettingActionRow(title = stringResource(R.string.stock_weather_refresh),
                    buttonLabel = stringResource(R.string.stock_weather_refresh), onClick = { weather.requestRefresh() },
                    enabled = state != StockWeatherState.Updating)
                SettingHint(stringResource(R.string.stock_weather_refresh_hint))
            }
        }
    }
}
