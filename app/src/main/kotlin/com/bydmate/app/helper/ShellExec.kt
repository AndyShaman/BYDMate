package com.bydmate.app.helper

import java.io.File

internal class CmdResult(val code: Int, val stdout: String)

/**
 * Runs [script] under sh with [args] bound to positional params ($1, $2, …) so untrusted values
 * (e.g. an existing accessibility list read off the device) are passed as argv and NEVER re-parsed
 * by the shell — no injection, no quote-breakage. Returns ONLY stdout + exit code (stderr is sent
 * to /dev/null, so there is no second pipe to deadlock on and stdout can never be corrupted by an
 * stderr line). Use this when success/output matters and stderr must not reach the parser;
 * use [shExecMerged] when am/monkey error messages on stderr must be detected.
 */
internal fun shExec(script: String, vararg args: String): CmdResult {
    val cmd = arrayListOf("sh", "-c", script, "sh")
    cmd.addAll(args)
    val process = ProcessBuilder(cmd)
        .redirectError(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
        .start()
    val out = process.inputStream.bufferedReader().use { it.readText().trim() }
    return CmdResult(process.waitFor(), out)
}

/**
 * Like [shExec] but merges stderr into stdout via [ProcessBuilder.redirectErrorStream] — use this
 * wherever the result is checked for "Error" or "Exception" text that am and monkey write to stderr.
 * (AOSP ActivityManagerShellCommand uses getErrPrintWriter for "Error:" lines and
 * "Exception occurred while executing '<cmd>'"; the single merged pipe cannot deadlock.)
 * Do NOT use for component resolution ([resolveLaunchComponent]): stderr must not reach the parser.
 */
internal fun shExecMerged(script: String, vararg args: String): String =
    shExecMergedResult(script, *args).stdout.ifEmpty { "OK" }

/** [shExecMerged] with the exit code kept: the `wm density` op logs rc and output together. */
internal fun shExecMergedResult(script: String, vararg args: String): CmdResult {
    val cmd = arrayListOf("sh", "-c", script, "sh")
    cmd.addAll(args)
    val process = ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
    val out = process.inputStream.bufferedReader().use { it.readText().trim() }
    return CmdResult(process.waitFor(), out)
}

/**
 * Bounded variant of [shExecMerged] for the read-only diagnostic snapshot: a dumpsys on an unknown
 * firmware can print megabytes or hang, and neither may stall the daemon's binder thread. stdout is
 * drained on a background thread into a buffer capped at [maxBytes]; if the process outlives
 * [timeoutMs] it is killed and whatever was read is returned with a `[timeout Nms]` marker line.
 * Used by the diagnostic snapshots ([logClusterDisplayDiag], [logA11yFrameworkState]) and by
 * [readGlobalSetting], which runs on the binder thread — the other callers stay on
 * [shExec]/[shExecMerged]. [args] land as `$1`, `$2`, … so a value never goes into the script.
 */
internal fun shExecBounded(
    script: String,
    timeoutMs: Long = 4000L,
    maxBytes: Int = 64 * 1024,
    vararg args: String,
): String {
    val cmd = arrayListOf("sh", "-c", script, "sh")
    cmd.addAll(args)
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val buffer = StringBuilder()
    val reader = Thread {
        runCatching {
            process.inputStream.reader().use { stream ->
                val chunk = CharArray(8192)
                var total = 0
                while (total < maxBytes) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    val take = minOf(n, maxBytes - total)
                    synchronized(buffer) { buffer.append(chunk, 0, take) }
                    total += take
                }
            }
        }
    }
    reader.isDaemon = true
    reader.start()
    val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    if (!finished) process.destroyForcibly()
    reader.join(500L)
    val out = synchronized(buffer) { buffer.toString() }.trim()
    return if (finished) out else (out + "\n[timeout ${timeoutMs}ms]")
}
