package com.bydmate.app.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OverlayNotificationManager {

    private const val TAG = "OverlayNotif"
    private const val AUTO_DISMISS_MS = 6000L

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    suspend fun show(context: Context, title: String, text: String): Boolean {
        if (!canShow(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot show overlay")
            return false
        }
        return withContext(Dispatchers.Main) {
            try {
                renderOverlay(context, title, text)
                true
            } catch (e: Exception) {
                Log.e(TAG, "show failed: ${e.message}")
                false
            }
        }
    }

    private fun renderOverlay(context: Context, title: String, text: String) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.onCreate()

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        var dismissed = false
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val dismiss: () -> Unit = {
            if (!dismissed) {
                dismissed = true
                try {
                    lifecycleOwner.onDestroy()
                    wm.removeView(composeView)
                } catch (e: Exception) {
                    Log.w(TAG, "dismiss failed: ${e.message}")
                }
            }
        }

        composeView.setContent {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(min = 320.dp, max = 540.dp)
                    .background(CardSurface, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .clickable { dismiss() }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        color = AccentGreen
                    )
                    if (text.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = text,
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                    }
                }
                Text(
                    text = "×",
                    fontSize = 18.sp,
                    color = TextSecondary,
                    modifier = Modifier.clickable { dismiss() }
                )
            }
        }

        wm.addView(composeView, params)
        playNotificationSound(context)
        Handler(Looper.getMainLooper()).postDelayed(dismiss, AUTO_DISMISS_MS)
    }

    private fun playNotificationSound(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        } catch (e: Exception) {
            Log.w(TAG, "sound failed: ${e.message}")
        }
    }
}

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
