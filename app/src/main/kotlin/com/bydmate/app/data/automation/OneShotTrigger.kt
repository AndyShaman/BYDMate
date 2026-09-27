package com.bydmate.app.data.automation

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

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

    // STRICT: the default SMART style turns 2026-02-30 into 2026-02-28 instead of refusing it.
    // STRICT needs uuuu (proleptic year); yyyy is year-of-era and would then need an era field.
    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT)

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
