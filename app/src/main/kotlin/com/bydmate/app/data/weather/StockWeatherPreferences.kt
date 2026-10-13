package com.bydmate.app.data.weather

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockWeatherPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    private val _interval = MutableStateFlow(prefs.getInt("interval_minutes", 15).coerceIn(5, 180))
    private val _lastSuccess = MutableStateFlow(prefs.getLong("last_success_ms", 0))
    val enabled = _enabled.asStateFlow()
    val intervalMinutes = _interval.asStateFlow()
    val lastSuccessMs = _lastSuccess.asStateFlow()
    // A config restore can change this file while the process is still running.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _enabled.value = prefs.getBoolean("enabled", false)
        _interval.value = prefs.getInt("interval_minutes", 15).coerceIn(5, 180)
        _lastSuccess.value = prefs.getLong("last_success_ms", 0)
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs.edit().putBoolean("enabled", enabled).apply()
    }

    fun setIntervalMinutes(minutes: Int) {
        val value = minutes.coerceIn(5, 180)
        _interval.value = value
        prefs.edit().putInt("interval_minutes", value).apply()
    }

    internal fun recordSuccess(nowMs: Long) {
        _lastSuccess.value = nowMs
        prefs.edit().putLong("last_success_ms", nowMs).apply()
    }

    companion object { const val FILE = "stock_weather" }
}
