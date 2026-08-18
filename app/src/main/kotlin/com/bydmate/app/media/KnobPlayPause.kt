package com.bydmate.app.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.util.Log
import com.bydmate.app.split.RealMediaControllerHandle

/**
 * Sends play/pause to the app that owns the active MediaSession, replacing what the stock
 * MediaKeyHandler stopped doing after firmware V1.6 (it now lets com.byd.mediacenter switch the
 * audio source unless we own audio focus). Driven by the volume-knob press intercepted in
 * SteeringWheelKeyService; fail-soft everywhere — a missing listener grant or a dead session token
 * degrades to "nothing sent", never to an exception on the a11y binder thread.
 */
object KnobPlayPause {

    private const val TAG = "KnobPlayPause"

    // PlaybackState constants as literals: this file's pure part is unit-tested on the JVM, where
    // android.jar members are stubs.
    private const val STATE_NONE = 0
    private const val STATE_PLAYING = 3

    /** One active MediaSession, reduced to what target selection needs.
     *  [playbackState] is a PlaybackState.STATE_* value; null = the session reports no state. */
    data class SessionSnapshot(val packageName: String, val playbackState: Int?)

    /**
     * Picks which session gets the play/pause, returning its index in [sessions] (null when empty).
     * The list order is the system's own priority order from getActiveSessions.
     * Preference: a session that is PLAYING (pausing it is what the driver expects) → a session
     * with any state other than NONE (recently played, resume it) → the highest-priority session.
     */
    fun pickTarget(sessions: List<SessionSnapshot>): Int? {
        if (sessions.isEmpty()) return null
        val playing = sessions.indexOfFirst { it.playbackState == STATE_PLAYING }
        if (playing >= 0) return playing
        val stateful = sessions.indexOfFirst { (it.playbackState ?: STATE_NONE) != STATE_NONE }
        if (stateful >= 0) return stateful
        return 0
    }

    /**
     * Production entry point: reads the active sessions through the notification-listener foothold
     * ([MediaSessionListenerService]) and dispatches play/pause to [pickTarget]'s choice.
     * Returns true when a key pair was actually sent.
     */
    fun dispatch(context: Context): Boolean {
        val controllers = runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, MediaSessionListenerService::class.java)
            msm.getActiveSessions(component)
        }.getOrElse { emptyList() }
        val snapshots = controllers.map { SessionSnapshot(it.packageName, it.playbackState?.state) }
        val index = pickTarget(snapshots)
        if (index == null) {
            Log.i(TAG, "knob play/pause: no media session")
            return false
        }
        val target = controllers[index]
        Log.i(TAG, "knob play/pause -> ${target.packageName}")
        RealMediaControllerHandle(target).dispatchPlayPause()
        return true
    }
}
