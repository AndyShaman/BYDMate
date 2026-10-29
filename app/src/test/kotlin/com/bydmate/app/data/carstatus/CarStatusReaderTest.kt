package com.bydmate.app.data.carstatus

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun observers_cover_the_issue_text_and_the_owner_intervals_never_the_bare_uri() {
        val resolver = mockk<ContentResolver>(relaxed = true)
        val uris = mutableListOf<Uri>()
        every { resolver.registerContentObserver(capture(uris), any(), any()) } returns Unit
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        runBlocking {
            val job = launch(Dispatchers.Unconfined) { CarStatusReader(context).changes().collect {} }
            job.cancel()
        }
        val keys = uris.map { it.lastPathSegment }.toSet()
        assertTrue(keys.toString(), keys.containsAll(listOf(
            "car_status_issue", "set_car_status_maintenance_mile", "set_car_status_maintenance_hev_mile",
        )))
        assertFalse(uris.any { it.lastPathSegment == "car_status" })
    }
}
