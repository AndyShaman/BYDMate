package com.bydmate.app.split

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import com.bydmate.app.media.MediaSessionListenerService

// ─── Constants ────────────────────────────────────────────────────────────────

/** Package of the BYD stock media player that intercepts the rotary play/pause knob. */
internal const val MEDIACENTER_PKG = "com.byd.mediacenter"

/** Poll interval while a split session is Active. Zero cost when session is Idle.
 *  100 ms (was 250 ms): at this rate a skipped tick (getTopTaskPackageOrSkip tryLock miss)
 *  costs at most 100 ms of reroute latency, which is acceptable. */
internal const val MEDIA_POLL_MS = 100L

/** Max reroutes allowed before the stand-down latch engages. */
internal const val REROUTE_MAX_COUNT = 2

// ─── Interfaces ───────────────────────────────────────────────────────────────

/**
 * Provides access to the system MediaSession stack from the split-screen layer.
 * The production implementation calls MediaSessionManager.getActiveSessions() using the
 * notification-listener foothold established by [MediaSessionListenerService].
 * Permission-revoked calls must return empty results — callers treat empty as "no reroute".
 */
interface MediaSessionSource {
    /**
     * Returns the set of packages that currently own an active MediaSession.
     * Returns an empty set if the notification-listener permission is revoked or the
     * MediaSessionManager call fails — degrade gracefully, never throw.
     */
    fun activeSessionPackages(): Set<String>

    /**
     * Finds a MediaController for one of [panePkgs], preferring one with a non-NONE
     * playback state (i.e. the app that was recently playing). Returns null when no
     * matching controller is found or if the permission is unavailable.
     */
    fun findController(panePkgs: Set<String>): MediaControllerHandle?
}

/**
 * Thin wrapper around a real android.media.session.MediaController, abstracted for
 * testing (MediaController has no public constructor in unit-test scope).
 */
interface MediaControllerHandle {
    val packageName: String

    /**
     * Sends ACTION_DOWN + ACTION_UP KEYCODE_MEDIA_PLAY_PAUSE to the underlying session.
     * This replicates what the system media-button router would do if it received the
     * knob event normally. Fail-soft: implementations must not throw.
     */
    fun dispatchPlayPause()
}

// ─── Pure trigger logic ───────────────────────────────────────────────────────

/**
 * Pure function deciding whether a media-key reroute should fire.
 *
 * Returns true only when ALL of the following hold:
 *  1. [topPkg] equals [MEDIACENTER_PKG] (mediacenter is the foreground task).
 *  2. [MEDIACENTER_PKG] is NOT in [panePkgs] (mediacenter is not itself a split pane — that
 *     would mean the user intentionally launched it in split, not via the knob).
 *  3. The split session is Active ([splitActive] == true).
 *  4. At least one pane package appears in [sessionPkgs] (a pane owns a live MediaSession).
 *  5. The stand-down latch is not set: [stoodDown] == false (fewer than [REROUTE_MAX_COUNT]
 *     reroutes have been performed in this session; the latch is cleared on session start/exit).
 *
 * @param topPkg      package name returned by TX_GET_TOP_PACKAGE (null or "" = mediacenter absent).
 * @param splitActive true when SplitSessionState is Active.
 * @param panePkgs    set of packages currently in the two panes.
 * @param sessionPkgs packages that currently own an active MediaSession.
 * @param stoodDown   true once [REROUTE_MAX_COUNT] reroutes have fired in this session;
 *                    cleared on session start/exit. Prevents infinite re-assertion loops.
 */
internal fun shouldReroute(
    topPkg: String?,
    splitActive: Boolean,
    panePkgs: Set<String>,
    sessionPkgs: Set<String>,
    stoodDown: Boolean,
): Boolean {
    if (topPkg != MEDIACENTER_PKG) return false
    if (MEDIACENTER_PKG in panePkgs) return false
    if (!splitActive) return false
    if (panePkgs.intersect(sessionPkgs).isEmpty()) return false
    if (stoodDown) return false
    return true
}

// ─── Production implementation ────────────────────────────────────────────────

/**
 * Production [MediaSessionSource] backed by [MediaSessionManager.getActiveSessions].
 * Uses [MediaSessionListenerService] as the notification-listener component; that service
 * must be enabled (via the helper daemon) for getActiveSessions to grant our process access.
 * Any exception from the system call degrades to an empty result — caller stands down.
 */
class ProductionMediaSessionSource(private val context: Context) : MediaSessionSource {

    private fun sessions(): List<android.media.session.MediaController> = runCatching {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, MediaSessionListenerService::class.java)
        msm.getActiveSessions(component)
    }.getOrElse { emptyList() }

    override fun activeSessionPackages(): Set<String> =
        sessions().mapTo(mutableSetOf()) { it.packageName }

    override fun findController(panePkgs: Set<String>): MediaControllerHandle? {
        val controllers = sessions()
        // Prefer a controller with a meaningful playback state (not NONE / not null).
        val preferred = controllers.firstOrNull { ctrl ->
            ctrl.packageName in panePkgs &&
                (ctrl.playbackState?.state ?: PlaybackState.STATE_NONE) != PlaybackState.STATE_NONE
        }
        val picked = preferred ?: controllers.firstOrNull { it.packageName in panePkgs }
        return picked?.let { RealMediaControllerHandle(it) }
    }
}

private class RealMediaControllerHandle(
    private val controller: android.media.session.MediaController,
) : MediaControllerHandle {

    override val packageName: String = controller.packageName

    override fun dispatchPlayPause() {
        // Wrapped in runCatching: the session token may have died between findController and
        // dispatch, and dispatchMediaButtonEvent throws in that case — fail-soft.
        runCatching {
            val now = SystemClock.uptimeMillis()
            // ACTION_UP reuses ACTION_DOWN's eventTime — same as a real hardware key event pair.
            controller.dispatchMediaButtonEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            )
            controller.dispatchMediaButtonEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            )
        }
    }
}
