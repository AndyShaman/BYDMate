package com.bydmate.app.data.automation

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bydmate.app.ui.overlay.OverlayLifecycleOwner
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary

/**
 * Shows a SYSTEM_ALERT_WINDOW overlay asking the user to confirm execution
 * of an automation rule. Replaces the legacy notification-based confirm flow
 * for rules that have `confirmBeforeExecute = true`.
 *
 * Caller passes callbacks; the manager handles timeout auto-cancel, sound,
 * and teardown. Stateless singleton object (same shape as OverlayNotificationManager).
 */
object ConfirmOverlayManager {

    private const val TAG = "ConfirmOverlay"
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(
        context: Context,
        ruleName: String,
        actionsSummary: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        if (!canShow(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — caller must fall back")
            return false
        }
        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                render(context, ruleName, actionsSummary, onConfirm, onCancel, timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "show failed: ${e.message}")
                onCancel()
            }
        }
        return true
    }

    private fun render(
        context: Context,
        ruleName: String,
        actionsSummary: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
        timeoutMs: Long,
    ) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.onCreate()

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        var handled = false
        val handler = Handler(Looper.getMainLooper())

        val dismiss: (String) -> Unit = { outcome ->
            if (!handled) {
                handled = true
                try {
                    lifecycleOwner.onDestroy()
                    wm.removeView(composeView)
                } catch (e: Exception) {
                    Log.w(TAG, "dismiss failed: ${e.message}")
                }
                when (outcome) {
                    "confirm" -> onConfirm()
                    else -> onCancel()
                }
            }
        }

        composeView.setContent {
            Column(
                modifier = Modifier
                    .widthIn(min = 340.dp, max = 420.dp)
                    .background(CardSurface, RoundedCornerShape(12.dp))
                    .border(1.5.dp, CardBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text(
                    text = ruleName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                if (actionsSummary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = actionsSummary,
                        fontSize = 13.sp,
                        color = TextMuted,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { dismiss("cancel") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SocRed,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Отмена", fontSize = 14.sp)
                    }
                    Button(
                        onClick = { dismiss("confirm") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Выполнить", fontSize = 14.sp)
                    }
                }
            }
        }

        wm.addView(composeView, params)
        playSound(context)
        handler.postDelayed({ dismiss("timeout") }, timeoutMs)
    }

    private fun playSound(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        } catch (e: Exception) {
            Log.w(TAG, "sound failed: ${e.message}")
        }
    }
}
