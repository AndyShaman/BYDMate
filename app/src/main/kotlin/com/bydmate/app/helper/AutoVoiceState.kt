package com.bydmate.app.helper

import java.lang.reflect.InvocationTargetException

private const val AUTO_VOICE_TAG = "bydmate_helper"

/** External-amp type, read for the log only: the call works with and without the amp (#353). */
internal const val EXT_AMP_TYPE_PROP = "sys.ext.amp.type"

/** The package the firmware compares the passed name with; the uid is not checked. */
private const val STOCK_ASSISTANT_PACKAGE = "com.byd.autovoice"

/** TX_SET_AUTO_VOICE reply: the outcome and the log detail. */
internal class AutoVoiceResult(val outcome: Int, val detail: String)

/**
 * TX_SET_AUTO_VOICE body: calls setAutoVoiceActiveState([active], [active], "com.byd.autovoice")
 * on the IAudioService proxy [audioService] hands back, looked up by name because it is BYD's
 * hidden addition. [ampType] only goes into the detail. Nothing is propagated: every command
 * ends in one log line and a reply.
 */
internal fun setAutoVoiceActive(
    active: Boolean,
    ampType: () -> String?,
    audioService: () -> Any,
): AutoVoiceResult {
    val amp = runCatching(ampType).getOrNull() ?: "none"
    val result = runCatching(audioService).fold(
        onSuccess = { callAutoVoice(it, active) },
        onFailure = { e -> AutoVoiceResult(HelperBinderProtocol.AUTO_VOICE_FAILED, "no audio service ${describe(e)}") },
    ).let { AutoVoiceResult(it.outcome, "amp=$amp ${it.detail}") }
    android.util.Log.i(AUTO_VOICE_TAG, "auto voice want=$active ${result.detail} -> ${outcomeName(result.outcome)}")
    return result
}

private fun callAutoVoice(audio: Any, active: Boolean): AutoVoiceResult {
    val method = runCatching {
        audio.javaClass.getMethod(
            "setAutoVoiceActiveState",
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
        )
    }.getOrNull() ?: return AutoVoiceResult(HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED, "no setAutoVoiceActiveState")
    return runCatching { method.invoke(audio, active, active, STOCK_ASSISTANT_PACKAGE) }.fold(
        onSuccess = { AutoVoiceResult(HelperBinderProtocol.AUTO_VOICE_APPLIED, "setAutoVoiceActiveState($active)") },
        onFailure = { e -> AutoVoiceResult(HelperBinderProtocol.AUTO_VOICE_FAILED, "setAutoVoiceActiveState ${describe(e)}") },
    )
}

/** IAudioService proxy: ServiceManager.getService("audio") through IAudioService.Stub.asInterface. */
internal fun audioServiceProxy(): Any {
    val binder = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, "audio") as? android.os.IBinder
        ?: error("audio service null")
    return Class.forName("android.media.IAudioService\$Stub")
        .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        ?: error("asInterface null")
}

private fun describe(e: Throwable): String =
    ((e as? InvocationTargetException)?.cause ?: e).let { "${it.javaClass.simpleName}: ${it.message}" }

private fun outcomeName(outcome: Int): String = when (outcome) {
    HelperBinderProtocol.AUTO_VOICE_APPLIED -> "applied"
    HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED -> "unsupported"
    else -> "failed"
}
