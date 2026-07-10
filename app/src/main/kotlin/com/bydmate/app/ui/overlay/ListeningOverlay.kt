package com.bydmate.app.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
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
 */
object ListeningOverlay {

    private const val TAG = "ListeningOverlay"
    // Distance from the top edge to the pill; below the DiLink status bar so the pill is not clipped.
    internal const val TOP_MARGIN_DP = 56
    // Gap between the pill window and the dialog window below it.
    internal const val PILL_OFFSET_DP = 48
    // SharedPreferences the persisted orb position lives in (shared with the voice feature).
    private const val PREFS_NAME = "voice"
    private const val KEY_ORB_X_ABS = "orb_x_abs"
    private const val KEY_ORB_Y = "orb_y"
    // Sentinel for "no saved y yet": fall back to TOP_MARGIN_DP. 0 is a legitimate saved value, so
    // it cannot be the default.
    private const val ORB_Y_UNSET = -1
    // Sentinel for "no saved x yet". Int.MIN_VALUE can never be a legitimate saved left-edge offset.
    private const val ORB_X_UNSET = Int.MIN_VALUE

    private val textState = MutableStateFlow("")
    private val heardState = MutableStateFlow<String?>(null)   // "Ты: …" row text (no label)
    private val answerState = MutableStateFlow<String?>(null)  // "Агент: …" row text (no label)

    // Test-visible reads of the dialog state; the flows themselves stay private so only the three
    // mutators below can change them.
    internal val heardText: String? get() = heardState.value
    internal val answerText: String? get() = answerState.value

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
        private val view: ComposeView,
        private val owner: OverlayLifecycleOwner,
    ) : OverlayHandle {
        override fun destroy() {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "hide failed: ${e.message}")
            }
            owner.onDestroy()
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
    internal var permissionCheck: (Context) -> Boolean = { context -> canShow(context) }

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
                active = attachWindow(context, initial)
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

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun realAttach(context: Context, initial: String): OverlayHandle {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pillOffsetPx = (PILL_OFFSET_DP * density).toInt()

        val screenW = context.resources.displayMetrics.widthPixels
        val savedX = prefs.getInt(KEY_ORB_X_ABS, ORB_X_UNSET)
        // Rough centered estimate until the first layout pass reports the real pill width below.
        val startX = if (savedX == ORB_X_UNSET) screenW / 2 - (90 * density).toInt() else savedX
        val savedY = prefs.getInt(KEY_ORB_Y, ORB_Y_UNSET)
        val startY = if (savedY == ORB_Y_UNSET) (TOP_MARGIN_DP * density).toInt() else savedY

        val youLabel = context.getString(R.string.orb_you)
        val agentLabel = context.getString(R.string.orb_agent)

        // Pill window: touchable so it can be dragged; NOT_TOUCH_MODAL lets touches outside the pill
        // still reach whatever is behind the overlay.
        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        // Dialog window: NOT_TOUCHABLE so touches pass straight through the text; it just follows the
        // pill's x and sits a fixed gap below it.
        val dialogParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY + pillOffsetPx
        }

        val pillOwner = OverlayLifecycleOwner().also { it.onCreate() }
        val dialogOwner = OverlayLifecycleOwner().also { it.onCreate() }
        val pillView = ComposeView(context)
        val dialogView = ComposeView(context)

        // Default centering after the first layout pass (only when there is no saved position).
        if (savedX == ORB_X_UNSET) {
            pillView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, l: Int, t: Int, r: Int, b: Int,
                    ol: Int, ot: Int, orr: Int, ob: Int,
                ) {
                    if (v.width == 0) return
                    v.removeOnLayoutChangeListener(this)
                    pillParams.x = (screenW - v.width) / 2
                    dialogParams.x = pillParams.x
                    runCatching {
                        wm.updateViewLayout(pillView, pillParams)
                        wm.updateViewLayout(dialogView, dialogParams)
                    }
                }
            })
        }

        // Gravity is TOP|START, so x is an absolute offset from the left edge. With the old
        // TOP|CENTER_HORIZONTAL anchor a WRAP_CONTENT window re-centered itself whenever its
        // content width changed, so the pill/dialog visibly jumped when the "Ты:/Агент:" text
        // appeared (field defect APK 336). A start anchor keeps the left edge fixed instead.
        val onDrag: (Int, Int) -> Unit = { dx, dy ->
            pillParams.x += dx
            pillParams.y += dy
            dialogParams.x = pillParams.x
            dialogParams.y = pillParams.y + pillOffsetPx
            try {
                wm.updateViewLayout(pillView, pillParams)
                wm.updateViewLayout(dialogView, dialogParams)
            } catch (e: Exception) {
                Log.w(TAG, "drag updateViewLayout failed: ${e.message}")
            }
        }
        // Persist only on drag end (not per frame) to avoid a SharedPreferences write every gesture tick.
        val onDragEnd: () -> Unit = {
            prefs.edit()
                .putInt(KEY_ORB_X_ABS, pillParams.x)
                .putInt(KEY_ORB_Y, pillParams.y)
                .apply()
        }

        pillView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(pillOwner)
            setViewTreeSavedStateRegistryOwner(pillOwner)
            setContent { PillContent(onDrag, onDragEnd) }
        }
        dialogView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(dialogOwner)
            setViewTreeSavedStateRegistryOwner(dialogOwner)
            setContent { DialogContent(youLabel, agentLabel) }
        }

        wm.addView(pillView, pillParams)
        wm.addView(dialogView, dialogParams)

        return CompositeOverlayHandle(
            listOf(
                RealOverlayHandle(wm, pillView, pillOwner),
                RealOverlayHandle(wm, dialogView, dialogOwner),
            ),
        )
    }

    @Composable
    private fun PillContent(onDrag: (Int, Int) -> Unit, onDragEnd: () -> Unit) {
        val text by textState.collectAsState()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(CardSurface, RoundedCornerShape(20.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                    ) { _, drag -> onDrag(drag.x.toInt(), drag.y.toInt()) }
                },
        ) {
            Icon(imageVector = Icons.Outlined.Mic, contentDescription = null, tint = AccentGreen)
            Spacer(Modifier.width(8.dp))
            Text(text = text, fontSize = 14.sp, color = TextPrimary)
        }
    }

    @Composable
    private fun DialogContent(youLabel: String, agentLabel: String) {
        val heard by heardState.collectAsState()
        val answer by answerState.collectAsState()
        if (heard != null || answer != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .widthIn(max = 480.dp)
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
            Text(
                text = text,
                fontSize = 13.sp,
                color = TextPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
