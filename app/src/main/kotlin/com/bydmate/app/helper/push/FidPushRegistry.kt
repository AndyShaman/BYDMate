package com.bydmate.app.helper.push

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.bydmate.app.helper.HelperBinderProtocol
import java.lang.reflect.Method

/**
 * Daemon side of the fid push channel: owns the vendor listeners and forwards every event to the
 * app's callback binder. Registration is refused to the app uid (SecurityException from
 * enableDevice), which is why it lives here — the daemon runs under the shell uid.
 *
 * One listener instance per device, registered ONE fid at a time: the framework's
 * registerListener(l, int[]) runs checkDeviceFeatures over the whole array and silently registers
 * nothing when a single id is not in the device's feature map, so a batch call would hide which fid
 * was the bad one. The firmware accumulates ids per listener, so the per-fid calls add up.
 *
 * Locking rule: [lock] guards our own tables and nothing else. No vendor call (getInstance,
 * registerListener, unregisterListener) may run while it is held — AbsBYDAutoDevice.onPostEvent
 * dispatches the whole listener loop under the firmware's own listener-map monitor and calls our
 * [deliver], which takes [lock], so holding [lock] across a vendor call is a lock-order inversion
 * that deadlocks a resubscribe landing during an event.
 */
internal object FidPushRegistry {

    private class Registration(
        val device: Any,
        val listener: Any,
        val unregister: Method,
        val dev: Int,
    )

    private class Counters {
        var events = 0
        var lastInt = 0
        var lastDouble = 0.0
        var lastTs = 0L
    }

    /** Internal rather than private so the tests can assert it is free during a vendor call. */
    internal val lock = Any()
    private val registrations = mutableListOf<Registration>()
    private val results = linkedMapOf<Int, FidPushResult>()
    private val counters = linkedMapOf<Int, Counters>()

    /**
     * Last value per fid waiting for the next flush. A LinkedHashMap, so a fid that keeps firing
     * inside one window is replaced in place and the packet stays in arrival order.
     */
    private val pending = linkedMapOf<Int, FidPushEvent>()

    @Volatile private var callback: IBinder? = null
    @Volatile private var deathRecipient: IBinder.DeathRecipient? = null
    @Volatile private var deliverErrors = 0
    @Volatile private var consecutiveErrors = 0
    /** Internal rather than private so a test can watch it end. */
    @Volatile internal var flusher: Thread? = null
        private set

    // Delivery totals, all guarded by [lock].
    private var packetsSent = 0
    private var eventsSent = 0
    private var coalesced = 0
    private var lastFlushAt = 0L
    private var lastFlushLogAt = 0L

    /** Test seams: the daemon always uses the real vendor device and listener classes. */
    internal var deviceFactory: (String) -> Any = ::deviceInstance
    internal var listenerFactory: (Int, FidPushSink) -> Any? = ::pushListenerFor

    /** Test seams: the daemon runs the flusher on its own thread and reads the real clock. */
    internal var autoFlush: Boolean = true
    internal var clock: () -> Long = android.os.SystemClock::elapsedRealtime

    /** System Context the vendor device classes are built with; installed by the daemon at startup. */
    @Volatile internal var context: Context? = null

    /**
     * (Re)installs the whole subscription for [binder]: everything registered before is dropped
     * first, so a repeated subscribe from a restarted app process is a clean reinstall rather than
     * a second set of listeners on the same fids.
     */
    fun subscribe(subs: List<FidPushSub>, binder: IBinder?, callerUid: Int): List<FidPushResult> {
        Log.i(TAG, "subscribe: ${subs.size} fids from uid=$callerUid")
        unregisterAll(synchronized(lock) { detachLocked() })
        if (binder == null) return emptyList()
        synchronized(lock) {
            // Adopt the callback before anything is registered: a death notice arriving mid-round
            // must find it, and a binder that is already dead is no callback at all (the link
            // throws, and the round below then finds nothing to adopt).
            val recipient = IBinder.DeathRecipient { dropDeadCallback() }
            callback = binder
            consecutiveErrors = 0
            runCatching { binder.linkToDeath(recipient, 0) }
                .onSuccess { deathRecipient = recipient }
                .onFailure {
                    callback = null
                    deathRecipient = null
                }
        }

        val grouping = groupFidsByDevice(subs)
        val deviceOf = subs.associate { it.fid to it.device }
        val fresh = linkedMapOf<Int, FidPushResult>()
        val made = mutableListOf<Registration>()
        for (fid in grouping.unsupported) {
            Log.i(TAG, "register dev=${deviceOf[fid]} fid=$fid -> $FID_PUSH_UNSUPPORTED")
            fresh[fid] = FidPushResult(fid, deviceOf[fid] ?: 0, FID_PUSH_UNSUPPORTED)
        }
        for ((dev, fids) in grouping.byDevice) registerDevice(dev, fids, fresh, made)

        val installed = synchronized(lock) { adoptLocked(binder, fresh, made) }
        // The callback died while we were registering: our tables are already empty, undo the
        // listeners we just made instead of leaving them behind.
        if (installed == null) {
            unregisterAll(made)
            return emptyList()
        }
        // One flusher thread per live callback; it exits on its own once the subscription is
        // dropped — the loop reads the registry's own field, which detachLocked() nulls.
        if (autoFlush && flusher?.isAlive != true) {
            val thread = Thread({
                while (this.callback != null) {
                    Thread.sleep(PUSH_FLUSH_MS)
                    runCatching { flush() }.onFailure { Log.w(TAG, "push flush failed: $it") }
                }
            }, "fid-push-flush")
            thread.isDaemon = true
            flusher = thread
            thread.start()
        }
        return installed
    }

    fun unsubscribe(): Int = unregisterAll(synchronized(lock) { detachLocked() })

    fun status(): FidPushStatus = synchronized(lock) {
        FidPushStatus(
            rows = results.values.map { result ->
                val c = counters[result.fid]
                val events = c?.events ?: 0
                FidPushStatusRow(
                    fid = result.fid,
                    device = result.device,
                    // An unconfirmed registration is proven by its first event.
                    outcome = if (result.outcome == FID_PUSH_SENT && events > 0) {
                        FID_PUSH_OK
                    } else {
                        result.outcome
                    },
                    events = events,
                    lastIntValue = c?.lastInt ?: 0,
                    lastDoubleValue = c?.lastDouble ?: 0.0,
                    lastTsElapsed = c?.lastTs ?: 0L,
                )
            },
            callbackAlive = callback?.isBinderAlive == true,
            deliverErrors = deliverErrors,
            packets = packetsSent,
            events = eventsSent,
            coalesced = coalesced,
        )
    }

    /** Vendor calls only — must run with [lock] free. Results go into [out], listeners into [made]. */
    private fun registerDevice(
        dev: Int,
        fids: List<Int>,
        out: MutableMap<Int, FidPushResult>,
        made: MutableList<Registration>,
    ) {
        val className = FID_PUSH_DEVICE_CLASSES[dev]
        val sink = FidPushSink { fid, intValue, doubleValue, tsElapsed ->
            deliver(fid, intValue, doubleValue, tsElapsed)
        }
        // Building the listener is a vendor call too: a firmware missing one AbsBYDAuto*Listener
        // throws NoClassDefFoundError here, and an Error escaping this function would end the
        // whole round — the devices already registered would then be unreachable for unsubscribe.
        // It must cost this device only.
        val built = runCatching { className?.let { listenerFactory(dev, sink) } }
        val listener = built.getOrNull()
        if (className == null || listener == null) {
            val text = built.exceptionOrNull()?.let { describe(it) } ?: FID_PUSH_UNSUPPORTED
            fids.forEach {
                Log.i(TAG, "register dev=$dev fid=$it -> $text")
                out[it] = FidPushResult(it, dev, text)
            }
            return
        }
        // One failure here (device class absent, getInstance refused, listener API renamed) applies
        // to every fid of the device, so it is reported once per fid and the device is skipped.
        val handle = runCatching {
            val device = deviceFactory(className)
            Triple(
                device,
                findListenerMethod(device, listener, "registerListener", withFeatureIds = true),
                findListenerMethod(device, listener, "unregisterListener", withFeatureIds = false),
            )
        }.getOrElse { t ->
            val text = describe(t)
            fids.forEach {
                Log.i(TAG, "register dev=$dev fid=$it -> $text")
                out[it] = FidPushResult(it, dev, text)
            }
            return
        }
        val (device, register, unregister) = handle
        var anyLive = false
        for (fid in fids) {
            val outcome = runCatching {
                register.invoke(device, listener, intArrayOf(fid))
                confirmRegistration(device, fid)
            }.getOrElse { describe(it) }
            Log.i(TAG, "register dev=$dev fid=$fid -> $outcome")
            out[fid] = FidPushResult(fid, dev, outcome)
            if (outcome == FID_PUSH_OK || outcome == FID_PUSH_SENT) anyLive = true
        }
        if (anyLive) made += Registration(device, listener, unregister, dev)
    }

    /**
     * Vendor binder thread: count the event, then buffer it for the next flush. With every
     * FidMap field subscribed the firmware pushes the busiest fids several times a second
     * each, so one transact (and one log line) per event would flood both the Binder and
     * logcat — the buffer keeps the last value per fid and [flush] sends them in one packet.
     *
     * A fid arriving into an empty buffer after a quiet window is flushed at once: a turn
     * signal or a door must not wait out the interval.
     */
    private fun deliver(fid: Int, intValue: Int, doubleValue: Double, tsElapsed: Long) {
        val immediate = synchronized(lock) {
            val c = counters.getOrPut(fid) { Counters() }
            c.events++
            c.lastInt = intValue
            c.lastDouble = doubleValue
            c.lastTs = tsElapsed
            if (callback == null) return
            if (pending.put(fid, FidPushEvent(fid, intValue, doubleValue, tsElapsed)) != null) {
                coalesced++
            }
            pending.size == 1 && clock() - lastFlushAt >= PUSH_FLUSH_MS
        }
        if (immediate) flush()
    }

    /**
     * Sends everything the buffer holds as one oneway transact, and logs the totals no more than
     * once per [FLUSH_LOG_INTERVAL_MS]. A callback that keeps refusing packets is dropped.
     *
     * Runs on the flusher thread, and on the test thread when [autoFlush] is off — internal for
     * the latter.
     */
    internal fun flush() {
        val batch = synchronized(lock) {
            if (pending.isEmpty() || callback == null) return
            lastFlushAt = clock()
            val taken = pending.values.toList()
            pending.clear()
            taken
        }
        val target = callback ?: return
        val data = Parcel.obtain()
        runCatching {
            data.writeInterfaceToken(HelperBinderProtocol.PUSH_CALLBACK_DESCRIPTOR)
            writePushEvents(data, batch)
            target.transact(HelperBinderProtocol.TX_PUSH_EVENT, data, null, IBinder.FLAG_ONEWAY)
        }.onSuccess {
            consecutiveErrors = 0
            val line = synchronized(lock) {
                packetsSent++
                eventsSent += batch.size
                val now = clock()
                if (now - lastFlushLogAt >= FLUSH_LOG_INTERVAL_MS) {
                    lastFlushLogAt = now
                    "push flush: events=$eventsSent fids=${counters.size} coalesced=$coalesced"
                } else {
                    null
                }
            }
            line?.let { Log.i(TAG, it) }
        }.onFailure { t ->
            deliverErrors++
            consecutiveErrors++
            Log.w(TAG, "deliver failed: ${describe(t)} ($consecutiveErrors in a row)")
            if (consecutiveErrors >= MAX_DELIVER_ERRORS) dropDeadCallback()
        }
        data.recycle()
    }

    /**
     * Drops the subscription for a callback that no longer answers. Reached from the vendor
     * dispatch thread, which holds the firmware's listener-map monitor, so the vendor unregister
     * cannot run here at all — it goes to a throwaway thread that holds neither monitor.
     */
    private fun dropDeadCallback() {
        val orphans = synchronized(lock) { detachLocked() }
        Log.w(TAG, "callback died -> unregistered ${orphans.size}")
        if (orphans.isNotEmpty()) Thread { unregisterAll(orphans) }.start()
    }

    /** Publishes a finished registration round, or null when [expected] is no longer the callback. */
    private fun adoptLocked(
        expected: IBinder,
        fresh: Map<Int, FidPushResult>,
        made: List<Registration>,
    ): List<FidPushResult>? {
        if (callback !== expected) return null
        registrations += made
        results.putAll(fresh)
        for ((fid, result) in fresh) {
            if (result.outcome == FID_PUSH_OK || result.outcome == FID_PUSH_SENT) {
                counters.getOrPut(fid) { Counters() }
            }
        }
        return results.values.toList()
    }

    /**
     * Clears our tables and the callback, and hands back the registrations the caller has to undo
     * with [unregisterAll] once [lock] is released.
     */
    private fun detachLocked(): List<Registration> {
        val taken = registrations.toList()
        registrations.clear()
        results.clear()
        counters.clear()
        pending.clear()
        deathRecipient?.let { r -> callback?.let { runCatching { it.unlinkToDeath(r, 0) } } }
        deathRecipient = null
        callback = null
        consecutiveErrors = 0
        // The delivery totals belong to one subscription: a fresh one starts its own flush
        // window, so the dump reports what the daemon did for the app that is there now.
        packetsSent = 0
        eventsSent = 0
        coalesced = 0
        lastFlushAt = 0L
        lastFlushLogAt = 0L
        return taken
    }

    /** Vendor calls — never invoke with [lock] held. Returns how many registrations were undone. */
    private fun unregisterAll(regs: List<Registration>): Int {
        for (reg in regs) {
            runCatching { reg.unregister.invoke(reg.device, reg.listener) }
                .onFailure { Log.w(TAG, "unregister dev=${reg.dev} failed: ${describe(it)}") }
        }
        return regs.size
    }

    private const val TAG = "FidPush"

    /** Consecutive delivery failures after which the callback counts as dead. */
    private const val MAX_DELIVER_ERRORS = 3

    /** How long a value may sit in the buffer before it is sent. */
    private const val PUSH_FLUSH_MS = 200L

    /** One delivery line per this many ms, whatever the event rate. */
    private const val FLUSH_LOG_INTERVAL_MS = 5_000L
}

/**
 * The vendor devices need a real Context: AbsBYDAutoDevice builds its BYDAutoDeviceManager from
 * `context.getSystemService("auto")`, and without that manager registerListener only fills the
 * local listener map and never calls enableDevice, so no event ever arrives (on-car 2026-09-15:
 * 14 fids, 3 "OK", 0 events). Gearbox and Bodywork additionally refuse a null Context outright.
 * The daemon installs its system Context into [FidPushRegistry.context] at startup.
 */
private fun deviceInstance(className: String): Any {
    val ctx = FidPushRegistry.context ?: error("daemon has no system Context")
    val type = Class.forName(className)
    return type.getMethod("getInstance", Context::class.java).invoke(null, ctx)
        ?: error("getInstance(context) returned null")
}

/**
 * BYDAutoBodyworkDevice.getInstance runs enforceCallingOrSelfPermission(BYDAUTO_BODYWORK_COMMON)
 * on the Context it is given. The shell uid is not a package, so the local check is not the
 * authority here: autoservice enforces the permission itself on enableDevice, and that verdict
 * is what the result table reports.
 */
internal class PermissiveContext(base: Context) : ContextWrapper(base) {
    override fun enforceCallingOrSelfPermission(permission: String, message: String?) = Unit
}

/**
 * registerListener() returns silently when checkDeviceFeatures refuses the fid, so the call itself
 * proves nothing. The device's own listener map does: containsId(fid) is true only after the fid
 * was really added, and only this process registers anything in the daemon.
 */
private fun confirmRegistration(device: Any, fid: Int): String {
    val map = listenerMapOf(device) ?: return FID_PUSH_SENT
    // The map is a private inner class of AbsBYDAutoDevice; containsId is public on its supertype.
    val contains = runCatching {
        val method = map.javaClass.getMethod("containsId", Int::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(map, fid) as? Boolean
    }.getOrNull() ?: return FID_PUSH_SENT
    return if (contains) FID_PUSH_OK else FID_PUSH_NOT_IN_FEATURE_MAP
}

/** The field is declared private on AbsBYDAutoDevice, so the walk starts at the concrete device. */
private fun listenerMapOf(device: Any): Any? {
    var type: Class<*>? = device.javaClass
    while (type != null) {
        val field = runCatching { type?.getDeclaredField("mIBYDAutoListenerMap") }.getOrNull()
        if (field != null) {
            return runCatching {
                field.isAccessible = true
                field.get(device)
            }.getOrNull()
        }
        type = type.superclass
    }
    return null
}

private fun findListenerMethod(target: Any, listener: Any, name: String, withFeatureIds: Boolean): Method =
    target.javaClass.methods.firstOrNull { matchesListenerMethod(it, listener, name, withFeatureIds) }
        ?: throw NoSuchMethodException(name)

private fun matchesListenerMethod(
    method: Method,
    listener: Any,
    name: String,
    withFeatureIds: Boolean,
): Boolean {
    if (method.name != name) return false
    val p = method.parameterTypes
    if (p.size != (if (withFeatureIds) 2 else 1)) return false
    if (!p[0].isAssignableFrom(listener.javaClass)) return false
    return !withFeatureIds || p[1] == IntArray::class.java
}

/** Reflection hides the real error inside InvocationTargetException — report the root. */
private fun describe(t: Throwable): String {
    val root = generateSequence(t) { it.cause }.last()
    return "${root.javaClass.simpleName}: ${root.message ?: "(no message)"}"
}
