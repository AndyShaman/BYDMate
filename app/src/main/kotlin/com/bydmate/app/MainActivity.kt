package com.bydmate.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.TrackingService
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.ui.components.ConsumptionThresholds
import com.bydmate.app.ui.components.LocalConsumptionThresholds
import com.bydmate.app.ui.navigation.AppNavigation
import com.bydmate.app.ui.theme.BYDMateTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var updateChecker: UpdateChecker

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val BACKGROUND_LOCATION_REQUEST_CODE = 1002
        private const val DEFAULT_LANG = "ru"
    }

    override fun attachBaseContext(newBase: Context) {
        // Cold-start: read stored language and wrap baseContext with a Configuration
        // that already has the right locale. Without this, initial layouts paint in
        // the system locale for one frame before AppCompat catches up.
        val lang = newBase.getSharedPreferences(LocalePreferences.FILE, Context.MODE_PRIVATE)
            .getString(LocalePreferences.KEY_LANG, null) ?: DEFAULT_LANG
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val cfg = Configuration(newBase.resources.configuration).apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()

        setContent {
            val thresholds by produceState(initialValue = ConsumptionThresholds.Default) {
                settingsRepository.observeConsumptionThresholds().collect { (good, bad) ->
                    value = ConsumptionThresholds(good = good, bad = bad)
                }
            }

            // Runtime locale switch: listen to LocalePreferences via SharedPreferences,
            // mutate Activity resources in place (deprecated since API 24 but functional
            // on Android 12), then bump `lang` state. The new LocalConfiguration value
            // triggers recomposition of every stringResource consumer in the tree.
            // LocalContext is left untouched so Hilt's `instanceof Activity` check passes.
            val prefs = remember {
                applicationContext.getSharedPreferences(
                    LocalePreferences.FILE, Context.MODE_PRIVATE
                )
            }
            var lang by remember {
                mutableStateOf(prefs.getString(LocalePreferences.KEY_LANG, null) ?: DEFAULT_LANG)
            }
            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == LocalePreferences.KEY_LANG) {
                        val newLang = p.getString(key, DEFAULT_LANG) ?: DEFAULT_LANG
                        if (newLang != lang) {
                            val locale = Locale.forLanguageTag(newLang)
                            Locale.setDefault(locale)
                            val res = resources
                            val cfg = Configuration(res.configuration).apply { setLocale(locale) }
                            @Suppress("DEPRECATION")
                            res.updateConfiguration(cfg, res.displayMetrics)
                            lang = newLang
                        }
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            val localizedConfig = remember(lang) {
                Configuration(resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(lang))
                }
            }

            BYDMateTheme {
                CompositionLocalProvider(
                    LocalConfiguration provides localizedConfig,
                    LocalConsumptionThresholds provides thresholds,
                ) {
                    AppNavigation(
                        settingsRepository = settingsRepository,
                        updateChecker = updateChecker,
                    )
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissions")
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            // Base permissions granted, check background location
            requestBackgroundLocationIfNeeded()
            startTrackingService()
        }
    }

    /**
     * On Android 10+ (API 29+), ACCESS_BACKGROUND_LOCATION must be requested
     * SEPARATELY after ACCESS_FINE_LOCATION is granted. Without it, the
     * foreground service GPS does not work when the activity is not visible.
     */
    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Requesting ACCESS_BACKGROUND_LOCATION separately")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                BACKGROUND_LOCATION_REQUEST_CODE
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val denied = mutableListOf<String>()
                permissions.forEachIndexed { index, permission ->
                    if (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED) {
                        Log.i(TAG, "Permission granted: $permission")
                    } else {
                        Log.w(TAG, "Permission denied: $permission")
                        denied.add(permission)
                    }
                }
                if (denied.isNotEmpty()) {
                    Log.w(TAG, "Starting TrackingService with denied permissions: $denied")
                }
                // Now request background location (must be after fine location)
                requestBackgroundLocationIfNeeded()
                startTrackingService()
            }
            BACKGROUND_LOCATION_REQUEST_CODE -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    Log.i(TAG, "Background location granted — GPS will work in background")
                } else {
                    Log.w(TAG, "Background location denied — GPS may not work when app is hidden")
                }
            }
        }
    }

    private fun startTrackingService() {
        TrackingService.start(this)
    }
}
