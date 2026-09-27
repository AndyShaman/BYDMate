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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
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
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.ui.overlay.OverlayLifecycleOwner
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextSecondary
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.WithAppFontScale
import com.bydmate.app.util.appLocalizedContext

/**
 * A show() onCancel that tells «Отмена» from «nobody answered»: the timeout runs [onTimeout]
 * instead. Any other onCancel gets both, as before.
 */
class CancelOrTimeout(private val onCancel: () -> Unit, val onTimeout: () -> Unit) : () -> Unit {
    override fun invoke() = onCancel()
}

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
    private const val DEFAULT_TIMEOUT_MS = 15_000L
    private val TEXT_MAX_HEIGHT = 300.dp

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** (cancel, run) button labels in the app language; the caller's context is usually the
     *  application one, which stays on the system locale. Resolved at every show. */
    internal fun buttonLabels(context: Context): Pair<String, String> {
        val lc = context.appLocalizedContext()
        return lc.getString(R.string.confirm_overlay_cancel) to lc.getString(R.string.confirm_overlay_run)
    }

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
            // Dispose on view detach to avoid attach-vs-onDestroy race (see ListeningOverlay).
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
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
                    // removeView must precede onDestroy: detach triggers composition dispose,
                    // so the recomposer is torn down cleanly before the lifecycle is destroyed.
                    wm.removeView(composeView)
                    lifecycleOwner.onDestroy()
                } catch (e: Exception) {
                    Log.w(TAG, "dismiss failed: ${e.message}")
                }
                when (outcome) {
                    "confirm" -> onConfirm()
                    "timeout" -> if (onCancel is CancelOrTimeout) onCancel.onTimeout() else onCancel()
                    else -> onCancel()
                }
            }
        }

        val fontScale = LocalePreferences(context).getFontScale()
        val (cancelLabel, runLabel) = buttonLabels(context)
        val lc = context.appLocalizedContext()
        val seconds = ((timeoutMs + 999) / 1000).toInt().coerceAtLeast(1)
        composeView.setContent {
            WithAppFontScale(fontScale) {
                // Seconds left: the bar and the line count down together, once a second.
                var left by remember { mutableIntStateOf(seconds) }
                LaunchedEffect(Unit) {
                    while (left > 0) {
                        delay(1000)
                        left--
                    }
                }
                Column(
                    modifier = Modifier
                        .width(640.dp)
                        .background(CardSurface, RoundedCornerShape(16.dp))
                        .border(1.5.dp, CardBorder, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                ) {
                    // The name and the actions scroll within a capped height and take only what the
                    // countdown and the buttons leave: a long text never pushes them off the window.
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = TEXT_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = lc.getString(R.string.auto_ui_confirm_rule, ruleName),
                            fontSize = 16.sp,
                            color = TextSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = actionsSummary.ifBlank { ruleName },
                            fontSize = 26.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(CardBorder)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(left.toFloat() / seconds)
                                .fillMaxHeight()
                                .background(AccentGreen)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = lc.getString(R.string.auto_ui_confirm_countdown, left),
                        fontSize = 18.sp,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { dismiss("cancel") },
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SocRed,
                                contentColor = NavyDark,
                            ),
                        ) {
                            Text(cancelLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { dismiss("confirm") },
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentGreen,
                                contentColor = NavyDark,
                            ),
                        ) {
                            Text(runLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
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
