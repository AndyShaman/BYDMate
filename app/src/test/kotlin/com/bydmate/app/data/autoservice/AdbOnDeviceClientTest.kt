package com.bydmate.app.data.autoservice

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdbOnDeviceClientTest {

    private fun newClient(fake: AdbProtocol): AdbOnDeviceClientImpl {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val keyStore = AdbKeyStore(ctx)
        val client = AdbOnDeviceClientImpl(ctx, keyStore)
        client.protocolFactory = { fake }
        return client
    }

    private class FakeProtocol(
        var connectResult: Boolean = true,
        var connectThrows: Throwable? = null,
        var execResponses: Map<String, String?> = emptyMap()
    ) : AdbProtocol {
        var connectCalls = 0
        var disconnectCalls = 0
        var execCalls = mutableListOf<String>()
        private var connected = false

        override fun connect(): Boolean {
            connectCalls++
            connectThrows?.let { throw it }
            connected = connectResult
            return connectResult
        }
        override fun exec(cmd: String): String? {
            execCalls += cmd
            return execResponses[cmd]
        }
        override fun isConnected(): Boolean = connected
        override fun disconnect() {
            disconnectCalls++
            connected = false
        }
    }

    @Test
    fun `exec write command throws write barrier`() = runTest {
        val client = newClient(FakeProtocol())
        client.connect()
        try {
            // tx=6 is setInt — must be rejected at the boundary.
            client.exec("service call autoservice 6 i32 1014 i32 1145045032")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("write barrier"))
        }
    }

    @Test
    fun `exec disconnected returns null`() = runTest {
        val client = newClient(FakeProtocol(connectResult = false))
        // Note: not calling connect() — protocol field stays null.
        val result = client.exec("service call autoservice 5 i32 1014 i32 1145045032")
        assertNull(result)
    }

    @Test
    fun `connect protocol returns true returns success`() = runTest {
        val fake = FakeProtocol(connectResult = true)
        val client = newClient(fake)

        val result = client.connect()

        assertTrue(result.isSuccess)
        assertEquals(1, fake.connectCalls)
    }

    @Test
    fun `connect protocol returns false returns failure`() = runTest {
        val fake = FakeProtocol(connectResult = false)
        val client = newClient(fake)

        val result = client.connect()

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `connect protocol throws returns failure does not crash`() = runTest {
        val fake = FakeProtocol(connectThrows = RuntimeException("kaboom"))
        val client = newClient(fake)

        val result = client.connect()

        assertTrue(result.isFailure)
        assertEquals("kaboom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `shutdown idempotent does not crash when never connected`() = runTest {
        val client = newClient(FakeProtocol())
        // Never connected.
        client.shutdown()
        client.shutdown()
        // No exception → ok.
    }

    @Test
    fun `shutdown after connect disconnects protocol`() = runTest {
        val fake = FakeProtocol(connectResult = true)
        val client = newClient(fake)

        client.connect()
        client.shutdown()

        assertEquals(1, fake.disconnectCalls)
    }
}
