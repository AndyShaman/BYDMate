package com.bydmate.app.helper.push

import android.hardware.bydauto.BYDAutoEventValue
import android.hardware.bydauto.adas.AbsBYDAutoADASListener
import android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener
import android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener
import android.hardware.bydauto.light.AbsBYDAutoLightListener
import android.os.SystemClock
import android.util.Log

/**
 * The four listener subclasses the daemon registers with the vendor devices. The firmware rejects
 * a dynamic Proxy over IBYDAutoListener, so each device needs a real subclass of its own Abs class
 * — the base classes only differ in which typed callbacks they dispatch, which we do not use.
 *
 * onDataChanged is final in the firmware, so [BYDAutoEventValue]-carrying onDataEventChanged is the
 * hook: its eventType IS the fid. These run on a binder thread inside vendor framework code — the
 * override does nothing but hand the numbers to [sink], and never lets a throwable escape.
 */
internal fun interface FidPushSink {
    fun onEvent(fid: Int, intValue: Int, doubleValue: Double, tsElapsed: Long)
}

private const val TAG = "FidPush"

private fun FidPushSink.dispatch(eventType: Int, value: BYDAutoEventValue?) {
    // runCatching, because an escaping throwable lands in the vendor stack and can take the
    // daemon down with it.
    runCatching {
        onEvent(eventType, value?.intValue ?: 0, value?.doubleValue ?: 0.0, SystemClock.elapsedRealtime())
    }.onFailure { Log.w(TAG, "sink failed for fid=$eventType: $it") }
}

private fun logListenerError(device: String, errCode: Int, errMessage: String?) {
    Log.w(TAG, "listener error dev=$device code=$errCode msg=${errMessage ?: "-"}")
}

internal class LightPushListener(private val sink: FidPushSink) : AbsBYDAutoLightListener() {
    override fun onDataEventChanged(eventType: Int, eventValue: BYDAutoEventValue?) =
        sink.dispatch(eventType, eventValue)

    override fun onError(errCode: Int, errMessage: String?) = logListenerError("light", errCode, errMessage)
}

internal class GearboxPushListener(private val sink: FidPushSink) : AbsBYDAutoGearboxListener() {
    override fun onDataEventChanged(eventType: Int, eventValue: BYDAutoEventValue?) =
        sink.dispatch(eventType, eventValue)

    override fun onError(errCode: Int, errMessage: String?) = logListenerError("gearbox", errCode, errMessage)
}

internal class AdasPushListener(private val sink: FidPushSink) : AbsBYDAutoADASListener() {
    override fun onDataEventChanged(eventType: Int, eventValue: BYDAutoEventValue?) =
        sink.dispatch(eventType, eventValue)

    override fun onError(errCode: Int, errMessage: String?) = logListenerError("adas", errCode, errMessage)
}

internal class BodyworkPushListener(private val sink: FidPushSink) : AbsBYDAutoBodyworkListener() {
    override fun onDataEventChanged(eventType: Int, eventValue: BYDAutoEventValue?) =
        sink.dispatch(eventType, eventValue)

    override fun onError(errCode: Int, errMessage: String?) = logListenerError("bodywork", errCode, errMessage)
}

/**
 * The listener that belongs to [device], or null when that device has no listener class at all.
 *
 * The four classes above cover the devices the first push wave used; every other device of
 * [FID_PUSH_DEVICE_CLASSES] is served by the generated recorder subclasses, which dispatch
 * exactly the same numbers. They are instantiated fresh here, so a recorder run and a push
 * subscription still hold separate listener instances and unregister independently.
 */
internal fun pushListenerFor(device: Int, sink: FidPushSink): Any? = when (device) {
    1001 -> BodyworkPushListener(sink)
    1004 -> LightPushListener(sink)
    1011 -> GearboxPushListener(sink)
    1038 -> AdasPushListener(sink)
    else -> recorderListenerFor(device, sink)
}
