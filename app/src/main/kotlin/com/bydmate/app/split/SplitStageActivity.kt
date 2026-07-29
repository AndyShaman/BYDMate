package com.bydmate.app.split

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.TextMuted
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Whether a resume of the backdrop means "it resurfaced above the panes" (hotfix 390-2).
 *
 * Only a stopped→resumed transition counts: the backdrop is stopped when a fullscreen Activity of
 * our package covers it, and coming back from that is exactly the case where ATMS leaves the
 * backdrop's task above the panes. The first resume of a fresh session is not a resurface, and a
 * healthy split never stops the backdrop, so the multi-resume ticks of a running session do not
 * reach here. [sessionActive] is the second gate — with no session there is nothing to re-raise.
 */
internal fun shouldReassertPanes(wasStopped: Boolean, sessionActive: Boolean): Boolean =
    wasStopped && sessionActive

/**
 * Fullscreen dark backdrop for a split-screen session.
 *
 * Shows NavyDark with a tiny muted "BYDMate" watermark — fills the gap between
 * the two freeform app windows and prevents the home wallpaper from bleeding through.
 *
 * Characteristics:
 * - excludeFromRecents / singleTask: prevents it from cluttering the recents tray.
 * - Not exported: started only by [SplitOverlayController.show].
 * - Back press suppressed: split is exited exclusively via the pill menu.
 *
 * Lifecycle: [SplitOverlayController.show] starts it; [SplitOverlayController.hide]
 * calls [finishInstance] to finish it.
 */
@AndroidEntryPoint
class SplitStageActivity : ComponentActivity() {

    @Inject lateinit var sessionManager: SplitSessionManager

    // Set in onStop, cleared once the resulting resume has been handled: distinguishes a
    // resurface (something covered us and went away) from the first resume of a session.
    private var wasStopped = false

    // Captures the show() generation this instance was (re)started for.
    // Set in onCreate (cold start) and onNewIntent (singleTask reorder-to-front).
    // Read in onResume to guard against completing a stale or unrelated deferred.
    private var myGeneration = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen: cover status bar chrome behind the backdrop. On-car-adjust: verify
        // there is no chrome bleed on the actual DiLink head unit after launch.
        // FLAG_FULLSCREEN is deprecated in API 30+, but targetSdk=29 here — keep it.
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Register the instance so hide() can finish this exact Activity.
        instance = this
        // Capture the generation of the show() call that cold-started this activity.
        myGeneration = generation

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NavyDark),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "BYDMate",
                    color = TextMuted.copy(alpha = 0.25f),
                    fontSize = 13.sp,
                )
            }
        }
    }

    // singleTask reorder-to-front path: system calls onNewIntent before onResume.
    // Capture the generation here so the subsequent onResume guard is correct.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        myGeneration = generation
    }

    override fun onResume() {
        super.onResume()
        // Complete the deferred only for the generation this instance was started for.
        // Guards against a stale onResume (e.g. after an aborted start) completing a
        // newer session's deferred.
        if (myGeneration == generation) resumedDeferred?.complete(Unit)

        // Hotfix 390-2: we were covered by a fullscreen Activity of our own package and are back
        // on top — but so are the panes' windows, hidden behind us. Re-raise them.
        val resurfaced = shouldReassertPanes(
            wasStopped = wasStopped,
            sessionActive = sessionManager.state.value is SplitSessionState.Active,
        )
        wasStopped = false
        if (resurfaced) lifecycleScope.launch { sessionManager.onBackdropResurfaced() }
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear static handle to prevent leaking a finished activity reference.
        if (instance == this) instance = null
    }

    // Back press deliberately suppressed: split is managed only via the pill menu.
    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        // no-op intentional
    }

    companion object {
        // Written on main thread only (onCreate / onDestroy), read from controller thread.
        @Volatile private var instance: SplitStageActivity? = null

        /**
         * Monotonically increasing epoch counter, incremented by [SplitOverlayController.show]
         * at the start of each new show() call (before startActivity). Guards two races:
         *
         * 1. hide()→show() back-to-back: [finishInstance] captures the generation when called;
         *    the posted runnable only executes finish() if generation is still the same, so a
         *    queued finish() from a previous session is a no-op after a newer show().
         *
         * 2. Stale onResume: [myGeneration] is captured at onCreate/onNewIntent; onResume
         *    completes [resumedDeferred] only when myGeneration == generation, preventing a
         *    lingering activity instance from completing a newer session's deferred.
         *
         * @Volatile: written by the controller coroutine, read from the main thread.
         */
        @Volatile private var generation: Int = 0

        /**
         * Called by [SplitOverlayController.show] at the start of each show() invocation.
         * Returns the new generation so the caller can pass it into [finishInstance] if needed
         * (currently unused by the caller, but kept for future flexibility).
         */
        internal fun nextGeneration(): Int = ++generation

        /**
         * Set by [SplitOverlayController] before each [SplitOverlayController.show] call.
         * Completed in [onResume] (when generation matches) so the caller can await the
         * backdrop's arrival before launching freeform app windows.
         * @Volatile: written from the controller coroutine, completed on the main thread.
         */
        @Volatile var resumedDeferred: CompletableDeferred<Unit>? = null

        /**
         * Finishes the backdrop activity, marshalled to the main thread.
         * Called by [SplitOverlayController.hide]; tolerates null (double-hide is safe).
         *
         * The generation guard makes the posted runnable a no-op when a newer show() has
         * already incremented [generation] — preventing a queued finish() from the previous
         * session from killing the newly started backdrop (hide→show back-to-back race).
         */
        fun finishInstance() {
            val act = instance ?: return
            val capturedGen = generation
            Handler(Looper.getMainLooper()).post {
                if (generation == capturedGen) act.finish()
            }
        }
    }
}
