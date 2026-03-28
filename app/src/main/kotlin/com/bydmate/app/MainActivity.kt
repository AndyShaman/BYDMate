package com.bydmate.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.navigation.AppNavigation
import com.bydmate.app.ui.theme.BYDMateTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
                AppNavigation()
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
