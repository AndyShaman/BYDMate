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
 *   TX_LIST_DISPLAYS : request (no args)
 *       -> reply: writeInt(status), writeInt(count),
 *                 then count * { writeInt(id), writeString(name),
 *                                writeInt(width), writeInt(height), writeInt(densityDpi) }
 *   TX_GET_INSTRUMENT_FEATURE : writeInt(featureId)
 *       -> reply: writeInt(status), writeInt(value)   // status 0 = value valid, <0 = no data/error
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
 *   TX_SET_TASK_WINDOWING_MODE : writeInt(taskId), writeInt(windowingMode) -> reply: writeInt(status), writeInt(0)
 *   TX_GRANT_OVERLAY_PERMISSION : (no args)               -> reply: writeInt(status), writeInt(0)
 *   TX_LAUNCH_AND_FORCE : writeString(packageName), writeInt(displayId), writeInt(width), writeInt(height)
 *       -> reply: writeInt(status), writeInt(0)           // status 0 = redirection completed
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
    const val TX_LIST_DISPLAYS = IBinder.FIRST_CALL_TRANSACTION + 3            // 4
    const val TX_GET_INSTRUMENT_FEATURE = IBinder.FIRST_CALL_TRANSACTION + 4   // 5

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

    /** Our own package — target of the narrow grantOverlayPermission appops call. */
    const val APP_PACKAGE = "com.bydmate.app"
}
