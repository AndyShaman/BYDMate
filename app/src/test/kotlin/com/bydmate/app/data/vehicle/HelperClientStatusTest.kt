package com.bydmate.app.data.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The helper daemon forwards the RAW autoservice transact return code in the
 * `status` field (HelperDaemon: status = reply.readInt()). Validated on
 * Leopard 3 2026-05-28:
 *   setInt real action → status = 1
 *   setInt no-op       → status = 0
 *   getInt success     → status = 0 (value follows)
 *   error / no reply   → status < 0 (-1 daemon exception, -999 no reply data)
 *
 * So a write is accepted on status >= 0 (regression: client used == 0, which
 * marked every real action as a failure). A read is accepted only on status == 0.
 */
class HelperClientStatusTest {

    @Test fun `write accepted on status 0 no-op and 1 real action`() {
        assertTrue(HelperClientImpl.writeAccepted(0))
        assertTrue(HelperClientImpl.writeAccepted(1))
    }

    @Test fun `write rejected on negative status`() {
        assertFalse(HelperClientImpl.writeAccepted(-1))
        assertFalse(HelperClientImpl.writeAccepted(-999))
        assertFalse(HelperClientImpl.writeAccepted(-10011))
    }

    @Test fun `read accepted only on status 0`() {
        assertTrue(HelperClientImpl.readAccepted(0))
        assertFalse(HelperClientImpl.readAccepted(1))
        assertFalse(HelperClientImpl.readAccepted(-1))
    }
}
