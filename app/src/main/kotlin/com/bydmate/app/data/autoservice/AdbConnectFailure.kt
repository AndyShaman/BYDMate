package com.bydmate.app.data.autoservice

/** Why an ADB connect to the on-device adbd failed. */
enum class AdbConnectFailure {
    /** No answer on the port: socket refused/reset, or a handshake adbd would never send. */
    UNREACHABLE,

    /** adbd answered and asked for auth, but our key was denied or the prompt timed out. */
    AUTH_REJECTED,
}
