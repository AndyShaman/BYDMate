package com.bydmate.app.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
    /** No token is armed at all — no spawn of ours has ever run in this install. */
    NOT_EXPECTED("not_expected"),
    /** Token does not match the one we generated for the last spawn. */
    TOKEN_MISMATCH("token_mismatch"),
    /** The binder is not our daemon's stub. */
    DESCRIPTOR_MISMATCH("descriptor_mismatch"),
    /** We already hold a binder — a re-announce into a live process, or a replay. */
    ALREADY_HELD("already_held"),
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
    alreadyHolding: Boolean = false,
): BinderAcceptResult? = when {
    !hasBinder -> BinderAcceptResult.NO_BINDER
    expectedToken.isNullOrEmpty() -> BinderAcceptResult.NOT_EXPECTED
    token != expectedToken -> BinderAcceptResult.TOKEN_MISMATCH
    // The token outlives its accept now (a daemon re-announces into a recreated app process),
    // so holding a binder is what makes the intent single-use: nothing may swap a live binder.
    alreadyHolding -> BinderAcceptResult.ALREADY_HELD
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

    /** Token generated for the last spawn; armed by HelperBootstrap BEFORE spawning and restored
     *  from prefs after a process restart, so a live daemon's re-announce is still authenticated.
     *  Read freely (the dump prints it); every write goes through [armToken] / [restore]. */
    @Volatile var expectedToken: String? = null
        private set

    /** Guards the read-modify-write of [expectedToken]: the receiver thread restores it from
     *  prefs while HelperBootstrap arms a fresh spawn on Dispatchers.IO, and a lost update there
     *  costs the new daemon its authentication for the rest of the process lifetime. */
    private val tokenLock = Any()

    /** Arms (or clears) the token for a spawn. Wins over a concurrent [restore]. */
    internal fun armToken(token: String?) {
        synchronized(tokenLock) { expectedToken = token }
    }

    /** True while HelperBootstrap waits for a daemon it just spawned. Only distinguishes a first
     *  delivery from a re-announce in the log — no decision hangs off it. */
    @Volatile var spawnInFlight: Boolean = false

    /** "none" until a binder is accepted, "broadcast" while one is held. For the diagnostic dump. */
    @Volatile var transport: String = TRANSPORT_NONE
        private set

    /** When the held binder arrived (System.currentTimeMillis), 0 when none. */
    @Volatile var receivedAt: Long = 0L
        private set

    /** Why the last intent was rejected, null when none was. For the diagnostic dump. */
    @Volatile var lastReject: String? = null
        private set

    /**
     * Called right after a binder was accepted and stored — the one signal every daemon spawn
     * path shares, whoever called ensureRunning(). Runs on the receiver's thread (the main
     * thread), so it must return immediately: launch a coroutine, never block or transact.
     */
    @Volatile var onAccepted: (() -> Unit)? = null

    /**
     * Installs [callback] and fires it at once when a binder is already held. A re-announce can
     * start the process and be accepted before TrackingService exists: the callback was null then,
     * and every later broadcast is turned away as already_held — so without this the daemon would
     * never learn that a client holds it and would keep broadcasting for the life of the process.
     */
    internal fun installOnAccepted(callback: (() -> Unit)?) {
        onAccepted = callback
        if (callback == null || binder == null) return
        Log.i(TAG, "onAccepted installed with a binder already held")
        runCatching { callback() }
            .onFailure { Log.w(TAG, "onAccepted callback threw: ${it.message}") }
    }

    const val TRANSPORT_NONE = "none"
    const val TRANSPORT_BROADCAST = "broadcast"

    /** Same SharedPreferences file HelperBootstrap keeps its daemon bookkeeping in. */
    const val PREFS_NAME = "helper"
    /** Token of the last spawn — survives the app process so a live daemon can be re-adopted. */
    const val KEY_SPAWN_TOKEN = "helper_spawn_token"
    /** Set to [TRANSPORT_BROADCAST] once a broadcast binder was accepted; never written on the
     *  addService path, so on Leopard 3 it stays absent and nothing waits for a re-announce. */
    const val KEY_LAST_TRANSPORT = "helper_last_transport"

    private const val TAG = "HelperBinderRx"

    /**
     * Re-arms [expectedToken] from the persisted spawn token after a process restart — without it
     * a recreated process rejects the live daemon's re-announce as not_expected and the bootstrap
     * kills a perfectly healthy daemon. Called from the receiver's onReceive (the earliest point
     * that both has a Context and matters: nothing else in the process needs the token), and
     * never overwrites a token armed by a spawn in flight.
     */
    internal fun restore(prefs: SharedPreferences) {
        // Prefs are read outside the lock (disk); only the compare-and-set is guarded, so a token
        // armed for a spawn in flight is never overwritten by the stored one.
        val persisted = prefs.getString(KEY_SPAWN_TOKEN, null)?.takeIf { it.isNotEmpty() } ?: return
        synchronized(tokenLock) {
            if (expectedToken == null) expectedToken = persisted
        }
    }

    /**
     * Authenticates an incoming [ACTION_BINDER][HelperBinderProtocol.ACTION_BINDER] payload and
     * stores the binder on success. Returns the verdict so the receiver can log it.
     */
    internal fun accept(bundle: Bundle?): BinderAcceptResult {
        val incoming = bundle?.getBinder(HelperBinderProtocol.KEY_BINDER)
        // One read under the lock: a token being armed mid-decision must not be seen half-way.
        val expected = synchronized(tokenLock) { expectedToken }
        // Nothing below this point may touch `incoming` until the token has matched.
        decideBinderAccept(
            hasBinder = incoming != null,
            token = bundle?.getString(HelperBinderProtocol.KEY_TOKEN),
            expectedToken = expected,
            alreadyHolding = binder != null,
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
        // The token stays armed on purpose — the same daemon re-announces to every new app
        // process. What keeps a replay out is the holder itself: see BinderAcceptResult.ALREADY_HELD.
        Log.i(TAG, "accepted via=${if (spawnInFlight) "first" else "re-announce"}")
        runCatching { onAccepted?.invoke() }
            .onFailure { Log.w(TAG, "onAccepted callback threw: ${it.message}") }
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
        // A process recreated while the daemon lives has no token in memory; this is the first
        // place in such a process that needs one, so it is also where the persisted one is re-armed.
        val prefs = context.getSharedPreferences(HelperBinderHolder.PREFS_NAME, Context.MODE_PRIVATE)
        HelperBinderHolder.restore(prefs)
        val bundle = intent.getBundleExtra(HelperBinderProtocol.EXTRA_BUNDLE)
        val verdict = HelperBinderHolder.accept(bundle)
        if (verdict == BinderAcceptResult.ACCEPTED) {
            // This firmware delivers the daemon by broadcast: remembering that is what lets a
            // recreated process wait for a re-announce instead of killing a live daemon. The token
            // is persisted only for a daemon that actually handed us a binder — a spawn whose
            // daemon died in the lock race (ALREADY_RUNNING) never overwrites the live one's token.
            prefs.edit()
                .putString(HelperBinderHolder.KEY_LAST_TRANSPORT, HelperBinderHolder.TRANSPORT_BROADCAST)
                .putString(HelperBinderHolder.KEY_SPAWN_TOKEN,
                    bundle?.getString(HelperBinderProtocol.KEY_TOKEN))
                .apply()
        }
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
