package com.bydmate.app.data.vehicle

import kotlinx.coroutines.CancellationException

/** A read for a channel's log line: the value, or "err" when the read did not complete. */
internal fun render(value: Int?) = value?.toString() ?: "err"

/**
 * [read]'s value, or null when it throws: a throwing read is a failed read, not a verdict.
 * Cancellation is rethrown; any other failure goes to [logFailure], which writes the channel's
 * own log line.
 */
internal inline fun readGuarded(logFailure: (Throwable) -> Unit, read: () -> Int?): Int? =
    runCatching { read() }
        .onFailure {
            if (it is CancellationException) throw it
            logFailure(it)
        }
        .getOrNull()
