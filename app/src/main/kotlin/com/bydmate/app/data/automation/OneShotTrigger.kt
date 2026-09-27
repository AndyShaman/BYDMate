package com.bydmate.app.data.automation

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * One-shot rule on a local date and time. The trigger value is `yyyy-MM-ddTHH:mm` in the head
 * unit's zone. It is true from that moment for [WINDOW_MS] (the engine adds «screen on»); the
 * engine then switches the rule off, so it runs once. Past the window without a fire the rule is
 * switched off with a journal entry instead.
 */
object OneShotTrigger {

    const val KIND = "once_at"
    const val PARAM = "OnceAt"

    /** How long after the moment a rule that has not fired yet may still fire. */
    const val WINDOW_MS = 24 * 60 * 60 * 1000L

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    enum class State { PENDING, DUE, EXPIRED, INVALID }

    fun format(at: LocalDateTime): String = at.format(FORMAT)

    /** Wall-clock millis of [value] in [zone]; null when it does not parse. */
    fun momentMs(value: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        LocalDateTime.parse(value.trim(), FORMAT).atZone(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    fun state(value: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): State {
        val at = momentMs(value, zone) ?: return State.INVALID
        return when {
            nowMs < at -> State.PENDING
            nowMs < at + WINDOW_MS -> State.DUE
            else -> State.EXPIRED
        }
    }
}
