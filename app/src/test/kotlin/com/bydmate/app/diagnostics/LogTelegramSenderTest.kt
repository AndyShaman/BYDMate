package com.bydmate.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TgBackupConfig
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The automatic upload of a finished log recording to the user's bot (#324). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LogTelegramSenderTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val recorderState = MutableStateFlow(LogRecorder.State())
    private val recorder = mockk<LogRecorder> { every { state } returns recorderState }
    private val settings = mockk<SettingsRepository>()
    private val sink = mockk<TelegramBackupSink>()
    private lateinit var sender: LogTelegramSender

    private var autoSend = true
    private var config = TgBackupConfig(token = "123:abc", chatId = 777L, botName = "my_car_bot", chatName = "Andy")

    @Before fun setUp() {
        coEvery { settings.isTgLogAutoSendEnabled() } answers { autoSend }
        coEvery { settings.getTgBackupConfig() } answers { config }
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.success(Unit)
        sender = LogTelegramSender(recorder, settings, sink, AppStrings(ctx))
    }

    private fun log(bytes: Long = 10): File =
        File.createTempFile("bydmate_logs_", ".txt").apply {
            deleteOnExit()
            java.io.RandomAccessFile(this, "rw").use { it.setLength(bytes) }
        }

    private fun TestScope.start() {
        backgroundScope.launch { sender.run() }
        runCurrent()
    }

    private fun stop(file: File, sizeKb: Long = 0, finished: Boolean = true) {
        recorderState.value = LogRecorder.State(lastStopped = LogRecorder.Stopped(file.absolutePath, sizeKb, finished))
    }

    @Test fun `a finished recording goes to the bound bot once`() = runTest {
        val file = log()
        start()
        recorderState.value = LogRecorder.State(isRecording = true, filePath = file.absolutePath)
        runCurrent()
        stop(file)
        runCurrent()

        coVerify(exactly = 1) { sink.sendDocument("123:abc", 777L, file, file.name) }
    }

    @Test fun `the same stopped file emitted again is not sent again`() = runTest {
        val file = log()
        start()
        stop(file, sizeKb = 0)
        runCurrent()
        stop(file, sizeKb = 1)
        runCurrent()

        // A restarted service observes the same state again in the same process.
        backgroundScope.launch { sender.run() }
        runCurrent()

        coVerify(exactly = 1) { sink.sendDocument(any(), any(), any(), any()) }
    }

    @Test fun `the setting off sends nothing`() = runTest {
        autoSend = false
        start()
        stop(log())
        runCurrent()

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any()) }
    }

    @Test fun `no bound bot sends nothing`() = runTest {
        config = TgBackupConfig(token = "", chatId = null, botName = "", chatName = "")
        start()
        stop(log())
        runCurrent()

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any()) }
    }

    @Test fun `a file over the Telegram limit is not sent`() = runTest {
        start()
        stop(log(TelegramBackupSink.MAX_UPLOAD_BYTES + 1))
        runCurrent()

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any()) }
    }

    @Test fun `a recording that will resume is not sent until it really ends`() = runTest {
        val file = log()
        start()
        // Resumed after the power-off: recording again, nothing stopped.
        recorderState.value = LogRecorder.State(isRecording = true, filePath = file.absolutePath)
        runCurrent()
        // Logcat died on its own; the pending record stays for the next resume.
        stop(file, finished = false)
        runCurrent()
        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any()) }

        stop(file, sizeKb = 1)
        runCurrent()
        coVerify(exactly = 1) { sink.sendDocument(any(), any(), any(), any()) }
    }

    @Test fun `a manual send works with the setting off and reports the result`() = runTest {
        autoSend = false
        val file = log()
        val results = mutableListOf<String>()
        backgroundScope.launch { sender.results.collect { results += it } }
        runCurrent()

        sender.send(file)
        runCurrent()

        coVerify(exactly = 1) { sink.sendDocument("123:abc", 777L, file, file.name) }
        assertEquals(AppStrings(ctx).get(com.bydmate.app.R.string.settings_log_tg_sent), results.last())
        assertEquals(false, sender.sending.value)
    }
}
