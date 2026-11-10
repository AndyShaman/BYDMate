package com.bydmate.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/** #380: no Telegram bot set -> the log goes through the system share sheet. */
class LogSendRouteTest {

    @Test fun `a bound bot sends through Telegram, no bot opens the share sheet`() {
        assertEquals(LogSendRoute.TELEGRAM, logSendRoute(botConfigured = true))
        assertEquals(LogSendRoute.SHARE_SHEET, logSendRoute(botConfigured = false))
    }
}
