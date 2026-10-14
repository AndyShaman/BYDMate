package com.bydmate.app.diagnostics

import android.util.Log
import com.bydmate.app.R
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramSinkException
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.settings.telegramErrorText
import com.bydmate.app.util.AppStrings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a saved log to the user's bot (#324): on the Settings button, and by itself when a
 * recording ends for good and «Отправлять записанный лог в Telegram» is on. App-scoped: the 2h
 * auto-stop may come while Settings is closed. One attempt per file, no retry queue.
 *
 * Log tag `TgReport` (already in the recorded tags); sizes and verdicts only, never the token.
 */
@Singleton
class LogTelegramSender @Inject constructor(
    private val recorder: LogRecorder,
    private val settings: SettingsRepository,
    private val sink: TelegramBackupSink,
    private val strings: AppStrings,
) {
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    // Every status line of an upload, for an open Settings screen; nothing is replayed to a later one.
    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val results: SharedFlow<String> = _results.asSharedFlow()

    // The last finished file the automatic path took up; on the singleton, so a service restarted
    // in the same process does not send it again.
    @Volatile
    private var handledPath: String? = null

    /** The automatic path, for the service's lifetime. */
    suspend fun run() {
        recorder.state.collect { state ->
            val stopped = state.lastStopped?.takeIf { it.finished } ?: return@collect
            if (stopped.path == handledPath) return@collect
            handledPath = stopped.path
            if (!settings.isTgLogAutoSendEnabled()) return@collect
            if (!settings.getTgBackupConfig().configured) {
                Log.i(TAG, "log to telegram: auto skipped, no bot")
                return@collect
            }
            Log.i(TAG, "log to telegram: auto")
            send(File(stopped.path))
        }
    }

    /** One upload of [file]; a call while another upload runs does nothing. */
    suspend fun send(file: File) {
        if (!_sending.compareAndSet(expect = false, update = true)) return
        try {
            _results.tryEmit(strings.get(R.string.settings_log_tg_sending))
            _results.tryEmit(upload(file))
        } finally {
            _sending.value = false
        }
    }

    private suspend fun upload(file: File): String {
        val config = settings.getTgBackupConfig()
        // The button shows only with a bound bot; this covers a disconnect racing the tap.
        val chatId = config.chatId ?: return strings.get(R.string.settings_log_tg_failed, "?")
        val size = if (file.isFile) file.length() else -1L
        if (size > TelegramBackupSink.MAX_UPLOAD_BYTES) {
            Log.w(TAG, "log to telegram: too big ${size / 1024} KB")
            return strings.get(R.string.settings_log_tg_too_big)
        }
        if (size < 0) {
            Log.w(TAG, "log to telegram: file missing")
            return strings.get(R.string.settings_log_tg_missing)
        }
        return sink.sendDocument(config.token, chatId, file, file.name).fold(
            onSuccess = {
                Log.i(TAG, "log to telegram: sent ${size / 1024} KB")
                strings.get(R.string.settings_log_tg_sent)
            },
            onFailure = { error ->
                Log.w(TAG, "log to telegram: failed ${(error as? TelegramSinkException)?.key ?: error.javaClass.simpleName}")
                strings.get(
                    R.string.settings_log_tg_failed,
                    (error as? TelegramSinkException)?.let { telegramErrorText(strings.context, it.key) } ?: "?",
                )
            },
        )
    }

    private companion object {
        const val TAG = "TgReport"
    }
}
