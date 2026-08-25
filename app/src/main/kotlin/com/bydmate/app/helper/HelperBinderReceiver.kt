package com.bydmate.app.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * Verdict of [HelperBinderHolder.accept]. [reason] is what the dump and the logcat line print,
 * so it stays a short stable token rather than prose.
 */
internal enum class BinderAcceptResult(val reason: String) {
    ACCEPTED("accepted"),
    /** Nothing usable in the intent — no extras bundle or no binder inside it. */
    NO_BINDER("no_binder"),
    /** We are not waiting for a daemon right now (no spawn in flight). */
    NOT_EXPECTED("not_expected"),
    /** Token does not match the one we generated for the spawn in flight. */
    TOKEN_MISMATCH("token_mismatch"),
    /** The binder is not our daemon's stub. */
    DESCRIPTOR_MISMATCH("descriptor_mismatch"),
}

/**
 * First half of the authentication decision: everything decidable from the intent alone.
 * The receiver is exported (the sender runs as the shell uid, not as us) and runs on the main
 * thread, so no check here may touch the incoming binder — reading its interface descriptor is a
 * synchronous transaction into the sender's process, and a hostile app that never answers it
 * would hang us into an ANR. Returns null when the payload survives these checks and the
 * descriptor is the only thing left to verify.
 */
internal fun decideBinderAccept(
    hasBinder: Boolean,
    token: String?,
    expectedToken: String?,
): BinderAcceptResult? = when {
    !hasBinder -> BinderAcceptResult.NO_BINDER
    expectedToken.isNullOrEmpty() -> BinderAcceptResult.NOT_EXPECTED
    token != expectedToken -> BinderAcceptResult.TOKEN_MISMATCH
    else -> null
}

/**
 * Second half, reached only once [decideBinderAccept] passed: the binder must carry our own
 * daemon's interface descriptor. Split from the cheap checks so the ordering is a property of the
 * code — the descriptor cannot be read before the token matched.
 */
internal fun decideBinderDescriptor(descriptor: String?): BinderAcceptResult =
    if (descriptor == HelperBinderProtocol.DESCRIPTOR) BinderAcceptResult.ACCEPTED
    else BinderAcceptResult.DESCRIPTOR_MISMATCH

/**
 * Holds the daemon's IBinder when it arrived by broadcast instead of ServiceManager (H2,
 * #64/#148). HelperClient falls back to [binder] whenever the service lookup by name is empty;
 * on firmwares where addService works this object stays untouched with transport "none".
 *
 * Written from a BroadcastReceiver thread, read from every HelperClient caller — all state is
 * @Volatile, and a single reference assignment is the only mutation of the hot field.
 */
object HelperBinderHolder {

    /** Live daemon binder received by broadcast, or null when we have none. */
    @Volatile var binder: IBinder? = null
        private set

    /** Token generated for the spawn currently in flight; set by HelperBootstrap BEFORE spawning. */
    @Volatile var expectedToken: String? = null

    /** "none" until a binder is accepted, "broadcast" while one is held. For the diagnostic dump. */
    @Volatile var transport: String = TRANSPORT_NONE
        private set

    /** When the held binder arrived (System.currentTimeMillis), 0 when none. */
    @Volatile var receivedAt: Long = 0L
        private set

    /** Why the last intent was rejected, null when none was. For the diagnostic dump. */
    @Volatile var lastReject: String? = null
        private set

    const val TRANSPORT_NONE = "none"
    const val TRANSPORT_BROADCAST = "broadcast"

    /**
     * Authenticates an incoming [ACTION_BINDER][HelperBinderProtocol.ACTION_BINDER] payload and
     * stores the binder on success. Returns the verdict so the receiver can log it.
     */
    internal fun accept(bundle: Bundle?): BinderAcceptResult {
        val incoming = bundle?.getBinder(HelperBinderProtocol.KEY_BINDER)
        // Nothing below this point may touch `incoming` until the token has matched.
        decideBinderAccept(
            hasBinder = incoming != null,
            token = bundle?.getString(HelperBinderProtocol.KEY_TOKEN),
            expectedToken = expectedToken,
        )?.let { rejected ->
            lastReject = rejected.reason
            return rejected
        }
        val live = incoming!!
        val verdict = decideBinderDescriptor(runCatching { live.interfaceDescriptor }.getOrNull())
        if (verdict != BinderAcceptResult.ACCEPTED) {
            lastReject = verdict.reason
            return verdict
        }
        // Death of the daemon must clear us, or HelperClient would keep handing out a dead
        // binder that no ServiceManager lookup can invalidate (there is no service name here).
        runCatching {
            live.linkToDeath({ clearIfHolding(live) }, 0)
        }.onFailure {
            // Already dead between the descriptor read and the link — treat as nothing received.
            lastReject = BinderAcceptResult.NO_BINDER.reason
            return BinderAcceptResult.NO_BINDER
        }
        binder = live
        transport = TRANSPORT_BROADCAST
        receivedAt = System.currentTimeMillis()
        lastReject = null
        // The token is single-use: one spawn, one binder. Leaving it set would let a replay of
        // the same intent — or a second sender that saw the token — swap the binder afterwards.
        expectedToken = null
        return BinderAcceptResult.ACCEPTED
    }

    /**
     * Death recipient body. A daemon that dies AFTER its successor was accepted still delivers its
     * notice, so clear only when [dead] is the binder we currently hold — otherwise the old daemon
     * would wipe the live one.
     */
    private fun clearIfHolding(dead: IBinder) {
        if (binder === dead) clear()
    }

    /** Drops the held binder — called from the death recipient, and by tests. */
    internal fun clear() {
        binder = null
        transport = TRANSPORT_NONE
        receivedAt = 0L
    }
}

/**
 * Receives the daemon's Binder on firmwares where ServiceManager.addService is refused to the
 * shell domain (qti/trinket, DiLink 3.0). Exported by necessity — the sender is the shell uid;
 * [HelperBinderHolder.accept] is what makes that safe.
 */
class HelperBinderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HelperBinderProtocol.ACTION_BINDER) return
        val bundle = intent.getBundleExtra(HelperBinderProtocol.EXTRA_BUNDLE)
        val verdict = HelperBinderHolder.accept(bundle)
        Log.i(
            TAG,
            "binder received via broadcast: accepted=${verdict == BinderAcceptResult.ACCEPTED} " +
                "reason=${verdict.reason} version=${bundle?.getLong(HelperBinderProtocol.KEY_VERSION, -1L) ?: -1L} " +
                "pid=${bundle?.getInt(HelperBinderProtocol.KEY_PID, -1) ?: -1}"
        )
    }

    private companion object {
        const val TAG = "HelperBinderRx"
    }
}
