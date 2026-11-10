package com.bydmate.app.helper

import com.bydmate.app.helper.push.FID_PUSH_DEVICE_CLASSES
import com.bydmate.app.helper.push.deviceInstance
import android.os.IBinder
import android.os.Parcel

/**
 * The HUD verbs, out of line like the recorder's: the SDK navigation status call, way 3's SDK
 * calls and the setBuffer write. Every reply starts with a status int; a failure costs this call only.
 */
internal fun handleHudTransact(code: Int, data: Parcel, reply: Parcel?, svc: IBinder, autoIface: String): Boolean {
    if (code == HelperBinderProtocol.TX_HUD_NAVI_STATUS) {
        val status = runCatching { data.readInt() }.getOrDefault(HelperBinderProtocol.HUD_NAVI_PROBE)
        val (outcome, sdkReturn) = sendAutoNaviStatusOutcome(status) {
            deviceInstance(FID_PUSH_DEVICE_CLASSES.getValue(HUD_NAVI_DEVICE))
        }
        reply?.writeInt(outcome)
        reply?.writeInt(sdkReturn)
        return true
    }
    if (code == HelperBinderProtocol.TX_HUD_SDK) {
        val (outcome, sdkReturn) = runCatching { readHudSdkInvocation(data) }.fold(
            onSuccess = { call ->
                call?.let { hudSdkOutcome(it) { deviceInstance(FID_PUSH_DEVICE_CLASSES.getValue(HUD_NAVI_DEVICE)) } }
                    ?: (HelperBinderProtocol.HUD_NAVI_ABSENT to 0)
            },
            onFailure = { HelperBinderProtocol.HUD_NAVI_THREW to 0 },
        )
        reply?.writeInt(outcome)
        reply?.writeInt(sdkReturn)
        return true
    }
    runCatching {
        val dev = data.readInt()
        val fid = data.readInt()
        val bytes = data.createByteArray() ?: ByteArray(0)
        val (status, _) = autoserviceTransactBuffer(svc, autoIface, dev, fid, bytes)
        reply?.writeInt(status)
        reply?.writeInt(0)
    }.onFailure {
        reply?.setDataSize(0)
        reply?.writeInt(-1); reply?.writeInt(0)
    }
    return true
}

/** The instrument device, owner of sendAutoNaviStatus. */
private const val HUD_NAVI_DEVICE = 1007

/**
 * TX_HUD_NAVI_STATUS body: `sendAutoNaviStatus(int)` on the device [device] hands back, looked up
 * by reflection because the SDK class exists only on the head unit. Returns (outcome, sdkReturn):
 * [HelperBinderProtocol.HUD_NAVI_PROBE] only reports whether the method exists. A throw (no
 * system Context, the device refusing, the SDK's own SecurityException) is logged by class name
 * and reported, never propagated: the app then writes the fid raw.
 */
internal fun sendAutoNaviStatusOutcome(status: Int, device: () -> Any): Pair<Int, Int> {
    val target = runCatching(device).getOrElse { e ->
        android.util.Log.w(HUD_NAVI_TAG, "sendAutoNaviStatus: no device: ${unwrapReflectionCause(e).javaClass.simpleName}")
        return HelperBinderProtocol.HUD_NAVI_THREW to 0
    }
    val method = runCatching {
        target.javaClass.getMethod("sendAutoNaviStatus", Int::class.javaPrimitiveType)
    }.getOrNull() ?: return HelperBinderProtocol.HUD_NAVI_ABSENT to 0
    if (status == HelperBinderProtocol.HUD_NAVI_PROBE) return HelperBinderProtocol.HUD_NAVI_CALLED to 0
    return runCatching { HelperBinderProtocol.HUD_NAVI_CALLED to (method.invoke(target, status) as? Int ?: 0) }
        .getOrElse { e ->
            // The SDK's message is its own verdict ("[setInt] permission deny!"), no user data.
            val root = unwrapReflectionCause(e)
            android.util.Log.w(HUD_NAVI_TAG, "sendAutoNaviStatus($status) threw ${root.javaClass.simpleName}: ${root.message}")
            HelperBinderProtocol.HUD_NAVI_THREW to 0
        }
}

private const val HUD_NAVI_TAG = "HudArming"

/** One TX_HUD_SDK call: the instrument method's name, its parameter types and the arguments. */
internal class HudSdkInvocation(val method: String, val types: Array<Class<*>>, val args: Array<Any>) {
    companion object {
        private val INT = Int::class.javaPrimitiveType!!
        fun guidance(icon: Int, distance: Int) = HudSdkInvocation("sendSimpleGuidanceInfo", arrayOf(INT, INT), arrayOf(icon, distance))
        fun pathName(name: String) = HudSdkInvocation("sendNextPathName", arrayOf(String::class.java), arrayOf(name))
        fun restRoute(hours: Int, minutes: Int, mileage: Long) = HudSdkInvocation(
            "sendRestRouteInfo", arrayOf(INT, INT, Long::class.javaPrimitiveType!!), arrayOf(hours, minutes, mileage),
        )
    }
}

/** TX_HUD_SDK request (see HudSdkCall.writeTo); null for a method this daemon does not know. */
internal fun readHudSdkInvocation(data: Parcel): HudSdkInvocation? = when (data.readInt()) {
    HelperBinderProtocol.HUD_SDK_GUIDANCE -> HudSdkInvocation.guidance(data.readInt(), data.readInt())
    HelperBinderProtocol.HUD_SDK_PATH_NAME -> HudSdkInvocation.pathName(data.readString().orEmpty())
    HelperBinderProtocol.HUD_SDK_REST_ROUTE -> HudSdkInvocation.restRoute(data.readInt(), data.readInt(), data.readLong())
    else -> null
}

/**
 * TX_HUD_SDK body: [call] on the device [device] hands back, looked up by reflection like
 * [sendAutoNaviStatusOutcome]. Returns (outcome, sdkReturn); a throw is logged by class name only
 * (the road name is user data, the SDK may echo it) and reported, never propagated.
 */
@Suppress("SpreadOperator") // two or three arguments per call
internal fun hudSdkOutcome(call: HudSdkInvocation, device: () -> Any): Pair<Int, Int> {
    val target = runCatching(device).getOrElse { e ->
        android.util.Log.w(HUD_SDK_TAG, "${call.method}: no device: ${unwrapReflectionCause(e).javaClass.simpleName}")
        return HelperBinderProtocol.HUD_NAVI_THREW to 0
    }
    val method = runCatching { target.javaClass.getMethod(call.method, *call.types) }.getOrNull()
        ?: return HelperBinderProtocol.HUD_NAVI_ABSENT to 0
    return runCatching { HelperBinderProtocol.HUD_NAVI_CALLED to (method.invoke(target, *call.args) as? Int ?: 0) }
        .getOrElse { e ->
            android.util.Log.w(HUD_SDK_TAG, "${call.method} threw ${unwrapReflectionCause(e).javaClass.simpleName}")
            HelperBinderProtocol.HUD_NAVI_THREW to 0
        }
}

private const val HUD_SDK_TAG = "HudWayChannels"
