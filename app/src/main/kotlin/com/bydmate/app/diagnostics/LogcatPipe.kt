package com.bydmate.app.diagnostics

/**
 * Which recorded logcat lines reach the file. `ActivityManager:I` is in the recorder's filter for
 * what happens to our process and to the navigators (force stops, kills, deaths, ANRs), but on a
 * head unit it is mostly other apps' noise: non-protected broadcast stacks, a voice service the
 * firmware fails to start every second, memory pressure — thousands of lines an hour.
 */
internal object LogcatLineFilter {

    private const val ACTIVITY_MANAGER = "/ActivityManager("

    /** Packages whose ActivityManager lines are kept: ours and the navigators the app reads. */
    private val PACKAGES = listOf(
        "com.bydmate",
        "ru.yandex.yandexnavi",
        "ru.yandex.yandexmaps",
        "ru.dublgis",
    )

    /** What the system does to a process, whoever it is: kept for any package. */
    private val KEYWORDS = Regex("""Killing|Force stopping|has died|ANR""")

    fun keep(line: String): Boolean {
        if (!line.contains(ACTIVITY_MANAGER)) return true
        return PACKAGES.any { line.contains(it) } || KEYWORDS.containsMatchIn(line)
    }
}

/**
 * When the recording pipe pushes its buffered lines to the file: once [maxLines] lines are
 * waiting or [maxDelayMs] after the oldest unflushed one, whichever comes first. A flush per line
 * was a write syscall per line, tens of thousands an hour. Not thread-safe: the pipe guards it.
 */
internal class PipeFlushPolicy(
    private val maxLines: Int = MAX_LINES,
    private val maxDelayMs: Long = MAX_DELAY_MS,
) {
    private var pending = 0
    private var firstPendingAtMs = 0L

    /** A line was written to the buffer; true when the buffer should be flushed now. */
    fun onLine(nowMs: Long): Boolean {
        if (pending == 0) firstPendingAtMs = nowMs
        pending++
        return pending >= maxLines || nowMs - firstPendingAtMs >= maxDelayMs
    }

    /** The periodic check while logcat is quiet: true when waiting lines are due. */
    fun dueIdle(nowMs: Long): Boolean = pending > 0 && nowMs - firstPendingAtMs >= maxDelayMs

    fun flushed() {
        pending = 0
    }

    companion object {
        const val MAX_LINES = 64
        const val MAX_DELAY_MS = 1_000L
    }
}
