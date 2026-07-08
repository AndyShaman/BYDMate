package com.bydmate.app.ui.overlay

import android.content.Context
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM-only pins for ListeningOverlay's window lifecycle (Fix wave 1): the hide()/show() race
 * (Finding 1) and the addView-failure rollback (Finding 2). Exercises the real object through its
 * attachWindow/poster/permissionCheck test seams -- no real ComposeView/WindowManager/Looper
 * involved, so these tests need no Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningOverlayTest {

    private class FakeHandle : ListeningOverlay.OverlayHandle {
        @Volatile var destroyed = false
        override fun destroy() { destroyed = true }
    }

    private val context: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        // withContext(Dispatchers.Main) inside show() needs a Main dispatcher in a plain JVM test;
        // Unconfined runs it eagerly on the calling thread, so no manual scheduler pumping needed.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ListeningOverlay.permissionCheck = { true }
    }

    @After
    fun tearDown() {
        // ListeningOverlay is a process-wide singleton object -- leave it in a clean state so this
        // test's fakes never leak into another test in the same JVM fork.
        ListeningOverlay.poster = { it.run() } // make sure a pending hide() below runs synchronously
        ListeningOverlay.hide()
        ListeningOverlay.clearDialog() // dialog state is a process-wide singleton too -- reset it
        ListeningOverlay.attachWindow = { _, initial -> error("attachWindow not stubbed: $initial") }
        ListeningOverlay.permissionCheck = { context -> ListeningOverlay.canShow(context) }
        Dispatchers.resetMain()
    }

    // Finding 1 pin (a): a fast stop -> start must attach a genuinely new window, not no-op
    // against the not-yet-destroyed old one.
    @Test fun `hide then immediate show attaches a second window, not a no-op`() = runBlocking {
        var attachCount = 0
        ListeningOverlay.attachWindow = { _, _ -> attachCount++; FakeHandle() }
        ListeningOverlay.poster = { it.run() }

        ListeningOverlay.show(context, "Слушаю")
        assertEquals(1, attachCount)

        ListeningOverlay.hide()
        ListeningOverlay.show(context, "Слушаю")

        assertEquals(2, attachCount) // second show() actually attached a new window
    }

    // Finding 1 pin (b): the deferred cleanup must destroy only the OLD captured handle, even when
    // a new window is already active by the time it runs.
    @Test fun `deferred cleanup destroys only the OLD handle even when a new one is already active`() = runBlocking {
        val handles = mutableListOf<FakeHandle>()
        ListeningOverlay.attachWindow = { _, _ -> FakeHandle().also { handles.add(it) } }
        // Deferred poster: capture the runnable instead of running it immediately, so hide()'s
        // teardown can be fired AFTER a new show() has already attached.
        var deferred: Runnable? = null
        ListeningOverlay.poster = { r -> deferred = r }

        ListeningOverlay.show(context, "Слушаю")
        val oldHandle = handles[0]

        ListeningOverlay.hide() // captures oldHandle locally, clears `active`, defers destroy()
        ListeningOverlay.show(context, "Слушаю") // must NOT no-op -- attaches a new handle
        val newHandle = handles[1]

        deferred?.run() // now actually run the deferred cleanup

        assertTrue(oldHandle.destroyed)
        assertFalse(newHandle.destroyed)
    }

    // Finding 2 pin (c): an addView failure (attachWindow throwing) must not leave the object
    // permanently in shown-state -- a later show() has to try again.
    @Test fun `attach failure leaves state cleared so a later show works`() = runBlocking {
        var attempt = 0
        ListeningOverlay.attachWindow = { _, _ ->
            attempt++
            if (attempt == 1) throw RuntimeException("addView failed") else FakeHandle()
        }
        ListeningOverlay.poster = { it.run() }

        ListeningOverlay.show(context, "Слушаю") // attachWindow throws; must not commit `active`
        ListeningOverlay.show(context, "Слушаю") // must attempt again, not silently no-op forever

        assertEquals(2, attempt)
    }

    // Finding (Fix wave 2): the pre-dispatch `active != null` check runs on the caller's thread,
    // before withContext(Dispatchers.Main) -- two concurrent show() calls can both pass it while
    // active is still null. Uses a real single-thread Main dispatcher (not the class's Unconfined
    // one) so the two calls' Main blocks genuinely queue behind each other, and a latch inside
    // attachWindow holds the first call inside its Main block until the second call has had time
    // to pass its own pre-dispatch check and queue behind it -- reproducing the exact race window.
    @Test fun `two concurrent show calls attach exactly once`() = runBlocking {
        val mainExecutor = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        try {
            var attachCount = 0
            val firstAttachStarted = CountDownLatch(1)
            val releaseFirstAttach = CountDownLatch(1)
            ListeningOverlay.attachWindow = { _, _ ->
                attachCount++
                if (attachCount == 1) {
                    firstAttachStarted.countDown()
                    releaseFirstAttach.await(2, TimeUnit.SECONDS)
                }
                FakeHandle()
            }
            ListeningOverlay.poster = { it.run() }

            val job1 = launch(Dispatchers.IO) { ListeningOverlay.show(context, "a") }
            assertTrue(firstAttachStarted.await(2, TimeUnit.SECONDS)) // job1 is inside attachWindow; active still null
            val job2 = launch(Dispatchers.IO) { ListeningOverlay.show(context, "b") }
            delay(200) // let job2 pass its pre-dispatch check and queue on Main behind job1
            releaseFirstAttach.countDown() // let job1's attachWindow return and commit `active`
            job1.join()
            job2.join()

            assertEquals(1, attachCount)
        } finally {
            Dispatchers.resetMain()
            mainExecutor.shutdown()
        }
    }

    // --- Wave D2 Task 5: dialog block state + composite teardown ---

    // (a) The three dialog mutators are pure state changes -- calling them before show() must not
    // throw and must still update the observable state (the window just is not there to render it).
    @Test fun `dialog mutators are safe before show`() {
        ListeningOverlay.showHeard("x")
        assertEquals("x", ListeningOverlay.heardText)
        assertEquals(null, ListeningOverlay.answerText)
        ListeningOverlay.showAnswer("y")
        assertEquals("y", ListeningOverlay.answerText)
        ListeningOverlay.clearDialog()
        assertEquals(null, ListeningOverlay.heardText)
        assertEquals(null, ListeningOverlay.answerText)
    }

    // (b) State transitions once shown: showHeard sets the "Ты:" row and clears any prior answer;
    // showAnswer adds the "Агент:" row; clearDialog collapses the block but leaves the pill (text) up.
    @Test fun `showHeard resets answer and showAnswer adds it`() {
        ListeningOverlay.showAnswer("stale") // a leftover answer from a previous turn
        ListeningOverlay.showHeard("привет")
        assertEquals("привет", ListeningOverlay.heardText)
        assertEquals(null, ListeningOverlay.answerText) // showHeard cleared the stale answer

        ListeningOverlay.showAnswer("здравствуй")
        assertEquals("привет", ListeningOverlay.heardText) // heard untouched by showAnswer
        assertEquals("здравствуй", ListeningOverlay.answerText)

        ListeningOverlay.clearDialog()
        assertEquals(null, ListeningOverlay.heardText)
        assertEquals(null, ListeningOverlay.answerText)
    }

    // (c) hide() must tear down BOTH windows -- realAttach wraps the pill and dialog handles in a
    // CompositeOverlayHandle, whose destroy() forwards to every child.
    @Test fun `hide destroys every window the composite handle wraps`() = runBlocking {
        val pill = FakeHandle()
        val dialog = FakeHandle()
        ListeningOverlay.attachWindow = { _, _ ->
            ListeningOverlay.CompositeOverlayHandle(listOf(pill, dialog))
        }
        ListeningOverlay.poster = { it.run() }

        ListeningOverlay.show(context, "Слушаю")
        ListeningOverlay.hide()

        assertTrue(pill.destroyed)
        assertTrue(dialog.destroyed)
    }

    // The layout constants are load-bearing (pill below the status bar, dialog a fixed gap under it)
    // and cheap to pin so a future edit to either does not silently regress the geometry.
    @Test fun `layout constants are the agreed defaults`() {
        assertEquals(56, ListeningOverlay.TOP_MARGIN_DP)
        assertEquals(48, ListeningOverlay.PILL_OFFSET_DP)
    }
}
