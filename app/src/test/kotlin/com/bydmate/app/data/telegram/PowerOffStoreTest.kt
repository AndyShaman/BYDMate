package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.offreport.OffReportOutcome
import com.bydmate.app.helper.offreport.OffReportState
import com.bydmate.app.helper.offreport.OffReportStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The power-off report's disk state and the outcome captured before a daemon replace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PowerOffStoreTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: PrefsPowerOffStore

    @Before fun setUp() {
        ctx.getSharedPreferences("tg_offreport", Context.MODE_PRIVATE).edit().clear().commit()
        store = PrefsPowerOffStore(ctx)
    }

    private fun record(id: String) = ArmedRecord(id, 42L, "text $id", id.hashCode().toLong())

    @Test fun `the two newest reports are kept, newest first`() {
        store.pushRecord(record("a"))
        store.pushRecord(record("b"))
        store.pushRecord(record("c"))
        assertEquals(listOf("c", "b"), PrefsPowerOffStore(ctx).records().map { it.id })
        assertEquals(record("c"), store.records().first())
        store.clearRecords()
        assertTrue(PrefsPowerOffStore(ctx).records().isEmpty())
    }

    @Test fun `a captured outcome is read once`() {
        val outcome = OffReportOutcome("a", OffReportState.FAILED, 10L, 0L, 3, "io:UnknownHostException")
        store.putCaptured(outcome)
        assertEquals(outcome, PrefsPowerOffStore(ctx).takeCaptured())
        assertNull(store.takeCaptured())
    }

    @Test fun `the replace hook stores the old daemon's last outcome`() = runBlocking {
        val last = OffReportOutcome("a", OffReportState.SENT, 10L, 20L, 1, "200")
        val helper = mockk<HelperClient>()
        coEvery { helper.offReportStatus("") } returns
            OffReportStatus(OffReportOutcome("", OffReportState.UNKNOWN), "", 0L, 2, last)
        PowerOffOutcomeCapture(ctx, helper).beforeReplace()
        assertEquals(last, store.takeCaptured())
    }

    @Test fun `the replace hook stores nothing when the old daemon has no outcome`() = runBlocking {
        val helper = mockk<HelperClient>()
        coEvery { helper.offReportStatus("") } returns null
        PowerOffOutcomeCapture(ctx, helper).beforeReplace()
        assertNull(store.takeCaptured())
    }
}
