package com.bydmate.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.bydmate.app.R

private const val TAG = "OverlayPermission"

/**
 * Opens the system "draw over other apps" screen for this package (#454).
 * Returns false without throwing on firmwares that do not ship that settings activity.
 */
fun Context.openOverlayPermissionScreen(): Boolean {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "overlay permission screen missing: ${e.message}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "overlay permission screen denied: ${e.message}")
        false
    }
}

/** Opens the overlay permission screen, or shows a short toast when the firmware lacks it. */
internal fun Context.openOverlayPermissionOrToast() {
    if (!openOverlayPermissionScreen()) {
        Toast.makeText(this, R.string.overlay_permission_screen_unavailable, Toast.LENGTH_SHORT).show()
    }
}
