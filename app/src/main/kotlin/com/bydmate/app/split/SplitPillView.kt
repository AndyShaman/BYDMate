package com.bydmate.app.split

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bydmate.app.R
import com.bydmate.app.ui.overlay.OverlayLifecycleOwner
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.TextPrimary

private const val TAG = "SplitPillView"

// ── Screen-space constants (match SplitSessionManager; on-car-adjust) ─────────

private const val SCREEN_W_PX = 1920
private const val Y_BOT_PX = 990        // bottom of the app area (excluding navbar)

// ── Circle-button window geometry (screen pixels; on-car-adjust) ──────────────
//
// Window params use raw screen pixels; Compose layout uses density-converted dp
// (via LocalDensity.toDp) so the two stay in sync across any display density.

// Square touch-target window for the circle button.
private const val CIRCLE_WIN_SIZE = 72
// Visual circle diameter (px); 8 px padding on each side inside the window.
private const val CIRCLE_DIAMETER = 56
// Hamburger icon line metrics (screen pixels; derived from circle so lines
// fit at every display density via LocalDensity.toDp inside HamburgerIcon).
private const val HAMBURGER_LINE_W_PX = 28  // line width  (~50 % of circle diameter)
private const val HAMBURGER_LINE_H_PX = 3   // line height
private const val HAMBURGER_GAP_PX = 7      // gap between lines

// Menu window (wider to fit Russian menu labels; on-car-adjust).
private const val MENU_WIN_W = 360
// circle-area height + 5 items × ~64 px + top/bottom margin; on-car-adjust.
private const val MENU_WIN_H = CIRCLE_WIN_SIZE + 64 * 5 + 48

// Picker window (full-screen).
private const val PICKER_WIN_W = WindowManager.LayoutParams.MATCH_PARENT
private const val PICKER_WIN_H = WindowManager.LayoutParams.MATCH_PARENT

// ── App entry for the picker ───────────────────────────────────────────────────

/** An installed launcher app shown in the picker grid. */
data class LauncherAppEntry(val pkg: String, val label: String, val icon: Bitmap)

// ── SplitPillView ──────────────────────────────────────────────────────────────

/**
 * Manages two TYPE_APPLICATION_OVERLAY windows:
 * 1. **Pill + menu** — small window positioned at the pane junction; resizes when menu opens.
 * 2. **Picker** — full-screen overlay shown when an app needs to be chosen.
 *
 * All public methods must be called from the main thread.
 * [SplitOverlayController] owns the lifecycle and calls [attach] / [detach].
 */
class SplitPillView(private val context: Context) {

    // Compose state read by the composables:
    private val menuVisibleState: MutableState<Boolean> = mutableStateOf(false)
    private val pickerModeState: MutableState<PickerMode?> = mutableStateOf(null)
    private val pickerTitleResState: MutableState<Int> = mutableStateOf(0)
    private val appsState: MutableState<List<LauncherAppEntry>> = mutableStateOf(emptyList())
    private val junctionXState: MutableState<Int> = mutableStateOf(1280)

    // Callbacks set by the controller:
    var onPillTap: () -> Unit = {}
    var onMenuAction: (MenuAction) -> Unit = {}
    var onAppPicked: (String) -> Unit = {}
    /** System back pressed while the picker overlay is open. */
    var onPickerBack: () -> Unit = {}

    // WindowManager refs:
    private var wm: WindowManager? = null
    private var pillComposeView: ComposeView? = null
    private var pillLifecycle: OverlayLifecycleOwner? = null
    private var pillParams: WindowManager.LayoutParams? = null

    private var pickerComposeView: ComposeView? = null
    private var pickerLifecycle: OverlayLifecycleOwner? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun attach(junctionX: Int) {
        if (pillComposeView != null) return
        junctionXState.value = junctionX
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager
        attachPillWindow(windowManager, junctionX)
    }

    fun detach() {
        hidePicker()
        pillComposeView?.let { v ->
            try { wm?.removeView(v) } catch (e: Exception) { Log.w(TAG, "removeView pill: ${e.message}") }
        }
        pillLifecycle?.onDestroy()
        pillComposeView = null
        pillLifecycle = null
        pillParams = null
        menuVisibleState.value = false
        pickerModeState.value = null
        wm = null
    }

    fun setMenuVisible(visible: Boolean) {
        menuVisibleState.value = visible
        if (visible) expandPillWindow() else collapsePillWindow()
    }

    fun setPickerMode(mode: PickerMode?) {
        pickerModeState.value = mode
        if (mode != null) showPicker() else hidePicker()
    }

    /**
     * Sets the picker header resource (which pane the choice applies to).
     * Must be called before [setPickerMode] so the header is correct on the first frame.
     * 0 = no title (no picker open).
     */
    fun setPickerTitleRes(resId: Int) {
        pickerTitleResState.value = resId
    }

    /** Currently propagated picker header resource. Internal for unit-test assertions. */
    internal val pickerTitleRes: Int get() = pickerTitleResState.value

    fun setApps(apps: List<LauncherAppEntry>) {
        appsState.value = apps
    }

    fun setJunctionX(x: Int) {
        junctionXState.value = x
        // Reposition the pill window when the narrow side switches.
        val params = pillParams ?: return
        val windowManager = wm ?: return
        val menuVisible = menuVisibleState.value
        params.x = pillWindowX(x, menuVisible)
        try { windowManager.updateViewLayout(pillComposeView ?: return, params) } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout junction: ${e.message}")
        }
    }

    // ── Pill window ────────────────────────────────────────────────────────────

    private fun attachPillWindow(wm: WindowManager, junctionX: Int) {
        val lifecycle = OverlayLifecycleOwner().also { it.onCreate() }
        pillLifecycle = lifecycle

        val params = pillLayoutParams(junctionX, menuOpen = false)
        pillParams = params

        // appLocalizedContext(): ComposeView resolves stringResource() via LocalContext which
        // inherits the View's context. ApplicationContext stays on system locale (en_US on DiLink);
        // the locale-wrapped context ensures pill and menu labels render in the user-selected language.
        // WindowManager and overlay params remain on the unmodified context.
        val compose = ComposeView(context.appLocalizedContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setContent {
                PillAndMenuContent(
                    menuVisible = menuVisibleState.value,
                    onPillTap = { this@SplitPillView.onPillTap() },
                    onMenuAction = { action -> this@SplitPillView.onMenuAction(action) },
                )
            }
        }
        pillComposeView = compose

        try {
            wm.addView(compose, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView pill: ${e.message}")
            pillLifecycle?.onDestroy()
            pillComposeView = null
            pillLifecycle = null
            pillParams = null
        }
    }

    private fun pillLayoutParams(junctionX: Int, menuOpen: Boolean): WindowManager.LayoutParams {
        val w = if (menuOpen) MENU_WIN_W else CIRCLE_WIN_SIZE
        val h = if (menuOpen) MENU_WIN_H else CIRCLE_WIN_SIZE
        return WindowManager.LayoutParams(
            w, h,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pillWindowX(junctionX, menuOpen)
            y = pillWindowY(menuOpen)
        }
    }

    private fun pillWindowX(junctionX: Int, menuOpen: Boolean): Int {
        val w = if (menuOpen) MENU_WIN_W else CIRCLE_WIN_SIZE
        return (junctionX - w / 2).coerceIn(0, SCREEN_W_PX - w)
    }

    private fun pillWindowY(menuOpen: Boolean): Int {
        val h = if (menuOpen) MENU_WIN_H else CIRCLE_WIN_SIZE
        return Y_BOT_PX - h
    }

    private fun expandPillWindow() {
        val params = pillParams ?: return
        val windowManager = wm ?: return
        val view = pillComposeView ?: return
        val x = junctionXState.value
        params.width = MENU_WIN_W
        params.height = MENU_WIN_H
        params.x = pillWindowX(x, menuOpen = true)
        params.y = pillWindowY(menuOpen = true)
        try { windowManager.updateViewLayout(view, params) } catch (e: Exception) {
            Log.w(TAG, "expandPillWindow: ${e.message}")
        }
    }

    private fun collapsePillWindow() {
        val params = pillParams ?: return
        val windowManager = wm ?: return
        val view = pillComposeView ?: return
        val x = junctionXState.value
        params.width = CIRCLE_WIN_SIZE
        params.height = CIRCLE_WIN_SIZE
        params.x = pillWindowX(x, menuOpen = false)
        params.y = pillWindowY(menuOpen = false)
        try { windowManager.updateViewLayout(view, params) } catch (e: Exception) {
            Log.w(TAG, "collapsePillWindow: ${e.message}")
        }
    }

    // ── Picker window ──────────────────────────────────────────────────────────

    private fun showPicker() {
        if (pickerComposeView != null) return
        val windowManager = wm ?: return

        val lifecycle = OverlayLifecycleOwner().also { it.onCreate() }
        pickerLifecycle = lifecycle

        // FLAG_LAYOUT_IN_SCREEN only — no FLAG_NOT_FOCUSABLE — so the overlay window can
        // receive key events (in particular KEYCODE_BACK) while the picker is open.
        val params = WindowManager.LayoutParams(
            PICKER_WIN_W, PICKER_WIN_H,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        // FOCUS_BLOCK_DESCENDANTS prevents Compose child nodes from stealing focus away from
        // the ComposeView root, ensuring setOnKeyListener reliably fires for KEYCODE_BACK
        // regardless of which grid cell or item the Compose focus system is on.
        val compose = ComposeView(context.appLocalizedContext()).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setContent {
                PickerContent(
                    pickerMode = pickerModeState.value,
                    titleRes = pickerTitleResState.value,
                    apps = appsState.value,
                    onAppPicked = { pkg -> this@SplitPillView.onAppPicked(pkg) },
                )
            }
            // Consume both DOWN and UP; act on UP to match system back convention.
            // Cancelled events are ignored so the system can reclaim back if needed.
            setOnKeyListener { _, keyCode, event ->
                if (!event.isCanceled && keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        this@SplitPillView.onPickerBack()
                    }
                    true
                } else {
                    false
                }
            }
        }
        pickerComposeView = compose

        try {
            windowManager.addView(compose, params)
            // Request focus so the overlay window receives key events; log on failure
            // so on-car debugging can detect focus routing issues early.
            val focused = compose.requestFocus()
            if (!focused) Log.w(TAG, "showPicker: requestFocus returned false — KEYCODE_BACK may not reach picker")
        } catch (e: Exception) {
            Log.e(TAG, "addView picker: ${e.message}")
            pickerLifecycle?.onDestroy()
            pickerComposeView = null
            pickerLifecycle = null
        }
    }

    private fun hidePicker() {
        pickerComposeView?.let { v ->
            try { wm?.removeView(v) } catch (e: Exception) { Log.w(TAG, "removeView picker: ${e.message}") }
        }
        pickerLifecycle?.onDestroy()
        pickerComposeView = null
        pickerLifecycle = null
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
}

// ── Composables ────────────────────────────────────────────────────────────────

/**
 * Circle-button + menu overlay composable.
 *
 * Window geometry (px) and Compose layout (dp) stay in sync through explicit density
 * conversion: `with(LocalDensity.current) { N.toDp() }` for every px-sourced dimension.
 * Never use `N.dp` for values that originated as screen pixels.
 *
 * Collapsed: square CIRCLE_WIN_SIZE × CIRCLE_WIN_SIZE window; a translucent circle with a
 * hamburger icon sits at the center. The full window area is the touch target so the drag
 * surface (entire window) is preserved at the same height as the old pill.
 * Expanded (menu open): window grows to MENU_WIN_W × MENU_WIN_H; the circle stays at the
 * bottom-center of the expanded window and the menu list opens above it.
 */
@Composable
private fun PillAndMenuContent(
    menuVisible: Boolean,
    onPillTap: () -> Unit,
    onMenuAction: (MenuAction) -> Unit,
) {
    val density = LocalDensity.current
    // Convert window pixel dimensions to Compose dp so Compose layout matches window bounds.
    val circleWinSizeDp = with(density) { CIRCLE_WIN_SIZE.toDp() }
    val circleDiameterDp = with(density) { CIRCLE_DIAMETER.toDp() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Menu opens upward from the circle, within the expanded window above the circle area.
        // On-car-adjust: verify item heights if labels truncate on the actual DiLink density.
        if (menuVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .background(CardSurface, RoundedCornerShape(10.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp)),
            ) {
                MenuItem(stringResource(R.string.split_menu_mirror), onClick = { onMenuAction(MenuAction.MIRROR) })
                MenuDivider()
                MenuItem(stringResource(R.string.split_menu_swap), onClick = { onMenuAction(MenuAction.SWAP) })
                MenuDivider()
                MenuItem(stringResource(R.string.split_menu_pick_left), onClick = { onMenuAction(MenuAction.PICK_LEFT) })
                MenuDivider()
                MenuItem(stringResource(R.string.split_menu_pick_right), onClick = { onMenuAction(MenuAction.PICK_RIGHT) })
                MenuDivider()
                MenuItem(
                    label = stringResource(R.string.split_menu_exit),
                    textColor = Color(0xFFEF4444),
                    onClick = { onMenuAction(MenuAction.EXIT) },
                )
            }
        }

        // Circle touch target: CIRCLE_WIN_SIZE × CIRCLE_WIN_SIZE px at bottom center.
        // Density conversion keeps px window size and dp Compose size aligned.
        Box(
            modifier = Modifier
                .size(circleWinSizeDp)
                .align(Alignment.BottomCenter)
                .clickable { onPillTap() },
            contentAlignment = Alignment.Center,
        ) {
            // Visual circle: translucent white with hamburger icon inside.
            Box(
                modifier = Modifier
                    .size(circleDiameterDp)
                    .background(
                        Color.White.copy(alpha = 0.25f),
                        RoundedCornerShape(circleDiameterDp / 2),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                HamburgerIcon()
            }
        }
    }
}

/**
 * Three horizontal translucent-white lines (hamburger icon), sized from screen-pixel
 * constants so they fit correctly inside the circle at every display density.
 * All dimensions are derived from [HAMBURGER_LINE_W_PX] / [HAMBURGER_LINE_H_PX] /
 * [HAMBURGER_GAP_PX] via [LocalDensity.toDp], consistent with the px-invariant
 * maintained throughout this file.
 */
@Composable
private fun HamburgerIcon() {
    val density = LocalDensity.current
    val lineWidthDp = with(density) { HAMBURGER_LINE_W_PX.toDp() }
    val lineHeightDp = with(density) { HAMBURGER_LINE_H_PX.toDp() }
    val gapDp = with(density) { HAMBURGER_GAP_PX.toDp() }
    Column(
        verticalArrangement = Arrangement.spacedBy(gapDp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .width(lineWidthDp)
                    .height(lineHeightDp)
                    .background(Color.White.copy(alpha = 0.70f)),
            )
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    textColor: Color = TextPrimary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = textColor,
        )
    }
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CardBorder),
    )
}

/**
 * Full-screen picker: header + grid of launcher apps, filtered/sorted by the controller.
 *
 * [titleRes] names the pane the choice applies to; it is computed by the controller
 * (pickerTitleRes) because only the session knows which side the narrow pane is on.
 *
 * There is no explicit cancel button — the system back button is the only dismiss affordance
 * (handled by the overlay window's key listener, not by this composable).
 */
@Composable
private fun PickerContent(
    pickerMode: PickerMode?,
    titleRes: Int,
    apps: List<LauncherAppEntry>,
    onAppPicked: (String) -> Unit,
) {
    if (pickerMode == null) return

    // Fall back to the generic header if the title was not propagated (defensive: 0 = unset).
    val title = stringResource(if (titleRes != 0) titleRes else R.string.split_picker_title_change)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDark.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            // On-car-adjust: column count / icon size for the actual screen density.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(apps, key = { it.pkg }) { entry ->
                    AppCell(entry = entry, onPicked = { onAppPicked(entry.pkg) })
                }
            }
        }
    }
}

@Composable
private fun AppCell(entry: LauncherAppEntry, onPicked: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onPicked() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            bitmap = entry.icon.asImageBitmap(),
            contentDescription = entry.label,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.label,
            fontSize = 11.sp,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Bitmap helper ──────────────────────────────────────────────────────────────

/** Renders a [Drawable] to a [Bitmap] scaled to [w]×[h] pixels. */
internal fun drawableToBitmap(drawable: Drawable, w: Int, h: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
