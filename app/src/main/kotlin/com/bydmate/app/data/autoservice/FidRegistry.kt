package com.bydmate.app.data.autoservice

/**
 * autoservice Binder addresses validated against on-device service.
 *
 * IMPORTANT: read-only constants. NEVER add a setInt fid here — the
 * regex barrier in AdbOnDeviceClient will reject any tx=6 attempt.
 */
object FidRegistry {

    // transact codes — only read-side codes are listed
    const val TX_GET_INT = 5
    const val TX_GET_FLOAT = 7
}
