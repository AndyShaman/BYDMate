package com.bydmate.app.helper.push

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The recorder's own file, written by the daemon under the shell uid (which may write
 * /sdcard/Download without any app permission). One line per recorded event; the caller decides
 * WHICH events get a line, this class only formats, counts and flushes them.
 *
 * Every method is synchronized: lines arrive from the vendor binder threads, while the flush, the
 * size check and the close come from the recorder's ticker thread.
 */
internal class FidRecLog(val file: File, private val nowMs: () -> Long = System::currentTimeMillis) {

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val writer: BufferedWriter = BufferedWriter(FileWriter(file, true))
    private var written = 0L
    private var lastFlush = nowMs()

    /** Bytes handed to the writer so far — the file itself lags by at most one flush. */
    val bytes: Long get() = synchronized(this) { written }

    fun header(lines: List<String>) = synchronized(this) { lines.forEach { append(it) } }

    /** One event line, stamped with the wall clock the caller measured the event at. */
    fun event(e: RecEvent) = synchronized(this) {
        append(
            "${stamp.format(Date(e.atMs))} dev=${e.dev} fid=${e.fid} ${e.symbol} " +
                "int=${e.intValue} dbl=${e.doubleValue}"
        )
    }

    /** Flushes when [FLUSH_INTERVAL_MS] passed since the last one, so a crash loses ~2 s at most. */
    fun flushIfDue(now: Long) = synchronized(this) {
        if (now - lastFlush < FLUSH_INTERVAL_MS) return@synchronized
        lastFlush = now
        runCatching { writer.flush() }
    }

    fun close(lastLine: String) = synchronized(this) {
        runCatching {
            append(lastLine)
            writer.close()
        }
        Unit
    }

    private fun append(line: String) {
        runCatching {
            writer.write(line)
            writer.write("\n")
            written += line.toByteArray().size + 1
        }
    }

    companion object {
        /** Directory the daemon writes into; the user finds the file in «Загрузки». */
        const val DIR = "/sdcard/Download"

        private const val FLUSH_INTERVAL_MS = 2000L

        /** File name of a run started at [atMs]. */
        fun nameFor(atMs: Long): String =
            "bydmate_fidrec_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(atMs))}.txt"

        /** Human-readable start time for the file header. */
        fun startedAtText(atMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(atMs))
    }
}
