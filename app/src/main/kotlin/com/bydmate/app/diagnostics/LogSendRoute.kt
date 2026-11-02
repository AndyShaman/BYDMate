package com.bydmate.app.diagnostics

/** Where the Settings button sends a saved log (#380). */
enum class LogSendRoute { TELEGRAM, SHARE_SHEET }

/** The user's bot when one is bound, otherwise the system share sheet. */
fun logSendRoute(botConfigured: Boolean): LogSendRoute =
    if (botConfigured) LogSendRoute.TELEGRAM else LogSendRoute.SHARE_SHEET
