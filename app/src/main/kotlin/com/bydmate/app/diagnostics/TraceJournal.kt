package com.bydmate.app.diagnostics

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Always-on journal of what the app did and why: the driver pressed PTT, the phrase went to the
 * agent, the tool asked Overpass, Overpass answered 406, the tool failed. One short line per
 * state change or decision, linked by `by=#id` to the event that caused it, so a log shared
 * after the fact shows the chain behind a failure — including everything before «Запись логов»
 * was pressed (the recorder starts with `logcat -c`).
 *
 * Bounded: the newest [maxEvents] events within [maxBytes], in one file in filesDir. An event
 * costs its caller one sanitized string and a send to a channel bounded at [QUEUE_CAPACITY]; if
 * the worker falls behind (slow storage), further events are dropped rather than piling up in
 * memory or blocking the caller — the drops are counted and surface as one `trace-dropped` line
 * once the worker catches up. Ids across restarts, logcat (tag [TAG]) and all file work run on one
 * worker coroutine on [dispatcher], and the file is appended in batches: [FLUSH_BATCH] new events,
 * [FLUSH_INTERVAL_MS] after the first unsaved one, or [flush]. Never throws into the caller.
 */
@Singleton
@Suppress("LongParameterList") // production defaults collapse this to one arg; the rest are test seams
class TraceJournal internal constructor(
    private val file: File?,
    dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logcat: (String) -> Unit = { Log.i(TAG, it) },
    // Seams for tests: the production caps take thousands of events to reach.
    maxEvents: Int = MAX_EVENTS,
    maxBytes: Int = MAX_BYTES,
    queueCapacity: Int = QUEUE_CAPACITY,
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, FILE_NAME), Dispatchers.IO)

    private sealed interface Op {
        class Add(val handle: Long, val atMs: Long, val body: String, val by: Long?) : Op
        class Repeat(val handle: Long, val atMs: Long, val count: Int) : Op
        class Flush(val done: CompletableDeferred<Unit>?) : Op
        class Snapshot(val reply: CompletableDeferred<List<String>>) : Op
    }

    private val ops = Channel<Op>(queueCapacity)
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, e -> Log.w(TAG, "trace worker failed: ${e.javaClass.simpleName}") },
    )

    // Caller side, guarded by [lock]: ids are handed out here, so they are in call order.
    // A handle is process-local; the worker adds the highest id found in the file to it, so
    // the ids a dump shows keep growing across restarts without the caller waiting for the read.
    private val lock = Any()
    private var nextHandle = 0L
    private var lastKey: String? = null
    private var lastHandle = 0L
    private var lastCount = 0
    // Events the queue had no room for; drained by the worker into one "trace-dropped" line.
    private var dropped = 0

    // Worker side: touched only by the worker coroutine.
    private val ring = TraceRing(file, maxEvents, maxBytes)
    private var sinceFlush = 0
    private var timer: Job? = null

    init {
        scope.launch {
            val base = ring.load()
            for (op in ops) {
                // A backlog cleared since the last op: one line names how many were dropped,
                // through the normal add path so it lands in the file and the dump like any event.
                takeDropped().takeIf { it > 0 }?.let { record(TraceArea.APP, "trace-dropped", arrayOf("n" to it), null) }
                runCatching { apply(op, base) }
                    .onFailure { Log.w(TAG, "trace op failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** The count of events the queue had no room for since the last call, reset to 0. */
    private fun takeDropped(): Int = synchronized(lock) { val d = dropped; dropped = 0; d }

    fun event(area: TraceArea, what: String, vararg fields: Pair<String, Any?>, by: Long? = null): Long =
        record(area, what, fields, by)

    /** [event] without the vararg copy, for [Trace]. An identical event right after the last
     *  one (same text, same cause) is not a new line: the last line's counter grows instead. */
    internal fun record(area: TraceArea, what: String, fields: Array<out Pair<String, Any?>>, by: Long?): Long =
        runCatching {
            val body = body(area, what, fields)
            val cause = by?.takeIf { it > 0 }
            val atMs = clock()
            val key = if (cause == null) body else "$body|$cause"
            synchronized(lock) {
                if (key == lastKey) {
                    lastCount++
                    if (ops.trySend(Op.Repeat(lastHandle, atMs, lastCount)).isFailure) dropped++
                    lastHandle
                } else {
                    val handle = ++nextHandle
                    lastKey = key
                    lastHandle = handle
                    lastCount = 1
                    if (ops.trySend(Op.Add(handle, atMs, body, cause)).isFailure) dropped++
                    handle
                }
            }
        }.getOrDefault(0L)

    fun flush() {
        ops.trySend(Op.Flush(null))
    }

    /** For the shutdown hook: waits up to [timeoutMs] for the pending events to reach the file. */
    fun flushBlocking(timeoutMs: Long): Boolean {
        val done = CompletableDeferred<Unit>()
        if (ops.trySend(Op.Flush(done)).isFailure) return false
        return runBlocking { withTimeoutOrNull(timeoutMs) { done.await() } } != null
    }

    /** Every event, oldest first, with a `-- yyyy-MM-dd --` line wherever the day changes. */
    suspend fun lines(): List<String> {
        val reply = CompletableDeferred<List<String>>()
        if (ops.trySend(Op.Snapshot(reply)).isFailure) return emptyList()
        return withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { reply.await() }.orEmpty()
    }

    /** Tests only: stops the worker. */
    internal fun close() {
        ops.close()
        scope.cancel()
    }

    private fun apply(op: Op, base: Long) {
        when (op) {
            is Op.Add -> {
                val entry = TraceEntry(op.atMs, base + op.handle, op.body, op.by?.let { base + it })
                ring.add(entry)
                logcat(entry.tail())
                if (++sinceFlush >= FLUSH_BATCH) flushNow() else armTimer()
            }
            is Op.Repeat -> {
                ring.repeat(base + op.handle, op.atMs, op.count)
                armTimer()
            }
            is Op.Flush -> {
                flushNow()
                op.done?.complete(Unit)
            }
            is Op.Snapshot -> op.reply.complete(ring.render())
        }
    }

    private fun flushNow() {
        timer?.cancel()
        timer = null
        sinceFlush = 0
        ring.flush()
    }

    private fun armTimer() {
        if (file == null || timer?.isActive == true) return
        timer = scope.launch {
            delay(FLUSH_INTERVAL_MS)
            ops.trySend(Op.Flush(null))
        }
    }

    companion object {
        const val TAG = "Trace"
        const val FILE_NAME = "trace_journal.txt"
        /** ~2000 lines of ~100-150 bytes: hours of driving, a few hundred KB at most. */
        const val MAX_EVENTS = 2000
        const val MAX_BYTES = 300 * 1024
        /** A stalled worker (slow storage) must not let events pile up in memory unbounded. */
        const val QUEUE_CAPACITY = 4096
        const val FLUSH_BATCH = 50
        const val FLUSH_INTERVAL_MS = 10_000L
        private const val SNAPSHOT_TIMEOUT_MS = 2_000L

        private fun body(area: TraceArea, what: String, fields: Array<out Pair<String, Any?>>): String {
            val sb = StringBuilder(BODY_CAPACITY).append(area.label).append(' ').append(TraceSanitizer.value(what))
            for ((key, value) in fields) {
                if (value == null) continue
                sb.append(' ').append(TraceSanitizer.key(key)).append('=').append(TraceSanitizer.value(value))
            }
            return sb.toString()
        }

        private const val BODY_CAPACITY = 96
    }
}

/** One journal line; [atMs] and [count] move when the same event repeats. */
internal class TraceEntry(var atMs: Long, val id: Long, val body: String, val by: Long?, var count: Int = 1) {

    /** Everything after the time: shared by the file, the dump and logcat. */
    fun tail(): String = buildString {
        append(body).append(" #").append(id)
        if (by != null) append(" by=#").append(by)
        if (count > 1) append(" (x").append(count).append(')')
    }

    /** The file form: epoch millis instead of a clock time, so the day survives. */
    fun stored(): String = "$atMs ${tail()}"
}

/**
 * The ring and its file, confined to the journal's worker. Lines are ASCII (the sanitizer
 * guarantees it for every value), so a line's length is its size in bytes.
 *
 * The file only grows by appends of the unsaved suffix. A collapsed line that already reached
 * the file is appended again with the same id and the loader keeps the last copy. Once an append
 * would take the file past [maxBytes], it is rewritten with the newest events that fit two thirds
 * of it, so a rewrite happens once per a third of the cap, not on every batch.
 */
internal class TraceRing(private val file: File?, private val maxEvents: Int, private val maxBytes: Int) {

    private val entries = ArrayDeque<TraceEntry>()
    private var bytes = 0L
    private var dirtyFromId = CLEAN
    private var fileBytes = 0L
    // A torn or partly unreadable file is rewritten on the next flush instead of appended to.
    private var rewriteNext = false

    /** Reads the file once, before any event is added. Returns the highest id in it (0 if none). */
    fun load(): Long {
        val f = file ?: return 0L
        return runCatching {
            when {
                !f.isFile -> 0L
                f.length() > maxBytes * 2L -> {
                    Log.w(TraceJournal.TAG, "trace file is ${f.length()} bytes, over the cap: dropped")
                    f.delete()
                    0L
                }
                else -> restore(f)
            }
        }.getOrElse {
            Log.w(TraceJournal.TAG, "trace file unreadable, starting empty: ${it.javaClass.simpleName}")
            entries.clear()
            bytes = 0L
            rewriteNext = true
            0L
        }
    }

    private fun restore(f: File): Long {
        val text = f.readText()
        val lines = text.split('\n').filter { it.isNotEmpty() }
        val parsed = lines.mapNotNull(::parse)
        val loaded = ArrayList<TraceEntry>(parsed.size)
        for (entry in parsed) {
            // A collapsed line saved again after a repeat: the later copy wins.
            if (loaded.lastOrNull()?.id == entry.id) loaded[loaded.lastIndex] = entry else loaded += entry
        }
        for (entry in loaded.asReversed()) {
            val size = entry.stored().length + 1L
            if (entries.size >= maxEvents || bytes + size > maxBytes) break
            entries.addFirst(entry)
            bytes += size
        }
        fileBytes = f.length()
        val bad = lines.size - parsed.size
        if (bad > 0 || !text.endsWith('\n')) rewriteNext = true
        if (bad > 0) Log.w(TraceJournal.TAG, "trace file: $bad unreadable line(s) dropped")
        return loaded.maxOfOrNull { it.id } ?: 0L
    }

    fun add(entry: TraceEntry) {
        entries.addLast(entry)
        bytes += entry.stored().length + 1L
        if (dirtyFromId == CLEAN) dirtyFromId = entry.id
        while (entries.size > maxEvents || bytes > maxBytes) {
            bytes -= entries.removeFirst().stored().length + 1L
        }
    }

    /** The last line happened again: new time and counter, the line goes out with the next flush. */
    fun repeat(id: Long, atMs: Long, count: Int) {
        val last = entries.lastOrNull()?.takeIf { it.id == id } ?: return
        bytes -= last.stored().length
        last.atMs = atMs
        last.count = count
        bytes += last.stored().length
        if (dirtyFromId == CLEAN) dirtyFromId = id
    }

    fun flush() {
        val f = file
        val from = dirtyFromId
        dirtyFromId = CLEAN
        if (f == null || (from == CLEAN && !rewriteNext)) return
        runCatching {
            val chunk = entries.filter { it.id >= from }.joinToString("") { it.stored() + "\n" }
            if (rewriteNext || fileBytes + chunk.length > maxBytes) {
                rewrite(f)
            } else {
                FileOutputStream(f, true).use { it.write(chunk.toByteArray(Charsets.US_ASCII)) }
                fileBytes += chunk.length
            }
        }.onFailure {
            Log.w(TraceJournal.TAG, "trace not saved: ${it.javaClass.simpleName}")
            rewriteNext = true
        }
    }

    fun render(): List<String> {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val out = ArrayList<String>(entries.size + 2)
        var lastDay: String? = null
        for (entry in entries) {
            val date = Date(entry.atMs)
            val d = day.format(date)
            if (d != lastDay) {
                out += "-- $d --"
                lastDay = d
            }
            out += time.format(date) + " " + entry.tail()
        }
        return out
    }

    private fun rewrite(f: File) {
        val budget = maxBytes * 2 / 3
        val keep = ArrayList<String>()
        var size = 0
        for (entry in entries.asReversed()) {
            val line = entry.stored() + "\n"
            if (size + line.length > budget) break
            keep += line
            size += line.length
        }
        val text = keep.asReversed().joinToString("")
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(text, Charsets.US_ASCII)
        if (!tmp.renameTo(f)) {
            f.writeText(text, Charsets.US_ASCII)
            tmp.delete()
        }
        fileBytes = size.toLong()
        rewriteNext = false
    }

    private companion object {
        const val CLEAN = Long.MAX_VALUE
        val STORED = Regex("""(\d{1,15}) (.+) #(\d{1,18})(?: by=#(\d{1,18}))?(?: \(x(\d{1,9})\))?""")

        fun parse(line: String): TraceEntry? {
            val g = STORED.matchEntire(line)?.groupValues ?: return null
            return TraceEntry(g[1].toLong(), g[3].toLong(), g[2], g[4].toLongOrNull(), g[5].toIntOrNull() ?: 1)
        }
    }
}
