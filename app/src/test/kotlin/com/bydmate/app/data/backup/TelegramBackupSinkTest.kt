package com.bydmate.app.data.backup

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramBackupSinkTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var sink: TelegramBackupSink

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        sink = TelegramBackupSink(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() { server.shutdown() }

    private fun failureOf(result: Result<*>): TelegramSinkException =
        result.exceptionOrNull() as TelegramSinkException

    @Test fun `getMe returns the bot username`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"id":1,"is_bot":true,"username":"my_car_bot"}}"""))

        assertEquals("my_car_bot", sink.getMe("123:abc").getOrThrow())
        assertEquals("/bot123:abc/getMe", server.takeRequest().path)
    }

    @Test fun `getMe with a rejected token reports an invalid token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"ok":false,"error_code":401,"description":"Unauthorized"}"""))

        val error = failureOf(sink.getMe("bad"))
        assertEquals(TelegramError.BAD_TOKEN, error.error)
        assertFalse(error.transient)
    }

    @Test fun `findPrivateChat returns the private chat that sent the code, with its name`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"ok":true,"result":[
                {"update_id":1,"message":{"text":"482913","chat":{"id":111,"type":"private","first_name":"Old"}}},
                {"update_id":2,"message":{"text":"482913","chat":{"id":-500,"type":"group"}}},
                {"update_id":3,"message":{"text":" 482913 ","chat":{"id":222,"type":"private","first_name":"Andy","username":"andy_s"}}},
                {"update_id":4,"my_chat_member":{"chat":{"id":-600,"type":"supergroup"}}},
                {"update_id":5,"message":{"text":"hi","chat":{"id":333,"type":"private","first_name":"Stranger"}}}
            ]}"""
        ))

        assertEquals(TelegramChat(222L, "Andy @andy_s"), sink.findPrivateChat("123:abc", "482913").getOrThrow())
        assertEquals(
            "/bot123:abc/getUpdates?offset=-100&limit=100&allowed_updates=%5B%22message%22%5D",
            server.takeRequest().path,
        )
    }

    @Test fun `findPrivateChat is null when no chat sent the code`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"ok":true,"result":[{"update_id":1,"message":{"text":"111111","chat":{"id":7,"type":"private"}}}]}"""
        ))
        assertNull(sink.findPrivateChat("123:abc", "482913").getOrThrow())
    }

    @Test fun `findPrivateChat ignores a chat whose latest message is not the code`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"ok":true,"result":[
                {"update_id":1,"message":{"text":"482913","chat":{"id":7,"type":"private","first_name":"A"}}},
                {"update_id":2,"message":{"text":"hello","chat":{"id":7,"type":"private","first_name":"A"}}}
            ]}"""
        ))
        assertNull(sink.findPrivateChat("123:abc", "482913").getOrThrow())
    }

    @Test fun `findPrivateChat name falls back to whichever part exists`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"ok":true,"result":[{"update_id":1,"message":{"text":"482913","chat":{"id":7,"type":"private","username":"only_nick"}}}]}"""
        ))
        assertEquals(TelegramChat(7L, "@only_nick"), sink.findPrivateChat("123:abc", "482913").getOrThrow())
    }

    @Test fun `200 with ok false is an error by its error_code`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":false,"error_code":403,"description":"Forbidden"}"""))

        val error = failureOf(sink.sendDocument("123:abc", 42L, tmp.newFile("a.zip"), "c"))
        assertEquals("HTTP:403", error.key)
        assertFalse(error.transient)
    }

    @Test fun `200 without ok or result is a bad response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":false}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        assertEquals(TelegramError.BAD_RESPONSE, failureOf(sink.getMe("123:abc")).error)
        assertEquals(TelegramError.BAD_RESPONSE, failureOf(sink.sendMessage("123:abc", 42L, "hi")).error)
    }

    @Test fun `409 on getUpdates means a webhook is set and is permanent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody(
            """{"ok":false,"error_code":409,"description":"Conflict: can't use getUpdates method while webhook is active"}"""
        ))

        val error = failureOf(sink.findPrivateChat("123:abc", "482913"))
        assertEquals(TelegramError.WEBHOOK, error.error)
        assertEquals(409, error.httpCode)
        assertFalse(error.transient)
    }

    @Test fun `garbled json answer is a transient bad response`() = runTest {
        server.enqueue(MockResponse().setBody("<html>proxy error</html>"))

        val error = failureOf(sink.getMe("123:abc"))
        assertEquals(TelegramError.BAD_RESPONSE, error.error)
        assertTrue(error.transient)
    }

    @Test fun `other http error keeps its code in the key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"ok":false,"description":"Forbidden: bot was blocked by the user"}"""))

        val error = failureOf(sink.sendMessage("123:abc", 42L, "hi"))
        assertEquals("HTTP:403", error.key)
        assertFalse(error.transient)
    }

    @Test fun `findPrivateChat is null when nobody wrote to the bot`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":[]}"""))
        assertNull(sink.findPrivateChat("123:abc", "482913").getOrThrow())
    }

    @Test fun `sendMessage posts chat id and text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{}}"""))

        assertTrue(sink.sendMessage("123:abc", 42L, "BYDMate подключён").isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/bot123:abc/sendMessage", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("chat_id=42"))
    }

    @Test fun `sendDocument uploads a multipart zip with chat id and caption`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{}}"""))
        val file = tmp.newFile("bydmate_backup_auto_20260923_100000.zip").apply { writeBytes(byteArrayOf(80, 75, 3, 4)) }

        assertTrue(sink.sendDocument("123:abc", 42L, file, "BYDMate: бэкап").isSuccess)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/bot123:abc/sendDocument", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("name=\"chat_id\"\r\nContent-Length: 2\r\n\r\n42"))
        assertTrue(body, body.contains("name=\"caption\""))
        assertTrue(body, body.contains("BYDMate: бэкап"))
        assertTrue(body, body.contains("name=\"document\"; filename=\"bydmate_backup_auto_20260923_100000.zip\""))
        assertTrue(body, body.contains("Content-Type: application/zip"))
    }

    @Test fun `chat not found asks the user to write to the bot`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}"""))

        val error = failureOf(sink.sendDocument("123:abc", 42L, tmp.newFile("a.zip"), "c"))
        assertEquals(TelegramError.NO_CHAT, error.error)
        assertFalse(error.transient)
    }

    @Test fun `413 reports the 50 MB limit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(413)
            .setBody("""{"ok":false,"error_code":413,"description":"Request Entity Too Large"}"""))

        val error = failureOf(sink.sendDocument("123:abc", 42L, tmp.newFile("a.zip"), "c"))
        assertEquals(TelegramError.TOO_LARGE, error.error)
        assertFalse(error.transient)
    }

    @Test fun `server error and rate limit are transient`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
        server.enqueue(MockResponse().setResponseCode(429)
            .setBody("""{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 5"}"""))

        assertTrue(failureOf(sink.sendDocument("123:abc", 42L, tmp.newFile("a.zip"), "c")).transient)
        assertTrue(failureOf(sink.sendDocument("123:abc", 42L, tmp.newFile("b.zip"), "c")).transient)
    }

    @Test fun `dropped connection reports no network and is transient`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val error = failureOf(sink.getMe("123:abc"))
        assertEquals(TelegramError.NO_NETWORK, error.error)
        assertTrue(error.transient)
        assertNull(error.httpCode)
    }
}
