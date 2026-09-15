package com.bydmate.app.helper.push

import android.content.Context
import android.hardware.bydauto.BYDAutoEventValue
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.helper.dumpFidsCore
import java.io.File
import java.lang.reflect.Method

/**
 * Diagnostic recorder: subscribes to EVERY fid of whole devices and writes each changed value to
 * its own file in /sdcard/Download, so a drive tells which fid carries which sensor. logcat only
 * gets the summary (start, per-device registration, one line per minute, stop). Started from the
 * app switch or by broadcast on `-test` builds only; it never starts by itself, does not survive a
 * daemon restart, and stops itself at [MAX_BYTES] or [MAX_DURATION_MS].
 *
 * Separate from [FidPushRegistry] on purpose: the app's live push subscription must keep working
 * while a recording runs, and stopping the recorder may not touch the push listeners. The two
 * share only the device-class map and the listener sink type.
 *
 * Locking rule is the registry's: [lock] guards our tables only, and no vendor call (getInstance,
 * registerListener, unregisterListener) runs while it is held — the firmware dispatches events
 * under its own listener-map monitor and calls back into [record].
 */
internal object FidRecorder {

    private class Registration(
        val device: Any,
        val listener: Any,
        val unregister: Method,
        val dev: Int,
    )

    private class Counters(val dev: Int, var symbol: String) {
        var events = 0
        var lastInt = 0
        var lastDouble = 0.0
        var seen = false

        /** Wall clock of the last line written for this fid, valid only once [written] is true. */
        var lastWritten = 0L
        var written = false

        /** A change was throttled away; [tick] writes the current value once the gap allows it. */
        var pending = false
    }

    /** Internal rather than private so a test can assert it is free during a vendor call. */
    internal val lock = Any()
    private val registrations = mutableListOf<Registration>()
    private val rows = linkedMapOf<Int, FidRecDeviceRow>()
    private val counters = linkedMapOf<Int, Counters>()

    @Volatile private var running = false
    @Volatile private var log: FidRecLog? = null
    @Volatile private var ticker: Thread? = null
    @Volatile private var startedAtMs = 0L
    @Volatile private var lastSummaryMs = 0L

    /** Path and size of the last run, kept after a stop so the dump can still point at the file. */
    @Volatile private var filePath = ""
    @Volatile private var fileBytes = 0L

    /** System Context the vendor device classes are built with; installed by the daemon at startup. */
    @Volatile internal var context: Context? = null

    /** Test seams: the daemon always uses the real firmware classes. */
    internal var deviceFactory: (String) -> Any = ::recorderDeviceInstance
    internal var catalogSource: () -> String =
        { dumpFidsCore { name -> runCatching { Class.forName(name) }.getOrNull() } }
    internal var featureIdsSource: (Int) -> Set<Int>? = ::featureIdsOfDevice
    internal var listenerFactory: (Int, FidPushSink) -> Any? = ::recorderListenerFor
    internal var nowMs: () -> Long = System::currentTimeMillis
    internal var logDir: String = FidRecLog.DIR

    /** Tests drive [tick] themselves; the daemon runs it on its own thread. */
    internal var autoTick: Boolean = true

    /**
     * Starts a run over [devs], or over every device of [FID_PUSH_DEVICE_CLASSES] when [devs] is
     * empty. A previous run is stopped first, so a repeated start is a clean reinstall: new file,
     * empty counters.
     */
    fun start(devs: IntArray): FidRecStart {
        stop()
        val startedAt = nowMs()
        synchronized(lock) {
            rows.clear()
            counters.clear()
            startedAtMs = startedAt
            lastSummaryMs = startedAt
            fileBytes = 0L
        }
        val file = openRecorderLog(logDir, startedAt, nowMs)
        filePath = file?.file?.path.orEmpty()
        // No file, no run: the events would go nowhere, and the caps that end a forgotten
        // recording live in [tick], which needs the file — so nothing is registered at all.
        if (file == null) {
            Log.w(TAG, "rec not started: $FILE_UNAVAILABLE $logDir")
            return FidRecStart(0, 0, "$FILE_UNAVAILABLE $logDir")
        }
        log = file
        val catalog = runCatching { groupCatalogByDevice(catalogSource()) }.getOrElse { t ->
            Log.w(TAG, "rec catalog unavailable: ${describe(t)}")
            emptyMap()
        }
        val targets = (if (devs.isEmpty()) FID_PUSH_DEVICE_CLASSES.keys.toList() else devs.toList())
            .distinct()
            .sorted()
        file.header(
            listOf(
                "bydmate fid recorder versionCode=${BuildConfig.VERSION_CODE} " +
                    "started=${FidRecLog.startedAtText(startedAt)}",
                "devices=${targets.joinToString(",")}",
            )
        )
        val round = Round(file)
        try {
            for (dev in targets) startDevice(dev, catalog[dev].orEmpty(), round)
        } finally {
            // A round cut short must still be adopted: stop() can only undo the registrations it
            // can see, so they are published even when a device threw past its own handling.
            synchronized(lock) {
                registrations += round.made
                rows.putAll(round.out)
                running = round.made.isNotEmpty()
            }
        }
        val fresh = round.out
        val registered = fresh.values.sumOf { it.registered }
        Log.i(TAG, "rec started: devices=${fresh.size} registered=$registered file=$filePath")
        if (running && autoTick) startTicker()
        return FidRecStart(fresh.size, registered)
    }

    /** Unregisters every recorder listener and closes the file. Returns how many were undone. */
    fun stop(): Int = stop(REASON_MANUAL)

    fun status(): FidRecStatus = synchronized(lock) {
        val events = counters.values.groupingBy { it.dev }.fold(0) { acc, c -> acc + c.events }
        FidRecStatus(
            running = running,
            devices = rows.values.map { it.copy(events = events[it.device] ?: 0) },
            totalEvents = counters.values.sumOf { it.events },
            top = counters.entries
                .sortedByDescending { it.value.events }
                .take(REC_TOP_FIDS)
                .map { (fid, c) -> FidRecTopRow(fid, c.symbol, c.events, c.lastInt, c.lastDouble) },
            filePath = filePath,
            fileBytes = log?.bytes ?: fileBytes,
        )
    }

    /**
     * One housekeeping step: writes the values the throttle deferred, flushes, logs the per-minute
     * summary and enforces the size and duration caps. Runs on the recorder's own thread every
     * [TICK_MS]; tests call it directly.
     */
    internal fun tick() {
        val now = nowMs()
        val sink = log ?: return
        val due = synchronized(lock) {
            counters.entries
                .filter { it.value.pending && now - it.value.lastWritten >= MIN_LINE_INTERVAL_MS }
                .map { (fid, c) ->
                    c.pending = false
                    c.lastWritten = now
                    c.written = true
                    RecEvent(now, c.dev, fid, c.symbol, c.lastInt, c.lastDouble)
                }
        }
        due.forEach { sink.event(it) }
        sink.flushIfDue(now)
        when {
            sink.bytes >= MAX_BYTES -> stop(REASON_SIZE)
            now - startedAtMs >= MAX_DURATION_MS -> stop(REASON_TIME)
            now - lastSummaryMs >= SUMMARY_INTERVAL_MS -> {
                lastSummaryMs = now
                Log.i(TAG, "rec running: events=${status().totalEvents} bytes=${sink.bytes} file=$filePath")
            }
        }
    }

    private fun startTicker() {
        val thread = Thread({
            while (running) {
                Thread.sleep(TICK_MS)
                runCatching { tick() }.onFailure { Log.w(TAG, "rec tick failed: $it") }
            }
        }, "fid-recorder")
        thread.isDaemon = true
        ticker = thread
        thread.start()
    }

    private fun stop(reason: String): Int {
        val taken = synchronized(lock) {
            val list = registrations.toList()
            registrations.clear()
            running = false
            list
        }
        for (reg in taken) {
            runCatching { reg.unregister.invoke(reg.device, reg.listener) }
                .onFailure { Log.w(TAG, "rec unregister dev=${reg.dev} failed: ${describe(it)}") }
        }
        val sink = log
        if (sink != null) {
            val events = status().totalEvents
            sink.close("stopped: $reason events=$events")
            fileBytes = sink.bytes
            log = null
            Log.i(TAG, "rec stopped: $reason devices=${taken.size} events=$events bytes=$fileBytes file=$filePath")
        }
        // The ticker exits on its own once running is false; joining from inside it would hang.
        val thread = ticker
        ticker = null
        if (thread != null && thread !== Thread.currentThread()) runCatching { thread.join(TICK_MS * 4) }
        return taken.size
    }

    /** Tables one start round fills in before they are published under [lock]. */
    private class Round(val file: FidRecLog?) {
        val out = linkedMapOf<Int, FidRecDeviceRow>()
        val made = mutableListOf<Registration>()
    }

    /** Vendor calls only — must run with [lock] free. */
    private fun startDevice(dev: Int, symbols: List<FidRecSymbol>, round: Round) {
        val out = round.out
        val made = round.made
        val file = round.file
        val className = FID_PUSH_DEVICE_CLASSES[dev]
        val sink = FidPushSink { fid, intValue, doubleValue, _ -> record(dev, fid, intValue, doubleValue) }
        // A firmware missing one AbsBYDAuto*Listener throws NoClassDefFoundError here, and an
        // Error escaping this function would end the whole round — it must cost this device only.
        val built = runCatching { className?.let { listenerFactory(dev, sink) } }
        val listener = built.getOrNull()
        if (className == null || listener == null) {
            val text = built.exceptionOrNull()?.let { describe(it) } ?: "no listener class"
            out[dev] = FidRecDeviceRow(dev, 0, symbols.size, 0, 0, text)
            reportRegistration(file, "rec dev=$dev: $text")
            return
        }
        val features = featureIdsSource(dev)
        val wanted = if (features == null) symbols else symbols.filter { it.fid in features }
        val filtered = symbols.size - wanted.size
        if (wanted.isEmpty()) {
            out[dev] = FidRecDeviceRow(dev, 0, symbols.size, filtered, 0, FID_REC_NO_ERROR)
            reportRegistration(file, "rec dev=$dev $className: registered=0/${symbols.size} filtered=$filtered err=-")
            return
        }
        val handle = runCatching {
            val device = deviceFactory(className)
            Triple(
                device,
                findRecorderMethod(device, listener, "registerListener", withFeatureIds = true),
                findRecorderMethod(device, listener, "unregisterListener", withFeatureIds = false),
            )
        }.getOrElse { t ->
            val text = describe(t)
            out[dev] = FidRecDeviceRow(dev, 0, symbols.size, filtered, 0, text)
            reportRegistration(file, "rec dev=$dev $className: registered=0/${symbols.size} filtered=$filtered err=$text")
            return
        }
        val (device, register, unregister) = handle
        // Symbols go in BEFORE the vendor call: events of the devices already registered arrive
        // while the later ones are still registering, and a counter [record] creates on its own
        // can only carry [UNKNOWN_SYMBOL].
        synchronized(lock) {
            wanted.forEach {
                val c = counters.getOrPut(it.fid) { Counters(dev, it.symbol) }
                if (c.symbol == UNKNOWN_SYMBOL) c.symbol = it.symbol
            }
        }
        val error = registerDevice(device, register, listener, wanted)
        val registered = confirmed(device, wanted)
        out[dev] = FidRecDeviceRow(dev, registered, symbols.size, filtered, 0, error)
        if (registered > 0) made += Registration(device, listener, unregister, dev)
        reportRegistration(file, "rec dev=$dev $className: registered=$registered/${symbols.size} filtered=$filtered err=$error")
    }

    /**
     * One batch [registerListener] call, falling back to one call per fid when the device's own
     * listener map says the batch registered nothing: the firmware's checkDeviceFeatures rejects
     * the WHOLE array when a single id is not in the device's feature map.
     */
    private fun registerDevice(
        device: Any,
        register: Method,
        listener: Any,
        wanted: List<FidRecSymbol>,
    ): String {
        val batch = runCatching {
            register.invoke(device, listener, wanted.map { it.fid }.toIntArray())
        }
        if (batch.isFailure) return describe(batch.exceptionOrNull()!!)
        if (confirmed(device, wanted) > 0) return FID_REC_NO_ERROR
        var lastError: String? = null
        for (symbol in wanted) {
            runCatching { register.invoke(device, listener, intArrayOf(symbol.fid)) }
                .onFailure { lastError = describe(it) }
        }
        return lastError ?: FID_REC_NO_ERROR
    }

    /** How many of [wanted] the device's listener map confirms; all of them when it is unreadable. */
    private fun confirmed(device: Any, wanted: List<FidRecSymbol>): Int {
        val contains = containsIdOf(device) ?: return wanted.size
        return wanted.count { runCatching { contains(it.fid) }.getOrDefault(false) }
    }

    /**
     * Vendor binder thread: count, then decide whether this change earns a line right now. A fid
     * that already got a line less than [MIN_LINE_INTERVAL_MS] ago is marked pending instead —
     * [tick] writes its current value as soon as the gap allows, so the last value of a burst
     * always lands, and a window sweeping 1 % at a time cannot flood the file.
     */
    private fun record(dev: Int, fid: Int, intValue: Int, doubleValue: Double) {
        val now = nowMs()
        val event = synchronized(lock) {
            val c = counters.getOrPut(fid) { Counters(dev, UNKNOWN_SYMBOL) }
            val changed = !c.seen || c.lastInt != intValue || c.lastDouble != doubleValue
            c.events++
            c.seen = true
            c.lastInt = intValue
            c.lastDouble = doubleValue
            when {
                !changed -> null
                !c.written || now - c.lastWritten >= MIN_LINE_INTERVAL_MS -> {
                    c.lastWritten = now
                    c.written = true
                    c.pending = false
                    RecEvent(now, dev, fid, c.symbol, intValue, doubleValue)
                }
                else -> {
                    c.pending = true
                    null
                }
            }
        } ?: return
        log?.event(event)
    }

    /** Housekeeping period: four times per [MIN_LINE_INTERVAL_MS] window. */
    private const val TICK_MS = 250L

    /** Minimum gap between two lines of the SAME fid — at most two lines per second per fid. */
    private const val MIN_LINE_INTERVAL_MS = 500L

    /** Caps that stop a forgotten recording: 400 MB and 3 hours. */
    private const val MAX_BYTES = 400L * 1024 * 1024
    private const val MAX_DURATION_MS = 3 * 60 * 60 * 1000L

    private const val SUMMARY_INTERVAL_MS = 60_000L

    /** Symbol of a fid the catalog did not name — an event from outside the registered set. */
    private const val UNKNOWN_SYMBOL = "?"

    /** Start refusal reported back to the app when /sdcard/Download takes no file. */
    private const val FILE_UNAVAILABLE = "file unavailable:"

    private const val REASON_MANUAL = "manual"
    private const val REASON_SIZE = "size limit"
    private const val REASON_TIME = "time limit"

    private const val TAG = "FidRec"
}

/** Hands a recorder event to [FidPushSink], letting no throwable escape into the vendor stack. */
internal fun FidPushSink.onRecorderEvent(eventType: Int, value: BYDAutoEventValue?) {
    runCatching {
        onEvent(eventType, value?.intValue ?: 0, value?.doubleValue ?: 0.0, SystemClock.elapsedRealtime())
    }.onFailure { Log.w("FidRec", "sink failed for fid=$eventType: $it") }
}

/** Same Context rule as the push registry: without it the firmware never enables delivery. */
private fun recorderDeviceInstance(className: String): Any {
    val ctx = FidRecorder.context ?: error("daemon has no system Context")
    val type = Class.forName(className)
    return type.getMethod("getInstance", Context::class.java).invoke(null, ctx)
        ?: error("getInstance(context) returned null")
}

/**
 * The device's own feature map: `BYDAutoDeviceFeaturesMap.getFeatureIdsFromDevice(dev)`. Null when
 * the class or the method is absent — the caller then registers the whole catalog list and lets
 * the firmware refuse what it does not know.
 */
private fun featureIdsOfDevice(dev: Int): Set<Int>? = runCatching {
    val type = Class.forName("android.hardware.bydauto.BYDAutoDeviceFeaturesMap")
    val method = type.getMethod("getFeatureIdsFromDevice", Int::class.javaPrimitiveType)
    @Suppress("UNCHECKED_CAST")
    (method.invoke(null, dev) as? Collection<Int>)?.toSet()
}.getOrNull()

/** `containsId` of the device's listener map, or null when the map cannot be read. */
private fun containsIdOf(device: Any): ((Int) -> Boolean)? {
    val map = recorderListenerMap(device) ?: return null
    val method = runCatching {
        map.javaClass.getMethod("containsId", Int::class.javaPrimitiveType).also { it.isAccessible = true }
    }.getOrNull() ?: return null
    return { fid -> method.invoke(map, fid) as? Boolean ?: false }
}

/** The field is private on AbsBYDAutoDevice, so the walk starts at the concrete device. */
private fun recorderListenerMap(device: Any): Any? {
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

private fun findRecorderMethod(
    target: Any,
    listener: Any,
    name: String,
    withFeatureIds: Boolean,
): Method =
    target.javaClass.methods.firstOrNull { method ->
        method.name == name &&
            method.parameterTypes.size == (if (withFeatureIds) 2 else 1) &&
            method.parameterTypes[0].isAssignableFrom(listener.javaClass) &&
            (!withFeatureIds || method.parameterTypes[1] == IntArray::class.java)
    } ?: throw NoSuchMethodException(name)

/** Reflection hides the real error inside InvocationTargetException — report the root. */
private fun describe(t: Throwable): String {
    val root = generateSequence(t) { it.cause }.last()
    return "${root.javaClass.simpleName}: ${root.message ?: "(no message)"}"
}

/** One recorded change, from the vendor thread or the throttle's deferred queue to the file. */
internal class RecEvent(
    val atMs: Long,
    val dev: Int,
    val fid: Int,
    val symbol: String,
    val intValue: Int,
    val doubleValue: Double,
)

/** Registration outcomes belong in both places: the file explains itself, logcat keeps them. */
private fun reportRegistration(file: FidRecLog?, line: String) {
    Log.i("FidRec", line)
    file?.header(listOf(line))
}

/** Opens the run's file; null (and a warning) when /sdcard/Download refuses the write. */
private fun openRecorderLog(dir: String, startedAt: Long, nowMs: () -> Long): FidRecLog? {
    val file = File(dir, FidRecLog.nameFor(startedAt))
    return runCatching { FidRecLog(file, nowMs) }.getOrElse {
        Log.w("FidRec", "rec file unavailable (${file.path}): $it")
        null
    }
}
