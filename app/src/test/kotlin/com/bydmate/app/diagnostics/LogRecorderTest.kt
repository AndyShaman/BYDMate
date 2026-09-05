package com.bydmate.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LogRecorderTest {

    /** Stdout of a logcat that keeps running: EOF only once the process is destroyed. */
    private class OpenStream : InputStream() {
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            closed.await()
            return -1
        }

        override fun close() {
            closed.countDown()
        }
    }

    private class FakeProcess(
        val args: Array<String>,
        private val stdout: InputStream,
    ) : Process() {
        @Volatile
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
            runCatching { stdout.close() }
        }
    }

    private val spawned = CopyOnWriteArrayList<Array<String>>()
    private val processes = CopyOnWriteArrayList<FakeProcess>()

    /** Only the recording processes; `logcat -c` is spawned and waited for, never destroyed. */
    private val recordings: List<FakeProcess>
        get() = processes.filter { it.args.size > 2 }

    private fun recorder(
        autoStopMs: Long = 2 * 60 * 60 * 1000L,
        maxSizeBytes: Long = 50 * 1024 * 1024L,
        stdout: () -> InputStream = { OpenStream() },
    ): LogRecorder = LogRecorder(
        ApplicationProvider.getApplicationContext<Context>(),
        autoStopMs,
        maxSizeBytes,
    ) { args ->
        spawned += args
        val stream = if (args.size > 2) stdout() else ByteArrayInputStream(ByteArray(0))
        FakeProcess(args, stream).also { processes += it }
    }

    private suspend fun LogRecorder.startWithHeader(header: String = "header\n") =
        start { file: File -> file.writeText(header) }

    /**
     * Waits on real time: the recorder pipes and tears down on its own IO scope,
     * which the test scheduler's virtual time does not drive.
     */
    private suspend fun LogRecorder.awaitStopped(): LogRecorder.State =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { state.first { !it.isRecording && it.lastStopped != null } }
        }

    @Test
    fun `start writes the header and spawns one logcat`() = runTest {
        val recorder = recorder()

        val result = recorder.startWithHeader()

        assertTrue(result is LogRecorder.StartResult.Started)
        val file = (result as LogRecorder.StartResult.Started).file
        assertTrue(file.readText().startsWith("header"))
        // logcat -c, then the recording process itself.
        assertEquals(2, spawned.size)
        assertEquals(listOf("logcat", "-c"), spawned[0].toList())
        assertTrue(spawned[1].contains("BootReceiver:*"))

        val state = recorder.state.value
        assertTrue(state.isRecording)
        assertEquals(file.absolutePath, state.filePath)
        assertTrue(state.startedAtMs > 0L)

        recorder.stop()
    }

    @Test
    fun `second start while recording is a no-op`() = runTest {
        val recorder = recorder()
        val first = recorder.startWithHeader()
        val firstPath = recorder.state.value.filePath

        val second = recorder.startWithHeader("overwritten\n")

        assertEquals(LogRecorder.StartResult.AlreadyRecording, second)
        // No second logcat, and the buffer of the running one was not cleared again.
        assertEquals(2, spawned.size)
        assertEquals(1, recordings.size)
        assertEquals(firstPath, recorder.state.value.filePath)
        assertTrue((first as LogRecorder.StartResult.Started).file.readText().startsWith("header"))

        recorder.stop()
    }

    @Test
    fun `stop kills the process and resets the state`() = runTest {
        val recorder = recorder()
        val started = recorder.startWithHeader() as LogRecorder.StartResult.Started

        val stopped = recorder.stop()

        assertNotNull(stopped)
        assertEquals(started.file.absolutePath, stopped!!.path)
        assertTrue(recordings.single().destroyed)
        val state = recorder.state.value
        assertFalse(state.isRecording)
        assertNull(state.filePath)
        assertEquals(stopped, state.lastStopped)
    }

    @Test
    fun `stop without a recording reports nothing`() = runTest {
        assertNull(recorder().stop())
    }

    @Test
    fun `a later observer sees the running recording`() = runTest {
        val recorder = recorder()
        recorder.startWithHeader()

        // What a freshly created ViewModel gets when it subscribes: the running
        // recording, not a default "not recording" state.
        val observed = recorder.state.value
        assertTrue(observed.isRecording)
        assertNotNull(observed.filePath)

        recorder.stop()
        assertFalse(recorder.state.value.isRecording)
    }

    @Test
    fun `start after stop records into a new file`() = runTest {
        val recorder = recorder()
        recorder.startWithHeader()
        recorder.stop()

        val restarted = recorder.startWithHeader()

        assertTrue(restarted is LogRecorder.StartResult.Started)
        assertEquals(4, spawned.size)
        assertTrue(recorder.state.value.isRecording)

        recorder.stop()
    }

    @Test
    fun `logcat dying on its own stops the recording and reports the file`() = runTest {
        // Stdout at EOF right away: the process is gone, the recorder must not stay
        // "recording" forever.
        val recorder = recorder(stdout = { ByteArrayInputStream(ByteArray(0)) })
        val started = recorder.startWithHeader() as LogRecorder.StartResult.Started

        val state = recorder.awaitStopped()

        assertFalse(state.isRecording)
        assertNull(state.filePath)
        assertEquals(started.file.absolutePath, state.lastStopped!!.path)
        assertTrue(recordings.single().destroyed)
        // The recorder is free again.
        assertTrue(recorder.startWithHeader() is LogRecorder.StartResult.Started)
        recorder.stop()
    }

    @Test
    fun `size limit stops the recording and kills logcat`() = runTest {
        val line = "x".repeat(200)
        val recorder = recorder(
            maxSizeBytes = 2048L,
            stdout = { ByteArrayInputStream((line + "\n").repeat(500).toByteArray()) },
        )
        val started = recorder.startWithHeader() as LogRecorder.StartResult.Started

        val state = recorder.awaitStopped()

        val text = started.file.readText()
        assertTrue(text.contains("LOG STOPPED: file size limit reached"))
        // Stopped at the limit, not after draining all 100 KB of input.
        assertTrue(started.file.length() < 10 * 1024)
        assertTrue(recordings.single().destroyed)
        assertFalse(state.isRecording)
        assertEquals(started.file.absolutePath, state.lastStopped!!.path)
    }

    @Test
    fun `auto stop fires at the deadline`() = runTest {
        val recorder = recorder(autoStopMs = 50L)
        val started = recorder.startWithHeader() as LogRecorder.StartResult.Started

        val state = recorder.awaitStopped()

        assertTrue(recordings.single().destroyed)
        assertFalse(state.isRecording)
        assertEquals(started.file.absolutePath, state.lastStopped!!.path)
    }

    @Test
    fun `a failed start leaves the recorder free`() = runTest {
        val recorder = recorder()

        val failed = recorder.start { throw IllegalStateException("no disk") }

        assertTrue(failed is LogRecorder.StartResult.Failed)
        assertFalse(recorder.state.value.isRecording)
        assertTrue(recordings.all { it.destroyed })

        assertTrue(recorder.startWithHeader() is LogRecorder.StartResult.Started)
        recorder.stop()
    }

    @Test
    fun `stop racing a start leaves no orphan logcat`() = runTest {
        val recorder = recorder()

        val starter = launch(Dispatchers.IO) { recorder.startWithHeader() }
        recorder.stop()
        starter.join()

        // Whoever won the lock, there is at most one recording and stopping it once
        // more leaves nothing alive.
        recorder.stop()
        assertTrue(recordings.size <= 1)
        assertTrue(recordings.all { it.destroyed })
        assertFalse(recorder.state.value.isRecording)

        // And the recorder still starts afterwards.
        assertTrue(recorder.startWithHeader() is LogRecorder.StartResult.Started)
        assertTrue(recorder.state.value.isRecording)
        recorder.stop()
    }

    private fun pendingPrefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("log_recorder", Context.MODE_PRIVATE)

    @Test
    fun `start remembers the pending recording and stop forgets it`() = runTest {
        val recorder = recorder()
        val started = recorder.startWithHeader() as LogRecorder.StartResult.Started

        val prefs = pendingPrefs()
        assertEquals(started.file.absolutePath, prefs.getString("file_path", null))
        assertEquals(recorder.state.value.startedAtMs, prefs.getLong("started_at_ms", 0L))

        recorder.stop()

        assertNull(prefs.getString("file_path", null))
        assertEquals(0L, prefs.getLong("started_at_ms", 0L))
    }

    @Test
    fun `resume appends to the same file without clearing the buffer`() = runTest {
        val file = File.createTempFile("bydmate_logs_", ".txt")
        file.writeText("header\nold line\n")
        val startedAtMs = System.currentTimeMillis() - 60_000L
        pendingPrefs().edit()
            .putString("file_path", file.absolutePath)
            .putLong("started_at_ms", startedAtMs)
            .apply()
        // A recorder of a fresh process: nothing running, only the prefs left behind.
        val recorder = recorder()

        assertTrue(recorder.resumeIfPending())

        // Exactly one spawn, and it is the recording itself: no `logcat -c`.
        assertEquals(1, spawned.size)
        assertTrue(spawned[0].contains("BootReceiver:*"))
        val text = file.readText()
        assertTrue(text.startsWith("header"))
        assertTrue(text.contains("=== LOG RESUMED after app restart at "))
        assertTrue(text.contains("uptime "))
        val state = recorder.state.value
        assertTrue(state.isRecording)
        assertEquals(file.absolutePath, state.filePath)
        assertEquals(startedAtMs, state.startedAtMs)

        recorder.stop()
        file.delete()
    }

    @Test
    fun `resume past the auto stop window does nothing`() = runTest {
        val file = File.createTempFile("bydmate_logs_", ".txt")
        file.writeText("header\n")
        pendingPrefs().edit()
            .putString("file_path", file.absolutePath)
            .putLong("started_at_ms", System.currentTimeMillis() - 3 * 60 * 60 * 1000L)
            .apply()
        val recorder = recorder()

        assertFalse(recorder.resumeIfPending())

        assertTrue(spawned.isEmpty())
        assertFalse(recorder.state.value.isRecording)
        assertEquals("header\n", file.readText())
        assertNull(pendingPrefs().getString("file_path", null))
        file.delete()
    }

    @Test
    fun `a not yet mounted file keeps the pending recording for the next attempt`() = runTest {
        val file = File.createTempFile("bydmate_logs_", ".txt")
        val startedAtMs = System.currentTimeMillis() - 60_000L
        pendingPrefs().edit()
            .putString("file_path", file.absolutePath)
            .putLong("started_at_ms", startedAtMs)
            .apply()
        // Storage still unmounted: the file the prefs point at is not there yet.
        assertTrue(file.delete())
        val recorder = recorder()

        assertFalse(recorder.resumeIfPending())

        assertTrue(spawned.isEmpty())
        assertEquals(file.absolutePath, pendingPrefs().getString("file_path", null))
        assertEquals(startedAtMs, pendingPrefs().getLong("started_at_ms", 0L))

        // Storage came up; the retry picks the same recording back up.
        file.writeText("header\nold line\n")

        assertTrue(recorder.resumeIfPending())

        assertEquals(1, spawned.size)
        assertTrue(file.readText().contains("=== LOG RESUMED after app restart at "))
        val state = recorder.state.value
        assertTrue(state.isRecording)
        assertEquals(startedAtMs, state.startedAtMs)

        recorder.stop()
        file.delete()
    }

    @Test
    fun `a resumed logcat dying at once keeps the pending recording`() = runTest {
        val file = File.createTempFile("bydmate_logs_", ".txt")
        file.writeText("header\n")
        val startedAtMs = System.currentTimeMillis() - 60_000L
        pendingPrefs().edit()
            .putString("file_path", file.absolutePath)
            .putLong("started_at_ms", startedAtMs)
            .apply()
        // What READ_LOGS missing looks like: logcat starts and is at EOF right away.
        var stdoutAtEof = true
        val recorder = recorder(
            stdout = {
                if (stdoutAtEof) ByteArrayInputStream(ByteArray(0)) else OpenStream()
            },
        )

        assertTrue(recorder.resumeIfPending())
        recorder.awaitStopped()

        assertFalse(recorder.state.value.isRecording)
        assertEquals(file.absolutePath, pendingPrefs().getString("file_path", null))
        assertEquals(startedAtMs, pendingPrefs().getLong("started_at_ms", 0L))

        // The grant landed: the next attempt resumes the very same recording.
        stdoutAtEof = false

        assertTrue(recorder.resumeIfPending())

        assertEquals(file.absolutePath, recorder.state.value.filePath)
        assertEquals(startedAtMs, recorder.state.value.startedAtMs)
        assertEquals(2, file.readText().split("=== LOG RESUMED").size - 1)

        recorder.stop()
        file.delete()
    }

    @Test
    fun `the size limit forgets the pending recording`() = runTest {
        val line = "x".repeat(200)
        val recorder = recorder(
            maxSizeBytes = 2048L,
            stdout = { ByteArrayInputStream((line + "\n").repeat(500).toByteArray()) },
        )
        recorder.startWithHeader()

        recorder.awaitStopped()

        assertNull(pendingPrefs().getString("file_path", null))
        assertEquals(0L, pendingPrefs().getLong("started_at_ms", 0L))
    }

    @Test
    fun `stop after a dead pipe cancels the pending resume`() = runTest {
        val file = File.createTempFile("bydmate_logs_", ".txt")
        file.writeText("header\n")
        pendingPrefs().edit()
            .putString("file_path", file.absolutePath)
            .putLong("started_at_ms", System.currentTimeMillis() - 60_000L)
            .apply()
        val recorder = recorder(stdout = { ByteArrayInputStream(ByteArray(0)) })

        assertTrue(recorder.resumeIfPending())
        recorder.awaitStopped()

        // The pipe died on its own, so the recording is still pending a retry.
        assertFalse(recorder.state.value.isRecording)
        assertEquals(file.absolutePath, pendingPrefs().getString("file_path", null))

        // The user pressed stop in between: nothing left to stop, nothing left to resume.
        assertNull(recorder.stop())
        assertNull(pendingPrefs().getString("file_path", null))

        val spawnsBeforeResume = spawned.size
        assertFalse(recorder.resumeIfPending())
        assertEquals(spawnsBeforeResume, spawned.size)

        file.delete()
    }

    @Test
    fun `resume while recording is a no-op`() = runTest {
        val recorder = recorder()
        val started = recorder.startWithHeader() as LogRecorder.StartResult.Started
        val running = recorder.state.value

        assertFalse(recorder.resumeIfPending())

        // Still the same session: no extra logcat, no resume marker in the file.
        assertEquals(2, spawned.size)
        assertEquals(1, recordings.size)
        assertEquals(running, recorder.state.value)
        assertFalse(started.file.readText().contains("LOG RESUMED"))

        recorder.stop()
    }
}
