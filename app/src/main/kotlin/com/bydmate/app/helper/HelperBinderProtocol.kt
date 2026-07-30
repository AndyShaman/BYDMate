package com.bydmate.app.helper

import android.os.IBinder

/**
 * Wire contract shared by the in-app binder client (HelperClientImpl) and the
 * shell-uid daemon (HelperDaemon). The daemon registers under SERVICE_NAME via
 * ServiceManager.addService; the app reaches it with ServiceManager.getService +
 * IBinder.transact.
 *
 * Parcel layout (after data.writeInterfaceToken(DESCRIPTOR) on the request):
 *   TX_PING  : request (no args)                       -> reply: writeInt(status=0)
 *   TX_READ  : writeInt(tx), writeInt(dev), writeInt(fid)
 *                                                        -> reply: writeInt(status), writeInt(value)
 *   TX_WRITE : writeInt(dev), writeInt(fid), writeInt(value)
 *                                                        -> reply: writeInt(status), writeInt(value)
 *   TX_CREATE_VIRTUAL_DISPLAY : writeString(name), writeInt(width), writeInt(height),
 *                               writeInt(density), writeInt(flags), Surface.writeToParcel(surface)
 *       -> reply: writeInt(status), writeInt(displayId)   // status 0 = ok, displayId>0
 *   TX_RELEASE_VIRTUAL_DISPLAY : writeInt(displayId)      -> reply: writeInt(status), writeInt(0)
 *   TX_LAUNCH_APP : writeString(packageName)              -> reply: writeInt(status), writeInt(0)
 *   TX_GET_TASK_ID : writeString(packageName)             -> reply: writeInt(status), writeInt(taskId)  // taskId -1 = not found
 *   TX_MOVE_TASK_TO_DISPLAY : writeInt(taskId), writeInt(displayId)   -> reply: writeInt(status), writeInt(0)
 *   TX_SET_TASK_BOUNDS : writeInt(taskId), writeInt(left), writeInt(top), writeInt(right), writeInt(bottom)
 *       -> reply: writeInt(status), writeInt(0)
 *   TX_SET_FOCUSED_TASK : writeInt(taskId)                -> reply: writeInt(status), writeInt(0)
 *   TX_SET_TASK_WINDOWING_MODE : writeInt(taskId), writeInt(windowingMode), writeInt(activityType)
 *       -> reply: writeInt(status), writeInt(0)
 *       activityType = trailing int, absent on old clients -> RECENTS (see PANE_TYPE_*).
 *   TX_GRANT_OVERLAY_PERMISSION : (no args)               -> reply: writeInt(status), writeInt(0)
 *   TX_LAUNCH_AND_FORCE : writeString(packageName), writeInt(displayId), writeInt(width), writeInt(height)
 *       -> reply: writeInt(status), writeInt(0)           // status 0 = redirection completed
 *   TX_ENABLE_ACCESSIBILITY : (no args)                   -> reply: writeInt(status), writeInt(0)  // status 0 = our a11y service enabled
 *   TX_PUT_GLOBAL_SETTING : writeString(key), writeInt(value)
 *       -> reply: writeInt(status), writeInt(0)   // status 0 = settings put global succeeded; -1 = not whitelisted / failed
 *   TX_SET_APP_HIDDEN : writeString(packageName), writeInt(hidden: 1=disable 0=enable)
 *       -> reply: writeInt(status), writeInt(0)   // status 0 = ok; -1 = not whitelisted / failed
 *   TX_ENABLE_NOTIFICATION_LISTENER : (no args)           -> reply: writeInt(status), writeInt(0)  // status 0 = our listener stub enabled
 *   TX_SET_CLUSTER_MODE: [int on(0|1)] -> [int status]; status 0 = ok.
 *   TX_GET_TASK_STATE : writeString(packageName)
 *       -> reply: writeInt(status), then on status 0:
 *          writeInt(taskId)   // -1 = no running task
 *          writeInt(windowingMode), writeInt(left), writeInt(top), writeInt(right), writeInt(bottom)
 *          All six ints are always present when status == 0; taskId -1 means not running (others = 0).
 *   TX_APP_SPLIT_SUPPORTED : writeString(packageName)
 *       -> reply: writeInt(status), writeInt(supported)   // status -1 on exception; supported 0|1
 *   TX_GET_VERSION : (no args)
 *       -> reply: writeInt(status=0), writeInt(versionCode)  // BuildConfig.VERSION_CODE frozen at spawn time
 *       An old daemon without this handler makes transact return false → client treats it as null.
 *   TX_GET_TOP_PACKAGE : (no args)
 *       -> reply: writeInt(status=0), writeString(packageName)  // "" when no top task
 *       An old daemon without this handler makes transact return false → client returns null.
 *   TX_RAISE_FREEFORM_TASK : writeString(packageName), writeInt(displayId), writeInt(activityType)
 *       -> reply: writeInt(status), writeInt(0)  // status 0 = ok; -1 = failed/component unresolved
 *       activityType = trailing int, absent on old clients → RECENTS (see PANE_TYPE_*).
 *       An old daemon without this handler makes transact return false → client returns false.
 *   TX_DUMP_FIDS : (no args)
 *       -> reply: writeInt(status), writeString(dump)  // status 0 = ok; -1 = reflection failed
 *       dump = sorted "ClassName.FIELD_NAME=value" lines joined with \n; empty string on non-BYD firmware.
 *       An old daemon without this handler makes transact return false → client returns null.
 *
 * Projection status: 0 = success, <0 = error/unavailable. Surface is written LAST so a
 * marshalling test can assert the scalar args without round-tripping the Surface.
 *
 * status/value carry the raw autoservice transact result (see HelperDaemon).
 */
object HelperBinderProtocol {
    const val SERVICE_NAME = "bydmate_helper"
    const val PROCESS_NAME = "bydmate_helper"   // app_process --nice-name + ps lookup
    const val DESCRIPTOR = "com.bydmate.app.helper.IHelper"

    const val TX_PING = IBinder.FIRST_CALL_TRANSACTION       // 1
    const val TX_READ = IBinder.FIRST_CALL_TRANSACTION + 1   // 2
    const val TX_WRITE = IBinder.FIRST_CALL_TRANSACTION + 2  // 3
    // tx 4, 5 retired (diagnostic listDisplays / getInstrumentFeature) — slots left as gaps.

    const val TX_CREATE_VIRTUAL_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 5    // 6
    const val TX_RELEASE_VIRTUAL_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 6   // 7
    const val TX_LAUNCH_APP = IBinder.FIRST_CALL_TRANSACTION + 7                // 8
    const val TX_GET_TASK_ID = IBinder.FIRST_CALL_TRANSACTION + 8               // 9
    const val TX_MOVE_TASK_TO_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 9      // 10
    const val TX_SET_TASK_BOUNDS = IBinder.FIRST_CALL_TRANSACTION + 10          // 11
    const val TX_SET_FOCUSED_TASK = IBinder.FIRST_CALL_TRANSACTION + 11         // 12
    const val TX_SET_TASK_WINDOWING_MODE = IBinder.FIRST_CALL_TRANSACTION + 12  // 13
    const val TX_GRANT_OVERLAY_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 13 // 14
    const val TX_LAUNCH_AND_FORCE = IBinder.FIRST_CALL_TRANSACTION + 14         // 15
    const val TX_ENABLE_ACCESSIBILITY = IBinder.FIRST_CALL_TRANSACTION + 15     // 16
    const val TX_PUT_GLOBAL_SETTING = IBinder.FIRST_CALL_TRANSACTION + 16       // 17
    const val TX_SET_APP_HIDDEN = IBinder.FIRST_CALL_TRANSACTION + 17           // 18
    const val TX_ENABLE_NOTIFICATION_LISTENER = IBinder.FIRST_CALL_TRANSACTION + 18  // 19
    /** Cluster compositor power via the auto_container service (Wave P). [int on] -> [int status]. */
    val TX_SET_CLUSTER_MODE = IBinder.FIRST_CALL_TRANSACTION + 19

    /**
     * Batched autoservice read. Request: int count, then count × (int tx, int dev, int fid).
     * Reply: int count, then count × (int status, int value) — same (status, value)
     * convention as TX_READ, one pair per requested triple, in request order.
     * count outside [1, MAX_BATCH_ITEMS] → reply is a single int 0 (no pairs).
     * Added in wave L; an older daemon returns false for this code (unknown
     * transaction → Binder.onTransact default), which the client treats as
     * "batch unsupported" and falls back to per-fid reads.
     */
    val TX_READ_BATCH: Int = IBinder.FIRST_CALL_TRANSACTION + 20

    /** Direct freeform launch for cluster projection and split panes: [String pkg, int displayId,
     *  int left, int top, int right, int bottom, int activityType] -> [int status (0 ok, -2 freeform
     *  unavailable, -1 failed), int 0]. activityType = trailing int, absent on old clients →
     *  RECENTS (see PANE_TYPE_*). */
    val TX_LAUNCH_FREEFORM: Int = IBinder.FIRST_CALL_TRANSACTION + 21          // 22

    /** `wm density` override on a NON-default display: [int displayId, int density
     *  (0 = reset)] -> [int status, int 0]. Maps the projection scale regulator onto the
     *  real cluster display in direct mode. */
    val TX_SET_DISPLAY_DENSITY: Int = IBinder.FIRST_CALL_TRANSACTION + 22      // 23

    /** `pm grant` of android.permission.READ_LOGS to our own package (development permission,
     *  hardcoded target) so the in-app log recorder sees the daemon's logcat lines.
     *  (no args) -> [int status, int 0]. */
    val TX_GRANT_READ_LOGS: Int = IBinder.FIRST_CALL_TRANSACTION + 23          // 24

    /** Enable (1) or disable (0) the Wi-Fi hotspot via BydTetheringInterface / ConnectivityManager
     *  reflection (TETHERING_WIFI = 0). Requires TETHER_PRIVILEGED held by shell uid.
     *  Request: [int enable: 1=on, 0=off] -> [int status (0=ok, -1=fail), int 0]. */
    val TX_SET_HOTSPOT: Int = IBinder.FIRST_CALL_TRANSACTION + 24              // 25

    /** Reads windowing state (taskId, windowingMode, bounds) for a package via ActivityTaskManager.
     *  Request: [String pkg] -> [int status (0=ok/-1=error), int taskId (-1=not running),
     *  int windowingMode, int left, int top, int right, int bottom].
     *  All seven ints are present when status == 0; taskId -1 means no running task (others = 0). */
    val TX_GET_TASK_STATE: Int = IBinder.FIRST_CALL_TRANSACTION + 25           // 26

    /** Queries IStatusBarService.isAppSuportSplit (BYD extension, tx 82) for split-screen eligibility.
     *  Request: [String pkg] -> [int status (0=ok/-1=exception), int supported (0|1)]. */
    val TX_APP_SPLIT_SUPPORTED: Int = IBinder.FIRST_CALL_TRANSACTION + 26      // 27

    /** Force-stops [packageName] via IActivityManager.forceStopPackage (shell uid holds
     *  FORCE_STOP_PACKAGES). Used before freeform re-launch to clear a stale fullscreen task
     *  that resists windowing-mode changes (on-car: Home+relaunch leaves task mode=1 invisible).
     *  Request: [String pkg] -> [int status (0=ok/-1=failed), int 0]. */
    val TX_FORCE_STOP: Int = IBinder.FIRST_CALL_TRANSACTION + 27               // 28

    /** Returns the versionCode the daemon was compiled with (BuildConfig.VERSION_CODE, frozen at
     *  spawn time since CLASSPATH is fixed to the APK at spawn). The client uses this to detect
     *  stale daemons that survived an APK update. (no args) -> [int status (0=ok), int versionCode].
     *  An old daemon without this handler makes transact return false → client returns null. */
    val TX_GET_VERSION: Int = IBinder.FIRST_CALL_TRANSACTION + 28              // 29

    /** Returns the package name of the foreground (top-of-stack) task via getTasks reflection.
     *  Used by the media-key reroute guard to detect when com.byd.mediacenter has surfaced over
     *  an active split without a per-package fullscreen-mode query that is false for backgrounded
     *  tasks. (no args) -> [int status (0=ok/-1=error), String packageName ("" when no top task)].
     *  An old daemon without this handler makes transact return false → client returns null. */
    val TX_GET_TOP_PACKAGE: Int = IBinder.FIRST_CALL_TRANSACTION + 29          // 30

    /**
     * Raises an existing freeform task to front via `am start --windowingMode 5
     * [--activityType 3] --display <displayId> -n <component>`, relaunching it when its live
     * activityType diverges from the requested one. Used by reAssertSplitZOrder to
     * recover split pane Z-order after a steering-wheel media key event (Task N: up to 391
     * recents-typed pane tasks nested under a shared root task, so setFocusedRootTask on a leaf id
     * was a no-op; panes are STANDARD from 392, but the raise stays the primary path).
     *
     * Request: [String pkg, int displayId, int activityType]
     * Reply:   [int status (0=ok, -1=failed/unresolved), int 0]
     *
     * An old daemon without this handler makes transact return false → client returns false
     * and falls back to setFocusedTask (386-era behavior). New TX only; no changes to prior codes.
     */
    val TX_RAISE_FREEFORM_TASK: Int = IBinder.FIRST_CALL_TRANSACTION + 30      // 31

    /**
     * Reflects all static int/long constants from android.hardware.bydauto.BYDAutoFeatureIds
     * and BYDAutoConstants (and their declared inner classes) via plain reflection.
     * Returns sorted "ClassName.FIELD=value" lines joined with \n; empty string on firmware
     * without the BYD SDK classes (non-BYD Android). No hidden-API bypass needed: the daemon
     * runs under app_process where hidden-API enforcement is inactive.
     *
     * Chunked transport (v2, Q4): the full dump may exceed the ~1 MB binder transaction limit.
     * Request:  int offset  (0-based byte offset into the UTF-8 encoded dump string; send 0 first)
     * Reply:    int status  (0 = ok, -1 = reflection / internal error)
     *           int totalLength  (total UTF-8 byte count; fixed across chunks for one sequence)
     *           byte[] chunk     (up to [DUMP_CHUNK_MAX] bytes starting at [offset])
     * Client loops: send next offset = previous offset + chunk.size; stop when offset >= totalLength.
     * Daemon builds the full dump on offset==0 and caches it (@Volatile); subsequent offsets reuse
     * the cache.
     * On old daemons (pre-Q4) transact returns false → client returns [DumpFidsResult.BinderAbsent].
     */
    val TX_DUMP_FIDS: Int = IBinder.FIRST_CALL_TRANSACTION + 31               // 32

    /**
     * Returns the windowing state of the top root task on the primary display (display 0).
     * Used by SplitSessionManager to detect when a foreign fullscreen app has covered the split
     * session (COVERED teardown, Q3 / F-3). Uses the same getTasks reflection surface as
     * TX_GET_TASK_STATE and TX_GET_TOP_PACKAGE.
     *
     * (no args) -> [int status (0=ok/-1=error/no task), String pkg, int taskId,
     *               int windowingMode, int activityType, int displayId]
     *
     * An old daemon without this handler makes transact return false → client returns null,
     * and SplitSessionManager skips COVERED detection that tick (fail-safe).
     */
    val TX_GET_TOP_TASK: Int = IBinder.FIRST_CALL_TRANSACTION + 32            // 33

    /** Hard cap on items per TX_READ_BATCH call (FidMap is 58 today; 128 leaves headroom). */
    const val MAX_BATCH_ITEMS: Int = 128

    /**
     * Maximum chunk size for TX_DUMP_FIDS chunked transport (UTF-8 bytes per reply).
     * 64 KiB is well under the ~1 MB binder transaction limit shared across the process,
     * leaving ample headroom for status/totalLength overhead and concurrent transactions.
     */
    const val DUMP_CHUNK_MAX: Int = 64 * 1024

    /** WindowConfiguration activityType values carried as the trailing int of
     *  TX_LAUNCH_FREEFORM / TX_RAISE_FREEFORM_TASK / TX_SET_TASK_WINDOWING_MODE.
     *  STANDARD panes own their root task (split touch fix, 392); RECENTS is kept for
     *  cluster projection (suppresses the freeform caption on the cluster display).
     *  Mixed-version: an old daemon simply never reads the trailing int (legacy RECENTS
     *  behavior); an old app not writing it makes the new daemon read it as absent and
     *  default to RECENTS. HelperBootstrap's version gate makes both windows transient. */
    const val PANE_TYPE_STANDARD = 1
    const val PANE_TYPE_RECENTS = 3

    /** Our own package — target of the narrow grantOverlayPermission appops call. */
    const val APP_PACKAGE = "com.bydmate.app"

    /**
     * Flattened ComponentName of our steering-wheel accessibility service — appended
     * (never clobbering existing entries) to Settings.Secure enabled_accessibility_services
     * by the narrow enableAccessibilityService daemon op, since DiLink has no a11y settings UI.
     */
    const val ACCESSIBILITY_SERVICE_COMPONENT =
        "com.bydmate.app/com.bydmate.app.cluster.SteeringWheelKeyService"

    /**
     * Flattened ComponentName of our notification-listener stub — appended (never clobbering
     * existing entries) to Settings.Secure enabled_notification_listeners by the narrow
     * enableNotificationListener daemon op, mirroring ACCESSIBILITY_SERVICE_COMPONENT. Grants
     * MediaSessionManager.getActiveSessions() access to our process for real Yandex Music playback.
     */
    const val NOTIFICATION_LISTENER_COMPONENT =
        "com.bydmate.app/com.bydmate.app.media.MediaSessionListenerService"
}
