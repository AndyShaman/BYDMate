package com.bydmate.app.data.carstatus

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarStatusReaderTest {

    private fun readerWith(answer: () -> android.database.Cursor?): CarStatusReader {
        val resolver = mockk<ContentResolver>()
        every {
            resolver.query(any<Uri>(), any<Array<String>>(), any<String>(), any<Array<String>>(), any<String>())
        } answers { answer() }
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        return CarStatusReader(context)
    }

    @Test fun null_cursor_returns_null() {
        assertNull(runBlocking { readerWith { null }.read() })
    }

    @Test fun security_exception_returns_null() {
        assertNull(runBlocking { readerWith { throw SecurityException("denied") }.read() })
    }
}
