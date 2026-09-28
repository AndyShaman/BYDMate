package com.bydmate.app.ui.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bydmate.app.R
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.WithAppFontScale
import com.bydmate.app.util.appLocalizedContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Persistent "Слушаю"/"Думаю" pill shown for the whole lifetime of a continuous voice session
 * (Wave B). Unlike [OverlayNotificationManager] this has no auto-dismiss and no ringtone; its text
 * updates in place via [textState] instead of tearing the window down and rebuilding it.
 *
 * v2 (Wave D2): the pill sits below the DiLink status bar and is draggable; a second window below
 * it shows the running dialog ("Ты: …" / "Агент: …"). Both windows share one x/y position, which is
 * persisted across sessions.
 *
 * v3 (Wave F): both windows are anchored at TOP|START instead of TOP|CENTER_HORIZONTAL, so x is an
 * absolute left-edge offset that does not shift when the pill's content width changes.
 *
 * v4.1: the status surface is intentionally minimal: one non-touchable microphone icon fixed in
 * the upper-left, matching the stock BYD assistant placement. The "Слушаю/Думаю" pill text is not
 * rendered anymore; the transcript/answer dialog stays below the icon. Alice remains independent.
 */
object ListeningOverlay {

    private const val TAG = "ListeningOverlay"
    // Distance from the top edge to the pill; below the DiLink status bar so the pill is not clipped.
    internal const val TOP_MARGIN_DP = 56
    // Gap between the pill window and the dialog window below it.
    internal const val PILL_OFFSET_DP = 48
    // Left-edge inset for the fixed listening mic indicator.
    internal const val MIC_INDICATOR_MARGIN_DP = 16
    // The dialog block grows with the answer up to this share of the screen height; beyond that
    // it would cover the road view behind the overlay.
    private const val DIALOG_MAX_HEIGHT_FRACTION = 0.5f
    private val textState = MutableStateFlow("")
    private val heardState = MutableStateFlow<String?>(null)   // "Ты: …" row text (no label)
    private val answerState = MutableStateFlow<String?>(null)  // "Агент: …" row text (no label)
    private val labelsState = MutableStateFlow("" to "")

    // Test-visible reads of the dialog state; the flows themselves stay private so only the three
    // mutators below can change them.
    internal val heardText: String? get() = heardState.value
    internal val answerText: String? get() = answerState.value
    internal val dialogLabels: Pair<String, String> get() = labelsState.value

    fun relocale(context: Context) {
        val lc = context.appLocalizedContext()
        labelsState.value = lc.getString(R.string.orb_you) to lc.getString(R.string.orb_agent)
    }

    /** Shows the recognized user phrase and clears any previous answer. No-op-safe when not shown --
     *  it only mutates state the dialog window observes, so calling it before [show] is harmless. */
    fun showHeard(text: String) {
        answerState.value = null
        heardState.value = text
    }

    /** Shows the agent's answer under the last heard phrase. No-op-safe when not shown. */
    fun showAnswer(text: String) {
        answerState.value = text
    }

    /** Clears both dialog rows (the block collapses to nothing). No-op-safe when not shown. */
    fun clearDialog() {
        heardState.value = null
        answerState.value = null
    }

    /** Whatever is needed to tear a shown window down again. Kept behind an interface (rather than
     *  raw WindowManager/ComposeView fields) so [hide]'s deferred cleanup can capture a fully
     *  self-contained snapshot -- see [hide] -- and so this object is exercisable from a JVM unit
     *  test without a real Android view/window (ListeningOverlayTest fakes this). */
    internal interface OverlayHandle {
        fun destroy()
    }

    private class RealOverlayHandle(
        private val wm: WindowManager,
        private val view: View,
        private val owner: OverlayLifecycleOwner? = null,
    ) : OverlayHandle {
        override fun destroy() {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "hide failed: ${e.message}")
            }
            owner?.onDestroy()
        }
    }

    /** Wraps the pill and dialog handles so [hide]'s single-handle logic tears both windows down. */
    internal class CompositeOverlayHandle(private val handles: List<OverlayHandle>) : OverlayHandle {
        override fun destroy() {
            handles.forEach { it.destroy() }
        }
    }

    // The currently shown window, or null if none. Volatile: show() commits this on the main
    // thread (see show()'s withContext(Dispatchers.Main)) while hide() may run on any thread (the
    // continuous voice session's own coroutine dispatcher) -- a plain var would not guarantee hide()'s
    // synchronous null-out below is visible to a show() racing in on a different thread.
    @Volatile private var active: OverlayHandle? = null

    // Test seam: whether SYSTEM_ALERT_WINDOW is granted. Defaults to the real Settings check;
    // ListeningOverlayTest overrides this so attachWindow actually runs without a real Context.
    internal var permissionCheck: (Context) -> Boolean = { context ->
        canShow(context) || SteeringWheelKeyService.instance != null
    }

    // Test seam: builds and attaches the real window in production. Tests substitute a fake that
    // returns a lightweight OverlayHandle without ever touching WindowManager/ComposeView, so this
    // object is JVM-testable without an Android runtime. Any exception here propagates out of
    // show()'s try block, which must not have committed `active` yet -- attachWindow only returns
    // once the window is actually attached (addView succeeds), so a thrown exception can never
    // leave a half-built handle behind.
    internal var attachWindow: (Context, String) -> OverlayHandle = { context, initial -> realAttach(context, initial) }

    // Test seam: how hide()'s deferred teardown is scheduled. WindowManager.removeView must run on
    // the thread that owns the window, so production posts to the main looper; tests inject a
    // synchronous (or manually-fired) poster so no real Looper is needed.
    internal var poster: (Runnable) -> Unit = { r -> Handler(Looper.getMainLooper()).post(r) }

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** True while the pill overlay is attached (a live voice session or another announcement owns it). */
    fun isShowing(): Boolean = active != null

    /** No-op if already shown -- a continuous session must never stack a second window. */
    suspend fun show(context: Context, initial: String) {
        if (active != null) return // cheap fast path; not authoritative, see recheck below
        if (!permissionCheck(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted -- cannot show overlay")
            return
        }
        withContext(Dispatchers.Main) {
            // Authoritative guard: two concurrent show() calls can both pass the check above
            // before either reaches this block (it runs on the caller's thread, before the
            // dispatch to Main). Rechecking here means both the check and the `active = ...`
            // commit run on the main thread, so they serialize and only one call can attach.
            if (active != null) return@withContext
            try {
                textState.value = initial
                // Commit `active` only after attachWindow returns successfully -- if it throws
                // (e.g. addView failure), `active` is never assigned and stays null, so the next
                // show() retries instead of silently no-op'ing forever.
                val overlayContext = SteeringWheelKeyService.instance ?: context
                active = attachWindow(overlayContext, initial)
            } catch (e: Exception) {
                Log.e(TAG, "show failed: ${e.message}")
            }
        }
    }

    /** Updates the pill's text in place; a no-op (Compose just never observes it) if not shown. */
    fun update(text: String) {
        textState.value = text
    }

    /** Tears the window down. Safe to call when not showing. Captures the current handle into a
     *  local and clears [active] synchronously so a show() racing in right after hide() builds a
     *  genuinely new window instead of no-op'ing against a handle that is about to be destroyed;
     *  the deferred destroy() below only ever touches the captured local [handle], never the
     *  [active] field, so it can never tear down that newer window. */
    fun hide() {
        val handle = active ?: return
        active = null
        poster(Runnable { handle.destroy() })
    }

    private fun overlayType(context: Context): Int =
        if (context is android.accessibilityservice.AccessibilityService)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    @Suppress("LongMethod")
    private fun realAttach(context: Context, @Suppress("UNUSED_PARAMETER") initial: String): OverlayHandle {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        val left = (MIC_INDICATOR_MARGIN_DP * density).toInt()
        val top = (TOP_MARGIN_DP * density).toInt()
        val pillOffsetPx = (PILL_OFFSET_DP * density).toInt()

        relocale(context)
        val fontScale = LocalePreferences(context).getFontScale()

        // One fixed, click-through status icon in the upper-left. Keeping it non-touchable means
        // it never steals taps from the car UI underneath.
        val micSizePx = (40 * density).toInt()
        val micPaddingPx = (8 * density).toInt()
        val micParams = WindowManager.LayoutParams(
            micSizePx,
            micSizePx,
            overlayType(context),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = left
            y = top
        }

        // The running transcript/answer stays below the icon. It is also click-through.
        val dialogParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(context),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = left
            y = top + pillOffsetPx
        }

        val dialogOwner = OverlayLifecycleOwner().also { it.onCreate() }
        val micView = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            imageTintList = ColorStateList.valueOf(AccentGreen.toArgb())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(micPaddingPx, micPaddingPx, micPaddingPx, micPaddingPx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CardSurface.copy(alpha = 0.94f).toArgb())
                setStroke((density.coerceAtLeast(1f)).toInt(), AccentGreen.toArgb())
            }
        }
        val dialogView = ComposeView(context)

        dialogView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setViewTreeLifecycleOwner(dialogOwner)
            setViewTreeSavedStateRegistryOwner(dialogOwner)
            setContent { WithAppFontScale(fontScale) { DialogContent() } }
        }

        wm.addView(micView, micParams)
        Log.i(TAG, "mic overlay attached type=${micParams.type} host=${context.javaClass.simpleName} x=$left y=$top size=$micSizePx")
        wm.addView(dialogView, dialogParams)
        Log.i(TAG, "dialog overlay attached type=${dialogParams.type} x=$left y=${top + pillOffsetPx}")

        return CompositeOverlayHandle(
            listOf(
                RealOverlayHandle(wm, micView),
                RealOverlayHandle(wm, dialogView, dialogOwner),
            ),
        )
    }

    @Composable
    private fun DialogContent() {
        val (youLabel, agentLabel) = labelsState.collectAsState().value
        val heard by heardState.collectAsState()
        val answer by answerState.collectAsState()
        if (heard != null || answer != null) {
            // The dialog window is WRAP_CONTENT and NOT_TOUCHABLE, so there is nothing to scroll:
            // the block simply grows with the answer until it would cover half the screen.
            val maxHeight = (LocalConfiguration.current.screenHeightDp * DIALOG_MAX_HEIGHT_FRACTION).dp
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .heightIn(max = maxHeight)
                    .background(CardSurface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                heard?.let { DialogRow(label = youLabel, labelColor = TextMuted, text = it) }
                answer?.let { DialogRow(label = agentLabel, labelColor = AccentGreen, text = it) }
            }
        }
    }

    @Composable
    private fun DialogRow(label: String, labelColor: Color, text: String) {
        Row(verticalAlignment = Alignment.Top) {
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = labelColor)
            Spacer(Modifier.width(6.dp))
            // No line cap: a long answer must be readable in full for a driver with TTS off.
            // Ellipsis still applies at the height bound of the block above.
            Text(
                text = text,
                fontSize = 13.sp,
                color = TextPrimary,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
