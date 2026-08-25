package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H2 (#64/#148): on qti/trinket firmwares ServiceManager.addService is refused for the shell
 * domain, and the daemon hands its Binder to the app in a broadcast instead. What may be
 * attempted after that refusal — and how the spawn token is read off the argv — is decided here,
 * away from the Android-dependent sender.
 */
class RegistrationFallbackTest {

    @Test fun `broadcast is used when both a context and a token are available`() {
        assertEquals(
            RegistrationFallback.BROADCAST,
            decideRegistrationFallback(hasSystemContext = true, hasToken = true),
        )
    }

    @Test fun `no system context means no sender at all`() {
        assertEquals(
            RegistrationFallback.EXIT_NO_CONTEXT,
            decideRegistrationFallback(hasSystemContext = false, hasToken = true),
        )
        // The missing context wins over a missing token: without it nothing can be sent.
        assertEquals(
            RegistrationFallback.EXIT_NO_CONTEXT,
            decideRegistrationFallback(hasSystemContext = false, hasToken = false),
        )
    }

    @Test fun `an untokened spawn must not broadcast`() {
        // Spawned by an app version that predates the fallback: the app could not tell our
        // intent from a forged one, so we exit rather than publish to an exported receiver.
        assertEquals(
            RegistrationFallback.EXIT_NO_TOKEN,
            decideRegistrationFallback(hasSystemContext = true, hasToken = false),
        )
    }

    @Test fun `token is read from the second argument`() {
        assertEquals("a1b2c3d4e5f60718", spawnTokenFrom(arrayOf("10123", "a1b2c3d4e5f60718")))
    }

    @Test fun `token is absent for an old single-argument spawn`() {
        assertNull(spawnTokenFrom(arrayOf("10123")))
        assertNull(spawnTokenFrom(emptyArray()))
    }

    @Test fun `a blank token counts as absent`() {
        assertNull(spawnTokenFrom(arrayOf("10123", "")))
        assertNull(spawnTokenFrom(arrayOf("10123", "   ")))
    }
}
