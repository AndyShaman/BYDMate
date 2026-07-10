package com.bydmate.app.media

import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient

/** Self-grants notification-listener access for MediaSessionListenerService via the helper
 *  daemon (settings put secure enabled_notification_listeners), preserving any listeners already
 *  enabled. The daemon op itself is idempotent; this is a thin fire-and-forget wrapper. */
object MediaSessionGrant {
    private const val TAG = "MediaSessionGrant"

    suspend fun ensureGranted(helper: HelperClient): Boolean {
        val ok = helper.enableNotificationListenerAccess()
        if (!ok) Log.w(TAG, "failed to grant notification listener access")
        return ok
    }
}
