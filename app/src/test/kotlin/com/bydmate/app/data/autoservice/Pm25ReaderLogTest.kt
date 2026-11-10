package com.bydmate.app.data.autoservice

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Pm25ReaderLogTest {

    private val answers = mutableMapOf<Int, Int?>(1331691568 to 2, 1331691536 to 8)

    private fun readLines() = ShadowLog.getLogsForTag("TechPanel").count { it.msg.startsWith("pm25 read:") }

    @Test fun `identical reads log once, a changed value logs again`() = runBlocking {
        val client = mockk<AutoserviceClient>()
        coEvery { client.getInt(any(), any()) } answers { answers[secondArg<Int>()] }
        val reader = Pm25Reader(client)
        ShadowLog.clear()

        reader.read()
        reader.read()
        assertEquals(1, readLines())

        answers[1331691536] = 9
        reader.read()
        assertEquals(2, readLines())
    }
}
