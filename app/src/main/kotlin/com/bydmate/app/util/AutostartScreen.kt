package com.bydmate.app.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.bydmate.app.R

/** Opens the DiLink autostart-unblock system screen (BYD app-start management).
 *  Falls back to the app details settings page plus a toast when the BYD screen
 *  is missing (non-BYD device or firmware change). Shared by the post-update
 *  dialog (AppNavigation) and the permanent Settings button. */
object AutostartScreen {
    fun open(context: Context) {
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
                context.getString(R.string.nav_autostart_open_settings_error),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
