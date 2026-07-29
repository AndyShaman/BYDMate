package com.bydmate.app.split

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "SplitOverlayCtrl"
private const val MENU_TICK_MS = 250L      // menu auto-hide timer interval
private const val ICON_SIZE_PX = 64        // launcher icon size for picker grid (on-car-adjust)
private const val MAX_FILTER_CONCURRENT = 4 // max parallel isAppSplitSupported binder calls

/**
 * Hilt singleton that implements [SplitBackdrop] and orchestrates the full
 * split-screen overlay stack: backdrop activity, pill+menu, and picker.
 *
 * Lifecycle contract:
 *   - [show] / [hide]: called by [SplitSessionManager] to show/hide the backdrop.
 *   - [start]: called once from the application scope (BYDMateApp.onCreate) to wire
 *              state/event observers. Mirrors the WidgetController init call site.
 *   - [showFirstPairPicker]: public API for Task 5 (split widget / settings entry point).
 */
@Singleton
class SplitOverlayController @Inject constructor(
    @ApplicationContext private val context: Context,
    // Provider breaks the circular dep:
    // SplitSessionManager → SplitBackdrop → SplitOverlayController → SplitSessionManager
    private val sessionManagerProvider: Provider<SplitSessionManager>,
    private val helperClient: HelperClient,
    private val splitPrefs: SplitPreferences,
) : SplitBackdrop {

    // Resolved lazily so Hilt can construct the object graph without a cycle.
    private val sessionManager: SplitSessionManager get() = sessionManagerProvider.get()

    // Pure state machine — no Android deps. Internal for test assertions on pickerMode.
    internal val logic = SplitMenuLogic()

    // Overlay view: null when not attached (Idle state or before first Active).
    // Internal (read-only) for unit-test assertions on what was propagated to the view.
    internal var pillView: SplitPillView? = null
        private set

    // App list cache for the picker (populated once per session, cleared on SessionEnded/Idle).
    @Volatile private var supportCache: Map<String, Boolean?> = emptyMap()
    @Volatile private var launcherApps: List<LauncherAppEntry> = emptyList()
    @Volatile private var appListReady: Boolean = false

    // Held so observers and the menu timer can be cancelled if needed.
    private lateinit var controllerScope: CoroutineScope

    // Job for the menu auto-hide timer; non-null only while the menu is visible.
    // @VisibleForTesting: accessed in relocale timer-restart tests.
    internal var menuTimerJob: kotlinx.coroutines.Job? = null
        private set

    // Receiver registered only while the pill is Idle-attached (showFirstPairPicker path).
    // Null at all other times. Unregistered in tearDownPill and when the session goes Active.
    // Catches ACTION_CLOSE_SYSTEM_DIALOGS (Home press, status-bar expansion, etc.) so that
    // the first-pair picker overlay does not float indefinitely above the launcher.
    // Android 12 (API 31) added a restriction on *sending* ACTION_CLOSE_SYSTEM_DIALOGS,
    // keyed on the *sender's* targetSdk. We are a receiver; the sender is the system
    // (StatusBarManagerService / PhoneWindowManager), which is never restricted regardless
    // of our own targetSdkVersion. A future targetSdk bump does not affect delivery.
    private var closeSystemDialogsReceiver: BroadcastReceiver? = null

    // True while a user-visible activity of OUR app is in the foreground. Main thread only
    // (activity lifecycle callbacks and the state observer both run there).
    private var ownAppForegrounded = false

    // ── SplitBackdrop ─────────────────────────────────────────────────────────

    /**
     * Starts the backdrop activity and suspends until it reaches onResume (or 2 s timeout).
     *
     * The deferred is registered BEFORE startActivity so the completion cannot be missed even
     * on a pathological fast-resume. On singleTask re-start the system reorders the existing
     * task to front and fires onResume there too — both paths complete the same deferred.
     *
     * @return true when the backdrop confirmed resumed (z-order safe to launch windows),
     *         false on start failure or timeout (caller proceeds in degraded mode).
     */
    override suspend fun show(): Boolean {
        // Advance the generation epoch so any finish() posted by a prior finishInstance()
        // call is a no-op when its lambda runs. Must happen before registering the deferred
        // so onCreate/onNewIntent captures the correct (new) generation.
        SplitStageActivity.nextGeneration()
        val deferred = CompletableDeferred<Unit>()
        // Write before startActivity so onResume (on any thread ordering) always finds it.
        SplitStageActivity.resumedDeferred = deferred
        val intent = Intent(context, SplitStageActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "show: failed to start backdrop: ${e.message}")
            return false
        }
        return try {
            withTimeout(2000L) { deferred.await() }
            true
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "show: backdrop resume timed out — proceeding without z-order guarantee")
            false
        }
    }

    override fun hide() {
        SplitStageActivity.finishInstance()
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Opens the first-pair app picker (Task 5 entry point): step 1 = wide/2/3 app.
     * Safe to call before any session is active (does not require Active state).
     * No-op when the split feature is disabled in settings.
     */
    fun showFirstPairPicker() {
        if (!splitPrefs.isFeatureEnabled()) return
        logic.showFirstPairPicker()
        // Attach pill BEFORE syncLogicToView so the view is non-null when sync runs.
        // (When called from Idle there is no Active session, so pillView is null here.)
        ensurePillAttached(junctionXFromCurrentState())
        // Register a Close-System-Dialogs receiver for this Idle-attached pill.
        // Without it, a Home press while the picker overlay is open leaves both windows
        // (the circle button and the full-screen picker) floating over the launcher
        // indefinitely, with the focusable MATCH_PARENT picker eating all touches.
        // tearDownPillIfIdle() is safe here because state is Idle by definition.
        registerCloseSystemDialogsReceiver()
        syncLogicToView()
        if (!appListReady) {
            controllerScope.launch { loadAppsIfNeeded() }
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    /**
     * Foreground edges of our OWN app, driven by the same ForegroundActivityCounter seam that
     * hides the floating widget (390-1) — so `SplitStageActivity` (the pane backdrop, not a
     * screen the user opened) is already excluded upstream and never moves this flag.
     *
     * On-car 390-4: opening BYDMate over a live split left the pill floating above our own
     * fullscreen UI. The pill controls OTHER apps' panes, so it has no business there.
     *
     * Only the pill WINDOW is removed — the session keeps running, which is what makes the
     * 390-2 flow work: Back → backdrop resurfaces → panes are reasserted, and the 1→0 edge
     * of this same seam brings the pill back. Session teardown (F-3) still removes the pill
     * independently of this flag; a pill hidden here is not resurrected for a dead session
     * because the restore is gated on [SplitSessionState.Active].
     */
    fun setOwnAppForegrounded(foreground: Boolean) {
        ownAppForegrounded = foreground
        if (foreground) {
            tearDownPill()
        } else if (sessionManager.state.value is SplitSessionState.Active) {
            ensurePillAttached(junctionXFromCurrentState())
        }
    }

    /**
     * Starts the state/event observers.
     * Call once from the application scope in [BYDMateApp.onCreate].
     * The menu auto-hide timer starts on demand when the pill is tapped (Fix 6).
     */
    fun start(scope: CoroutineScope) {
        controllerScope = scope
        scope.launch(Dispatchers.Main) { observeSessionState() }
        scope.launch(Dispatchers.Main) { observeSessionEvents() }
    }

    // ── Session state observer ────────────────────────────────────────────────

    private suspend fun observeSessionState() {
        sessionManager.state.collect { state ->
            when (state) {
                is SplitSessionState.Idle -> {
                    // Session over: remove pill, reset everything.
                    tearDownPill()
                    clearAppCache()
                }
                is SplitSessionState.Active -> {
                    val junctionX = junctionXForSide(state.pair.narrowSide)
                    // Session is now Active: the pill is owned by the session, not the
                    // first-pair picker. Unregister the Idle-picker receiver if it was armed.
                    unregisterCloseSystemDialogsReceiver()
                    // Keep pill at the correct junction when narrowSide changes. While our own
                    // app is in the foreground the pill stays away (390-4): a state emission
                    // there (mirror, pane change) must not float it back over our UI.
                    if (!ownAppForegrounded) {
                        pillView?.setJunctionX(junctionX) ?: ensurePillAttached(junctionX)
                    }
                    // mirror() flips narrowSide while a picker may be open (the pill menu or the
                    // voice agent can mirror at any time). The ChangePane header names a screen
                    // side, so it must be recomputed from the new state — otherwise the picker
                    // keeps saying «left» for a pane that has just moved to the right.
                    if (logic.pickerMode != null) syncLogicToView()
                    // Load app list for the picker (once per session).
                    if (!appListReady) {
                        controllerScope.launch { loadAppsIfNeeded() }
                    }
                }
            }
        }
    }

    // ── Session event observer ────────────────────────────────────────────────

    private suspend fun observeSessionEvents() {
        sessionManager.events.collect { event ->
            when (event) {
                is SplitEvent.PaneClosed -> {
                    // Capture the closed package from the current session state before opening
                    // the picker, so system back can restore the app into its pane.
                    val activeState = sessionManager.state.value as? SplitSessionState.Active
                    val closedPkg = if (event.pane == Pane.NARROW) {
                        activeState?.pair?.narrowPkg
                    } else {
                        activeState?.pair?.widePkg
                    }
                    logic.openPickerForPane(event.pane, closedPkg)
                    syncLogicToView()
                }
                is SplitEvent.SessionEnded -> {
                    // Backdrop is already hidden by the manager; remove pill and reset.
                    logic.dismissPicker()
                    tearDownPill()
                    clearAppCache()
                }
            }
        }
    }

    // ── Menu auto-hide timer ──────────────────────────────────────────────────

    /**
     * Auto-hide loop: runs only while the menu is visible. Exits naturally once
     * the deadline has passed and [SplitMenuLogic.onTimerTick] hides the menu.
     * Started on pill tap; cancelled in [tearDownPill].
     */
    private suspend fun menuTimerLoop() {
        while (logic.isMenuVisible) {
            delay(MENU_TICK_MS)
            val hidden = logic.onTimerTick()
            if (hidden) {
                withContext(Dispatchers.Main) { pillView?.setMenuVisible(false) }
                break
            }
        }
        menuTimerJob = null
    }

    // ── Pill overlay lifecycle ────────────────────────────────────────────────

    private fun ensurePillAttached(junctionX: Int) {
        if (pillView != null) return
        val view = SplitPillView(context)
        view.onPillTap = { handlePillTap() }
        view.onMenuAction = { action -> handleMenuAction(action) }
        view.onAppPicked = { pkg -> handleAppPicked(pkg) }
        view.onPickerBack = { handlePickerBack() }
        view.attach(junctionX)
        view.setApps(launcherApps)
        pillView = view
    }

    private fun tearDownPill() {
        menuTimerJob?.cancel()
        menuTimerJob = null
        unregisterCloseSystemDialogsReceiver()
        pillView?.detach()
        pillView = null
        logic.dismissPicker()
    }

    /**
     * Tears down the pill if the session is currently Idle.
     * Called after first-pair picker cancel and after StartSplit failure so a pill
     * attached solely for the picker does not float over other apps with no session.
     */
    private fun tearDownPillIfIdle() {
        if (sessionManager.state.value is SplitSessionState.Idle) tearDownPill()
    }

    // ── User-interaction handlers (run on main thread via ComposeView callbacks) ──

    private fun handlePillTap() {
        logic.onPillTap()
        pillView?.setMenuVisible(logic.isMenuVisible)
        // Start auto-hide timer on demand (Fix 6: timer only runs while menu is visible).
        if (menuTimerJob?.isActive != true) {
            menuTimerJob = controllerScope.launch { menuTimerLoop() }
        }
    }

    private fun handleMenuAction(action: MenuAction) {
        val state = sessionManager.state.value as? SplitSessionState.Active
        val narrowSide = state?.pair?.narrowSide ?: SplitSide.RIGHT
        val cmd = logic.onMenuAction(action, narrowSide)
        pillView?.setMenuVisible(false)
        // When PICK_LEFT / PICK_RIGHT transitions pickerMode to ChangePane, sync the view so
        // the picker overlay becomes visible with the current-session exclusion filter applied.
        // Without this sync the picker mode is set in logic but never propagated to the view.
        if (logic.pickerMode != null) {
            syncLogicToView()
            if (!appListReady) controllerScope.launch { loadAppsIfNeeded() }
        }
        executeCommand(cmd)
    }

    /**
     * User tapped an app in the picker grid.
     *
     * For [PickerMode.ChangePane], blocks only the OTHER pane's live package so the user
     * cannot duplicate a running app. For pane-closed pickers ([PickerMode.ChangePane.closedPkg]
     * != null) the closed app is explicitly allowed — the session still has the closed pkg in
     * the pair, but tapping it should re-launch it into its pane, not be swallowed.
     * Internal for unit-test assertions.
     */
    internal fun handleAppPicked(pkg: String) {
        val session = sessionManager.state.value as? SplitSessionState.Active
        if (session != null) {
            val mode = logic.pickerMode as? PickerMode.ChangePane
            if (mode != null) {
                // W6-F2 second gate: the list filter can be one tick stale (advisory read), so a
                // departed app may still be tappable. Swallow the tap instead of painting a black
                // pane. See buildExcludedPackages.
                if (pkg in sessionManager.departedPanePkgs()) return
                val otherPkg = if (mode.pane == Pane.NARROW) session.pair.widePkg else session.pair.narrowPkg
                if (mode.closedPkg != null) {
                    // Pane-closed: only the other (still-running) pane is off-limits.
                    if (pkg == otherPkg) return
                } else {
                    // Voluntary: neither app can be duplicated.
                    if (pkg == session.pair.narrowPkg || pkg == session.pair.widePkg) return
                }
            }
        }
        val cmd = logic.onAppPicked(pkg)
        syncLogicToView()
        executeCommand(cmd)
    }

    /**
     * System back was pressed while the picker overlay was open.
     *
     * - Pane-closed picker ([PickerMode.ChangePane.closedPkg] != null): restores the closed app
     *   into its pane. The picker stays visible during the async restore attempt; on failure it
     *   remains open so the user can retry or choose a different app.
     * - Voluntary picker or first-pair picker: dismisses the picker and leaves the session unchanged.
     *
     * Internal for unit-test assertions.
     */
    internal fun handlePickerBack() {
        val cmd = logic.onPickerBack()  // clears pickerMode
        if (cmd is MenuCmd.ChangeApp) {
            // Re-open the picker immediately in logic so the view keeps it visible during
            // the async restore — avoids a flash-close-then-reopen on failure.
            logic.openPickerForPane(cmd.pane, cmd.pkg)
            syncLogicToView()
            if (!appListReady) controllerScope.launch { loadAppsIfNeeded() }
            controllerScope.launch {
                val result = sessionManager.changeApp(cmd.pane, cmd.pkg)
                withContext(Dispatchers.Main) {
                    if (result == SplitStartResult.OK) {
                        logic.dismissPicker()
                        syncLogicToView()
                    }
                    // On failure: picker stays open (pickerMode still set with closedPkg) so
                    // the user can retry or choose a different app. No extra action needed.
                }
            }
        } else {
            syncLogicToView()
            tearDownPillIfIdle()
        }
    }

    // ── Command dispatch ──────────────────────────────────────────────────────

    private fun executeCommand(cmd: MenuCmd) {
        when (cmd) {
            is MenuCmd.None -> Unit
            is MenuCmd.Mirror -> controllerScope.launch { sessionManager.mirror() }
            is MenuCmd.SwapApps -> controllerScope.launch { sessionManager.swapApps() }
            is MenuCmd.ChangeApp -> controllerScope.launch {
                sessionManager.changeApp(cmd.pane, cmd.pkg)
            }
            is MenuCmd.StartSplit -> controllerScope.launch {
                val result = sessionManager.start(cmd.pair)
                // If start failed the state stays Idle; the pill was attached only for the
                // first-pair picker, so clean it up on the main thread.
                if (result != SplitStartResult.OK) {
                    withContext(Dispatchers.Main) { tearDownPillIfIdle() }
                }
            }
            is MenuCmd.Exit -> controllerScope.launch { sessionManager.exit() }
        }
    }

    // ── Sync logic state → view ───────────────────────────────────────────────

    private fun syncLogicToView() {
        val view = pillView ?: return
        view.setMenuVisible(logic.isMenuVisible)
        // Header must name the target pane; set before setPickerMode so the picker window
        // is created with the correct title already in state.
        val narrowSide = (sessionManager.state.value as? SplitSessionState.Active)?.pair?.narrowSide
            ?: SplitSide.RIGHT
        view.setPickerTitleRes(pickerTitleRes(logic.pickerMode, narrowSide))
        view.setPickerMode(logic.pickerMode)
        // Update the displayed app list with per-mode exclusions (Fix 3: same-app guard in data).
        val excluded = buildExcludedPackages(logic.pickerMode)
        view.setApps(if (excluded.isEmpty()) launcherApps else launcherApps.filter { it.pkg !in excluded })
    }

    /**
     * Packages to exclude from the picker list for the given mode.
     * - [PickerMode.FirstPairStep2]: exclude the wide app chosen in step 1.
     * - [PickerMode.ChangePane] voluntary (closedPkg == null): exclude both current-session packages
     *   so the user cannot pick an app already occupying a pane.
     * - [PickerMode.ChangePane] pane-closed (closedPkg != null): exclude only the OTHER pane's app;
     *   the closed app must remain selectable so the user can reopen it.
     * Internal for test assertions.
     */
    internal fun buildExcludedPackages(mode: PickerMode?): Set<String> = when (mode) {
        is PickerMode.FirstPairStep2 -> setOf(mode.widePkg)
        is PickerMode.ChangePane -> {
            val s = sessionManager.state.value as? SplitSessionState.Active
            // W6-F2: an app whose pane departed to the cluster owns a single task that lives
            // there. Picking it paints a black pane and does not bring it back, so it is hidden
            // until the watchdog sees the task on display 0 again. This also covers the
            // pane-closed picker: a closed-BECAUSE-departed app must stay excluded, while a
            // closed-and-still-home app remains selectable as before.
            val departed = sessionManager.departedPanePkgs()
            if (mode.closedPkg != null) {
                // Pane-closed: only the other (still-running) pane's app is off-limits.
                if (s == null) departed
                else {
                    val otherPkg = if (mode.pane == Pane.NARROW) s.pair.widePkg else s.pair.narrowPkg
                    departed + otherPkg
                }
            } else {
                // Voluntary: exclude both session apps (neither should be doubled up).
                s?.let { setOf(it.pair.narrowPkg, it.pair.widePkg) } ?: departed
            }
        }
        else -> emptySet()
    }

    // ── App loader ────────────────────────────────────────────────────────────

    /**
     * Loads all launcher apps from PackageManager, filters by [helperClient.isAppSplitSupported]
     * (fail-open: null = keep, false = remove), and caches the result.
     * Runs on IO dispatcher; updates Compose state on Main when done.
     */
    private suspend fun loadAppsIfNeeded() = withContext(Dispatchers.IO) {
        if (appListReady) return@withContext
        try {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            @Suppress("QueryPermissionsNeeded")
            val resolved = pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName }

            // Query split support concurrently (binder calls), cache results.
            val pkgs = resolved.map { it.activityInfo.packageName }.distinct()
            val supportMap = buildSupportCache(pkgs)
            supportCache = supportMap

            // Build LauncherAppEntry list, filtered and sorted.
            val entries = resolved
                .distinctBy { it.activityInfo.packageName }
                .filter { supportMap[it.activityInfo.packageName] != false }
                .map { res ->
                    val pkg = res.activityInfo.packageName
                    val label = res.loadLabel(pm).toString()
                    val iconDrawable = try { res.loadIcon(pm) } catch (_: Exception) {
                        pm.defaultActivityIcon
                    }
                    LauncherAppEntry(pkg, label, drawableToBitmap(iconDrawable, ICON_SIZE_PX, ICON_SIZE_PX))
                }
                .sortedBy { it.label.lowercase() }

            launcherApps = entries
            appListReady = true

            withContext(Dispatchers.Main) {
                pillView?.setApps(entries)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadAppsIfNeeded failed: ${e.message}")
        }
    }

    private suspend fun buildSupportCache(pkgs: List<String>): Map<String, Boolean?> {
        val semaphore = Semaphore(MAX_FILTER_CONCURRENT)
        return coroutineScope {
            pkgs.map { pkg ->
                async {
                    semaphore.withPermit {
                        pkg to try {
                            helperClient.isAppSplitSupported(pkg)
                        } catch (_: Exception) {
                            null // fail-open on exception
                        }
                    }
                }
            }.awaitAll().toMap()
        }
    }

    private fun clearAppCache() {
        supportCache = emptyMap()
        launcherApps = emptyList()
        appListReady = false
    }

    // ── Idle-picker teardown receiver ─────────────────────────────────────────

    /**
     * Registers a [BroadcastReceiver] for [Intent.ACTION_CLOSE_SYSTEM_DIALOGS] (fired on
     * Home press, status-bar expansion, and similar system-driven navigation events).
     *
     * Only called when [showFirstPairPicker] attaches the pill while the session is Idle.
     * In that state, neither the Idle branch of [observeSessionState] (StateFlow won't
     * re-emit an already-Idle value) nor the normal session-exit paths can reach
     * [tearDownPill], so the first-pair picker overlay would float indefinitely without
     * this receiver.
     *
     * Safe to call repeatedly; re-entry guard ensures at most one receiver is live.
     */
    // ACTION_CLOSE_SYSTEM_DIALOGS is @Deprecated for app SENDERS (API 31+). We are a RECEIVER
    // of the system broadcast (sent by StatusBarManagerService / PhoneWindowManager), which is
    // never subject to the sender-side restriction. The @Suppress silences the IDE/lint warning
    // that flags the constant reference regardless of sender vs receiver role.
    @Suppress("DEPRECATION")
    private fun registerCloseSystemDialogsReceiver() {
        if (closeSystemDialogsReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                    // Log the reason extra to diagnose on-car: confirms the ROM broadcasts
                    // on Home, reveals whether shade dismissal reaches us, and catches
                    // backdrop.show() activity-start trips. Permanent — needed for any
                    // on-car logcat pass with the first-pair picker open.
                    Log.d(TAG, "closeSystemDialogs: reason=${intent.getStringExtra("reason")}")
                    tearDownPillIfIdle()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        closeSystemDialogsReceiver = receiver
    }

    /** Unregisters and clears the Close-System-Dialogs receiver. No-op if not registered. */
    @Suppress("DEPRECATION")
    private fun unregisterCloseSystemDialogsReceiver() {
        closeSystemDialogsReceiver?.let { receiver ->
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {
                Log.w(TAG, "unregisterCloseSystemDialogs: ${e.message}")
            }
        }
        closeSystemDialogsReceiver = null
    }

    // ── Locale re-attach ──────────────────────────────────────────────────────

    /**
     * Tears down the pill and immediately re-attaches it so the new locale is picked up.
     * Called by [com.bydmate.app.ui.widget.WidgetController.relocale] when the user changes
     * the app language in Settings. No-op when no pill is currently attached.
     *
     * [tearDownPill] nulls [pillView]; [ensurePillAttached] then creates a new [SplitPillView]
     * whose [ComposeView] is wrapped with the freshly-set [appLocalizedContext].
     */
    fun relocale() {
        if (pillView == null) return
        val junctionX = junctionXFromCurrentState()
        // D-4: snapshot menu state before teardown. tearDownPill() cancels menuTimerJob and
        // calls logic.dismissPicker() (clears pickerMode), but does NOT clear logic.isMenuVisible.
        val wasMenuVisible = logic.isMenuVisible
        tearDownPill()
        ensurePillAttached(junctionX)
        syncLogicToView()
        // Restart the auto-hide timer if the menu was visible before the locale change.
        // Without this, the pill is visually correct (syncLogicToView propagates isMenuVisible)
        // but the timer that was cancelled in tearDownPill is never restarted — the menu
        // freezes open indefinitely until the user taps the pill again.
        // Picker was open: UX-acceptable to leave it closed after relocale — language changes
        // are rare one-off operations and the user can reopen the picker with one tap.
        if (wasMenuVisible && menuTimerJob?.isActive != true) {
            menuTimerJob = controllerScope.launch { menuTimerLoop() }
        }
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private fun junctionXFromCurrentState(): Int {
        val active = sessionManager.state.value as? SplitSessionState.Active
        return if (active != null) junctionXForSide(active.pair.narrowSide)
        else X_JUNCTION_NARROW_RIGHT
    }

    companion object {
        // Mirror of SplitSessionManager constants (on-car-adjust).
        private const val X_JUNCTION_NARROW_RIGHT = 1280
        private const val X_JUNCTION_NARROW_LEFT = 640

        internal fun junctionXForSide(side: SplitSide): Int = when (side) {
            SplitSide.RIGHT -> X_JUNCTION_NARROW_RIGHT
            SplitSide.LEFT -> X_JUNCTION_NARROW_LEFT
        }
    }
}
