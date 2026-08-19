package com.bydmate.app.cluster

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.CENTER_MAP
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.DEV_INSTRUMENT
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.FID_CENTER
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.FID_LEFT
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.FID_MENU_SET
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.FID_MENU_STATUS
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.KEY_SAVED_CENTER
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.KEY_SAVED_LEFT
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.KEY_SAVED_MENU
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.LEFT_CARD
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.MAX_REASSERT_FAILURES
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.MENU_HIDDEN
import com.bydmate.app.cluster.ClusterFrameUi7.Companion.REASSERT_INTERVAL_MS
import com.bydmate.app.cluster.ClusterFrameUi7.Owner.CAMERA
import com.bydmate.app.cluster.ClusterFrameUi7.Owner.PROJECTION
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The frame writes to the instrument panel's own CAN registers, so the two things that matter are
 * what leaves for the car and what is left behind for the next process: a pre-OTA car must see no
 * traffic at all, the stock pair must be read once and kept verbatim, and a restore must put it
 * back even after a process death.
 *
 * Robolectric for real SharedPreferences (the class persists its write-ahead pair through them);
 * the re-assert loop is exercised through [ClusterFrameUi7.reassertOnce] instead of its 5 s job, so
 * the tests assert the pass itself rather than the timer around it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterFrameUi7Test {

    // Stock pair read on the car 2026-08-18: Map frame off, native car widget on the left.
    private val stockCenter = 7
    private val stockLeft = 0

    // Menu status as the car answers it with the native side cards on screen (2 = already hidden).
    private val stockMenu = 1

    // autoservice setInt statuses: 1 = the car really moved, 0 = accepted but inert on this trim.
    private val statusReal = 1
    private val statusNoop = 0

    private val helper = mockk<HelperClient>(relaxed = true)
    private val journal = mutableListOf<String>()
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        clearManagerFrame()
    }

    @After
    fun tearDown() {
        clearManagerFrame()
    }

    /**
     * [ClusterProjectionManager.frameFor] caches ONE frame in a static field. Robolectric hands
     * every method a fresh Application and fresh prefs, but that field survives, so a later test
     * would get this method's instance over this method's prefs, with its re-assert job still on
     * this method's scope.
     */
    private fun clearManagerFrame() {
        ClusterProjectionManager::class.java.getDeclaredField("frame")
            .apply { isAccessible = true }
            .set(ClusterProjectionManager, null)
    }

    private fun frame(
        scope: CoroutineScope,
        platformized: Boolean = true,
        store: SharedPreferences = prefs,
    ) = ClusterFrameUi7(
        prefs = store,
        journal = { journal += it },
        scope = scope,
        platformized = { platformized },
    )

    /** What a live projection of this version leaves on disk: the pair plus the menu status. */
    private fun persistStockTriple() {
        prefs.edit()
            .putInt(KEY_SAVED_CENTER, stockCenter)
            .putInt(KEY_SAVED_LEFT, stockLeft)
            .putInt(KEY_SAVED_MENU, stockMenu)
            .commit()
    }

    private fun stubStockRead() {
        coEvery { helper.read(DEV_INSTRUMENT, FID_CENTER) } returns stockCenter.toLong()
        coEvery { helper.read(DEV_INSTRUMENT, FID_LEFT) } returns stockLeft.toLong()
        coEvery { helper.read(DEV_INSTRUMENT, FID_MENU_STATUS) } returns stockMenu.toLong()
    }

    /** Our own frame on the registers, i.e. what a live projection reads back. */
    private fun stubOurFrameRead() {
        coEvery { helper.read(DEV_INSTRUMENT, FID_CENTER) } returns CENTER_MAP.toLong()
        coEvery { helper.read(DEV_INSTRUMENT, FID_LEFT) } returns LEFT_CARD.toLong()
        coEvery { helper.read(DEV_INSTRUMENT, FID_MENU_STATUS) } returns MENU_HIDDEN.toLong()
    }

    private fun stubWrites(status: Int? = statusReal) {
        coEvery { helper.writeStatus(DEV_INSTRUMENT, any(), any()) } returns status
    }

    @Test fun `pre-OTA firmware sees no read, no write and no journal line`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope, platformized = false)

        assertFalse(frame.apply(helper, PROJECTION))
        frame.restore(helper, PROJECTION)
        frame.recoverStale(helper)

        coVerify(exactly = 0) { helper.read(any(), any(), any()) }
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertTrue(journal.isEmpty())
        assertFalse(frame.hasStalePair())
    }

    @Test fun `apply reads the stock pair, persists it, then opens the Map frame`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)

        assertTrue(frame.apply(helper, PROJECTION))

        coVerifyOrder {
            helper.read(DEV_INSTRUMENT, FID_CENTER)
            helper.read(DEV_INSTRUMENT, FID_LEFT)
            helper.read(DEV_INSTRUMENT, FID_MENU_STATUS)
            helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, CENTER_MAP)
            helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, LEFT_CARD)
            helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, MENU_HIDDEN)
        }
        assertEquals(stockCenter, prefs.getInt(KEY_SAVED_CENTER, -1))
        assertEquals(stockLeft, prefs.getInt(KEY_SAVED_LEFT, -1))
        assertEquals(stockMenu, prefs.getInt(KEY_SAVED_MENU, -1))
        assertEquals(listOf("ui7 frame: center 7->2 left 0->1 menu 1->2 ok=true"), journal)
    }

    @Test fun `apply hides the side cards after opening the frame and persists the stock menu status`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)

        assertTrue(frame.apply(helper, PROJECTION))

        coVerify(exactly = 1) { helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, MENU_HIDDEN) }
        // Read and write live on different fids here: the status register only ever answers.
        coVerify(exactly = 0) { helper.writeStatus(DEV_INSTRUMENT, FID_MENU_STATUS, any()) }
        assertEquals(stockMenu, prefs.getInt(KEY_SAVED_MENU, -1))
    }

    @Test fun `an unreadable menu status leaves the side cards alone but still opens the frame`() = runTest {
        // The cards are cosmetic, the frame is what the driver asked for: one must not cost the other.
        stubStockRead()
        coEvery { helper.read(DEV_INSTRUMENT, FID_MENU_STATUS) } returns null
        stubWrites()
        val frame = frame(backgroundScope)

        assertTrue(frame.apply(helper, PROJECTION))

        coVerify(exactly = 1) { helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, CENTER_MAP) }
        coVerify(exactly = 1) { helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, LEFT_CARD) }
        coVerify(exactly = 0) { helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, any()) }
        assertFalse("nothing was hidden, so there is nothing to put back", prefs.contains(KEY_SAVED_MENU))
        assertEquals(
            listOf(
                "ui7 frame: menu status unreadable, side cards left alone",
                "ui7 frame: center 7->2 left 0->1 ok=true",
            ),
            journal,
        )
    }

    @Test fun `a re-apply keeps the first stock pair and does not read again`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)

        // A reproject/resize would read OUR values back and save them as the stock pair.
        stubOurFrameRead()
        assertTrue(frame.apply(helper, PROJECTION))

        coVerify(exactly = 1) { helper.read(DEV_INSTRUMENT, FID_CENTER) }
        coVerify(exactly = 1) { helper.read(DEV_INSTRUMENT, FID_LEFT) }
        coVerify(exactly = 1) { helper.read(DEV_INSTRUMENT, FID_MENU_STATUS) }
        assertEquals(stockCenter, prefs.getInt(KEY_SAVED_CENTER, -1))
        assertEquals(stockLeft, prefs.getInt(KEY_SAVED_LEFT, -1))
    }

    @Test fun `a pair persisted by a dead process is applied without a fresh read`() = runTest {
        // Crash mid-projection: the pair survived, the registers now hold OUR frame. Reading them
        // again would save 2 and 1 as the stock pair and the cluster would never come back.
        persistStockTriple()
        stubOurFrameRead(); stubWrites()
        val frame = frame(backgroundScope)

        assertTrue(frame.apply(helper, PROJECTION))

        coVerify(exactly = 0) { helper.read(any(), any(), any()) }
        assertEquals(stockCenter, prefs.getInt(KEY_SAVED_CENTER, -1))
        assertEquals(stockLeft, prefs.getInt(KEY_SAVED_LEFT, -1))
    }

    @Test fun `an unreadable register leaves the cluster untouched`() = runTest {
        coEvery { helper.read(DEV_INSTRUMENT, FID_CENTER) } returns stockCenter.toLong()
        coEvery { helper.read(DEV_INSTRUMENT, FID_LEFT) } returns null
        stubWrites()
        val frame = frame(backgroundScope)

        assertFalse(frame.apply(helper, PROJECTION))

        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertFalse(prefs.contains(KEY_SAVED_CENTER))
        assertFalse(prefs.contains(KEY_SAVED_LEFT))
        assertEquals(listOf("ui7 frame: read failed, skipped"), journal)
    }

    @Test fun `a sentinel is not a value - it is an unreadable register`() = runTest {
        // -10011 = wrong direction. Persisting it would put garbage back on the cluster at restore.
        coEvery { helper.read(DEV_INSTRUMENT, FID_CENTER) } returns -10011L
        coEvery { helper.read(DEV_INSTRUMENT, FID_LEFT) } returns stockLeft.toLong()
        stubWrites()
        val frame = frame(backgroundScope)

        assertFalse(frame.apply(helper, PROJECTION))

        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertFalse(prefs.contains(KEY_SAVED_CENTER))
        assertFalse(prefs.contains(KEY_SAVED_LEFT))
        assertEquals(listOf("ui7 frame: read failed, skipped"), journal)
    }

    @Test fun `a center write that is a no-op means the car has no Map frame`() = runTest {
        stubStockRead(); stubWrites(status = statusNoop)
        val frame = frame(backgroundScope)

        assertFalse(frame.apply(helper, PROJECTION))

        // Nothing moved: no LEFT write, no re-assert, and no pair for a restore to put back.
        coVerify(exactly = 0) { helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, LEFT_CARD) }
        assertFalse(frame.isReassertRunning())
        assertFalse(frame.hasStalePair())
        assertFalse(prefs.contains(KEY_SAVED_CENTER))
        assertFalse(prefs.contains(KEY_SAVED_LEFT))
        assertEquals(listOf("ui7 frame: center write no-op, frame unsupported here"), journal)
    }

    @Test fun `a pair that could not be persisted is never written to the car`() = runTest {
        // Write-ahead or nothing: a pair that is not on disk cannot be recovered after a death.
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val store = mockk<SharedPreferences>(relaxed = true)
        every { store.contains(any()) } returns false
        every { store.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.commit() } returns false
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope, store = store)

        assertFalse(frame.apply(helper, PROJECTION))

        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertEquals(listOf("ui7 frame: save failed, skipped"), journal)
    }

    @Test fun `restore writes LEFT then CENTER with the stock values and drops the pair`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        stubOurFrameRead()
        journal.clear()

        frame.restore(helper, PROJECTION)

        coVerifyOrder {
            helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, stockLeft)
            helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter)
            helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, stockMenu)
        }
        assertEquals(listOf("ui7 frame: restored left=0 center=7 menu=1 ok=true"), journal)
        assertFalse("the saved pair must be gone after a confirmed restore", prefs.contains(KEY_SAVED_CENTER))
        assertFalse(prefs.contains(KEY_SAVED_LEFT))
        assertFalse(prefs.contains(KEY_SAVED_MENU))
        assertFalse(frame.hasStalePair())
    }

    @Test fun `restore leaves CENTER alone when it is no longer our frame`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        // The driver cycled the cluster mode past the map: the firmware owns the center zone now.
        coEvery { helper.read(DEV_INSTRUMENT, FID_CENTER) } returns 1L
        journal.clear()

        frame.restore(helper, PROJECTION)

        coVerify(exactly = 1) { helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, stockLeft) }
        coVerify(exactly = 0) { helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter) }
        assertEquals(listOf("ui7 frame: restored left=0 center kept=1 menu=1 ok=true"), journal)
        assertFalse(frame.hasStalePair())
    }

    @Test fun `restore stops the re-assert job`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        stubOurFrameRead()

        frame.restore(helper, PROJECTION)
        assertFalse(frame.isReassertRunning())

        clearMocks(helper, answers = false)
        advanceTimeBy(REASSERT_INTERVAL_MS * 3)
        runCurrent()
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `a rejected restore keeps the pair for a later retry`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        stubOurFrameRead()
        coEvery { helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter) } returns null
        journal.clear()

        frame.restore(helper, PROJECTION)

        assertEquals(listOf("ui7 frame: restored left=0 center=7 menu=1 ok=false"), journal)
        assertEquals(stockCenter, prefs.getInt(KEY_SAVED_CENTER, -1))
        assertEquals(stockLeft, prefs.getInt(KEY_SAVED_LEFT, -1))
        assertEquals(stockMenu, prefs.getInt(KEY_SAVED_MENU, -1))
        assertTrue("an unconfirmed restore is exactly what recovery is for", frame.hasStalePair())
    }

    @Test fun `restore puts the stock menu status back verbatim`() = runTest {
        // A driver who already hid the instrument menu with the native toggle gets 2 back, not our
        // idea of a default: the cards stay hidden after the projection, exactly as before it.
        stubStockRead()
        coEvery { helper.read(DEV_INSTRUMENT, FID_MENU_STATUS) } returns MENU_HIDDEN.toLong()
        stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        stubOurFrameRead()
        journal.clear()

        frame.restore(helper, PROJECTION)

        coVerify(exactly = 2) { helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, MENU_HIDDEN) }
        assertEquals(listOf("ui7 frame: restored left=0 center=7 menu=2 ok=true"), journal)
        assertFalse(frame.hasStalePair())
    }

    @Test fun `a rejected menu restore keeps the triple for a retry`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        stubOurFrameRead()
        coEvery { helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, stockMenu) } returns null
        journal.clear()

        frame.restore(helper, PROJECTION)

        assertEquals(listOf("ui7 frame: restored left=0 center=7 menu=1 ok=false"), journal)
        assertEquals(stockCenter, prefs.getInt(KEY_SAVED_CENTER, -1))
        assertEquals(stockLeft, prefs.getInt(KEY_SAVED_LEFT, -1))
        assertEquals(stockMenu, prefs.getInt(KEY_SAVED_MENU, -1))
        assertTrue("side cards still hidden by us are what recovery is for", frame.hasStalePair())
    }

    @Test fun `a re-assert pass only rewrites the register that drifted`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        clearMocks(helper, answers = false)
        journal.clear()

        // Nothing drifted: the wheel knob was not touched.
        stubOurFrameRead()
        frame.reassertOnce(helper)

        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertTrue("a silent pass must not journal", journal.isEmpty())

        // The firmware took the center zone back when the driver cycled the cluster mode.
        coEvery { helper.read(DEV_INSTRUMENT, FID_CENTER) } returns stockCenter.toLong()
        frame.reassertOnce(helper)

        coVerify(exactly = 1) { helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, CENTER_MAP) }
        coVerify(exactly = 0) { helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, LEFT_CARD) }
        assertEquals(listOf("ui7 frame: reasserted center=7->2 ok=true"), journal)
    }

    @Test fun `a re-assert pass never touches the menu register`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        clearMocks(helper, answers = false)
        journal.clear()

        // The firmware brought the side cards back for its ~15 s wheel-button window: a pass that
        // hid them again would be fighting the driver's own button press.
        stubOurFrameRead()
        coEvery { helper.read(DEV_INSTRUMENT, FID_MENU_STATUS) } returns stockMenu.toLong()
        frame.reassertOnce(helper)

        coVerify(exactly = 0) { helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, any()) }
        coVerify(exactly = 0) { helper.read(DEV_INSTRUMENT, FID_MENU_STATUS) }
        assertTrue(journal.isEmpty())
    }

    @Test fun `a re-assert pass that cannot read writes nothing and gives up after three`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        assertTrue(frame.isReassertRunning())
        clearMocks(helper, answers = false)
        journal.clear()

        coEvery { helper.read(DEV_INSTRUMENT, FID_CENTER) } returns null
        coEvery { helper.read(DEV_INSTRUMENT, FID_LEFT) } returns null
        repeat(MAX_REASSERT_FAILURES) { frame.reassertOnce(helper) }

        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertFalse("a car that keeps refusing the registers is not worth 5 s polling", frame.isReassertRunning())
        assertEquals(listOf("ui7 frame: reassert stopped after 3 failures"), journal)
    }

    @Test fun `a pair left by a dead process is restored at service start`() = runTest {
        persistStockTriple()
        stubOurFrameRead(); stubWrites()
        val frame = frame(backgroundScope)
        assertTrue(frame.hasStalePair())

        frame.recoverStale(helper)

        coVerifyOrder {
            helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, stockLeft)
            helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter)
            helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, stockMenu)
        }
        assertFalse(prefs.contains(KEY_SAVED_CENTER))
        assertFalse(prefs.contains(KEY_SAVED_LEFT))
        assertFalse(prefs.contains(KEY_SAVED_MENU))
        assertEquals(
            listOf(
                "ui7 frame: recovering frame left by a prior session",
                "ui7 frame: restored left=0 center=7 menu=1 ok=true",
            ),
            journal,
        )
    }

    @Test fun `a pair persisted before the menu register existed restores center and left only`() = runTest {
        // Upgrade over a live projection: that pair knows nothing about the side cards, so whatever
        // the menu register holds now is the driver's setting and not ours to write.
        prefs.edit().putInt(KEY_SAVED_CENTER, stockCenter).putInt(KEY_SAVED_LEFT, stockLeft).commit()
        stubOurFrameRead(); stubWrites()
        val frame = frame(backgroundScope)

        frame.recoverStale(helper)

        coVerifyOrder {
            helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, stockLeft)
            helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter)
        }
        coVerify(exactly = 0) { helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, any()) }
        assertEquals(
            listOf(
                "ui7 frame: recovering frame left by a prior session",
                "ui7 frame: restored left=0 center=7 ok=true",
            ),
            journal,
        )
        assertFalse(frame.hasStalePair())
    }

    @Test fun `recovery does nothing while this process holds the frame`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        journal.clear()

        frame.recoverStale(helper)

        assertFalse(frame.hasStalePair())
        coVerify(exactly = 1) { helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, CENTER_MAP) }
        coVerify(exactly = 0) { helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter) }
        assertTrue(journal.isEmpty())
    }

    @Test fun `a second owner joins the open frame instead of re-opening it`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        stubOurFrameRead()
        journal.clear()

        // The camera lights up while the navigation projection is already on the cluster.
        assertTrue(frame.apply(helper, CAMERA))

        coVerify(exactly = 1) { helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, CENTER_MAP) }
        coVerify(exactly = 1) { helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, LEFT_CARD) }
        coVerify(exactly = 1) { helper.read(DEV_INSTRUMENT, FID_CENTER) }
        assertTrue("the joining owner must not stop the re-assert job", frame.isReassertRunning())
        assertEquals(listOf("ui7 frame: joined by camera"), journal)
    }

    @Test fun `the frame goes back only when the last owner lets go`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        frame.apply(helper, CAMERA)
        stubOurFrameRead()
        clearMocks(helper, answers = false)
        journal.clear()

        // Blinker off while the projection still draws: giving the cluster back here would black
        // out the navigation under it.
        frame.restore(helper, CAMERA)

        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertTrue(frame.isReassertRunning())
        assertEquals(listOf("ui7 frame: kept, still held by projection"), journal)

        journal.clear()
        frame.restore(helper, PROJECTION)

        coVerifyOrder {
            helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, stockLeft)
            helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter)
            helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, stockMenu)
        }
        assertFalse(frame.isReassertRunning())
        assertEquals(listOf("ui7 frame: restored left=0 center=7 menu=1 ok=true"), journal)
    }

    @Test fun `a restore by an owner that never applied writes nothing`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)
        stubOurFrameRead()
        clearMocks(helper, answers = false)
        journal.clear()

        // Camera teardown on a car where the compositor was never powered by it.
        frame.restore(helper, CAMERA)

        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertTrue(frame.isReassertRunning())
        assertTrue(journal.isEmpty())
    }

    @Test fun `the camera opens the frame and the joining projection is the one that gives it back`() = runTest {
        // Mirror of the case above: the blinker comes first, the navigation projection starts while
        // the camera is on the cluster, and the camera lets go first.
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, CAMERA)
        stubOurFrameRead()
        assertTrue(frame.apply(helper, PROJECTION))
        clearMocks(helper, answers = false)
        journal.clear()

        frame.restore(helper, CAMERA)

        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertTrue(frame.isReassertRunning())
        assertEquals(listOf("ui7 frame: kept, still held by projection"), journal)

        journal.clear()
        frame.restore(helper, PROJECTION)

        coVerifyOrder {
            helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, stockLeft)
            helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter)
            helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, stockMenu)
        }
        assertFalse(frame.isReassertRunning())
        assertFalse(frame.hasStalePair())
        assertEquals(listOf("ui7 frame: restored left=0 center=7 menu=1 ok=true"), journal)
    }

    @Test fun `the camera alone round-trips the frame`() = runTest {
        // A blind-spot alert with no projection anywhere: the camera reads the stock pair, opens
        // the frame and puts it back by itself.
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)

        assertTrue(frame.apply(helper, CAMERA))
        assertTrue(frame.isReassertRunning())
        stubOurFrameRead()
        journal.clear()

        frame.restore(helper, CAMERA)

        coVerifyOrder {
            helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, CENTER_MAP)
            helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, LEFT_CARD)
            helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, MENU_HIDDEN)
            helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, stockLeft)
            helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter)
            helper.writeStatus(DEV_INSTRUMENT, FID_MENU_SET, stockMenu)
        }
        assertFalse(frame.isReassertRunning())
        assertFalse(frame.hasStalePair())
        assertEquals(listOf("ui7 frame: restored left=0 center=7 menu=1 ok=true"), journal)
    }

    @Test fun `a cancellation inside the restore still hands the cluster back`() = runTest {
        // The camera cancels its compositor job on a fast LEFT->NONE->LEFT flicker (150 ms ticks),
        // which lands right inside this restore. Giving up half way would leave our frame on the
        // cluster with no owner and no re-assert job, and the next apply would only join it.
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, CAMERA)
        stubOurFrameRead()
        journal.clear()

        val inFirstWrite = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        coEvery { helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, stockLeft) } coAnswers {
            inFirstWrite.complete(Unit)
            releaseWrite.await()
            statusReal
        }
        val restoring = backgroundScope.launch { frame.restore(helper, CAMERA) }
        inFirstWrite.await()
        restoring.cancel()
        releaseWrite.complete(Unit)
        restoring.join()

        coVerifyOrder {
            helper.writeStatus(DEV_INSTRUMENT, FID_LEFT, stockLeft)
            helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, stockCenter)
        }
        assertEquals(listOf("ui7 frame: restored left=0 center=7 menu=1 ok=true"), journal)
        assertFalse(frame.hasStalePair())

        // The frame is really released, not just half written: the next owner opens it again
        // instead of joining a frame that is no longer on the car.
        stubStockRead()
        journal.clear()
        assertTrue(frame.apply(helper, CAMERA))

        coVerify(exactly = 2) { helper.writeStatus(DEV_INSTRUMENT, FID_CENTER, CENTER_MAP) }
        assertEquals(listOf("ui7 frame: center 7->2 left 0->1 menu 1->2 ok=true"), journal)
    }

    @Test fun `a joining owner restarts a re-assert job that had given up`() = runTest {
        stubStockRead(); stubWrites()
        val frame = frame(backgroundScope)
        frame.apply(helper, PROJECTION)

        coEvery { helper.read(DEV_INSTRUMENT, FID_CENTER) } returns null
        coEvery { helper.read(DEV_INSTRUMENT, FID_LEFT) } returns null
        repeat(MAX_REASSERT_FAILURES) { frame.reassertOnce(helper) }
        assertFalse(frame.isReassertRunning())
        journal.clear()

        // The registers answer again by the time the camera joins; without a job the next knob
        // turn would take the center zone back with nobody to write it again.
        stubOurFrameRead()
        assertTrue(frame.apply(helper, CAMERA))

        assertTrue("a joiner must never sit on an unguarded frame", frame.isReassertRunning())
        assertEquals(listOf("ui7 frame: joined by camera"), journal)
    }

    @Test fun `the manager hands out one frame instance for every owner`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertSame(
            "a second instance would race the first one's re-assert job",
            ClusterProjectionManager.frameFor(context),
            ClusterProjectionManager.frameFor(context),
        )
    }

    @Test fun `half a pair is no pair, and a pre-OTA car never has one`() = runTest {
        prefs.edit().putInt(KEY_SAVED_CENTER, stockCenter).commit()
        assertFalse("a half-written pair says nothing about what to restore", frame(backgroundScope).hasStalePair())

        prefs.edit().putInt(KEY_SAVED_LEFT, stockLeft).commit()
        assertTrue(frame(backgroundScope).hasStalePair())
        assertFalse(frame(backgroundScope, platformized = false).hasStalePair())
    }
}
