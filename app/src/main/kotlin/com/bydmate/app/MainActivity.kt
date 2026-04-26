package com.bydmate.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bydmate.app.data.autoservice.AdbKeyStore
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.TrackingService
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.ui.navigation.AppNavigation
import com.bydmate.app.ui.theme.BYDMateTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var updateChecker: UpdateChecker
    @Inject lateinit var adbOnDeviceClient: AdbOnDeviceClient
    @Inject lateinit var adbKeyStore: AdbKeyStore

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val BACKGROUND_LOCATION_REQUEST_CODE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()

        setContent {
            BYDMateTheme {
                AppNavigation(
                    settingsRepository = settingsRepository,
                    updateChecker = updateChecker,
                )
            }
        }

        // TEMP smoke trigger for Phase 2 C0 manual gate — REVERT after success.
        // Auto-runs adbOnDeviceClient.connect() once on activity start, surfaces
        // result as a long Toast plus full detail under tag "BYDMate-AdbSmoke".
        runC0SmokeTrigger()
    }

    private fun runC0SmokeTrigger() {
        val smokeTag = "BYDMate-AdbSmoke"
        lifecycleScope.launch {
            val fingerprint = try {
                adbKeyStore.getFingerprint()
            } catch (e: Exception) {
                "<fingerprint error: ${e.message}>"
            }
            Log.i(smokeTag, "smoke start, key fingerprint=$fingerprint")
            val result = adbOnDeviceClient.connect()
            if (result.isSuccess) {
                val msg = "ADB ✓ connected · fp ${fingerprint.take(11)}"
                Log.i(smokeTag, "connect success — fp=$fingerprint")
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                val socOut = adbOnDeviceClient.exec(
                    "service call autoservice 5 i32 1014 i32 1246777400"
                )
                Log.i(smokeTag, "exec autoservice SOC fid → $socOut")
            } else {
                val ex = result.exceptionOrNull()
                val short = ex?.javaClass?.simpleName + ": " + (ex?.message ?: "n/a")
                Log.w(smokeTag, "connect failed", ex)
                Toast.makeText(this@MainActivity, "ADB ✗ failed: $short", Toast.LENGTH_LONG).show()
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
