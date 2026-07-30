package com.bydmate.app.media

import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient

/** Self-grants notification-listener access for MediaSessionListenerService via the helper
 *  daemon. The daemon tries `cmd notification allow_listener` first (updates NMS's canonical
 *  approved list); falls back to settings put secure enabled_notification_listeners on firmwares
 *  that predate cmd notification. Preserves any listeners already enabled; idempotent. */
object MediaSessionGrant {
    private const val TAG = "MediaSessionGrant"

    suspend fun ensureGranted(helper: HelperClient): Boolean {
        val ok = helper.enableNotificationListenerAccess()
        if (!ok) Log.w(TAG, "failed to grant notification listener access")
        return ok
    }
}
