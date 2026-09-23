package com.bydmate.app.ui.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.backup.PostRestoreCheck
import com.bydmate.app.data.backup.PostRestoreReport
import com.bydmate.app.ui.settings.SpaceShortfall
import com.bydmate.app.voice.ContinuousAsr
import com.bydmate.app.voice.GigaAmModelManager
import com.bydmate.app.voice.RuStressMarker
import com.bydmate.app.voice.TtsModelManager
import com.bydmate.app.voice.TtsVoiceCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Progress of one model download started from the dialog; [percent] null = idle. */
data class PostRestoreDownload(
    val percent: Int? = null,
    val unpacking: Boolean = false,
    val failed: Boolean = false,
    /** Set when the failure was a lack of storage, as on the Settings screen. */
    val shortfall: SpaceShortfall? = null,
)

/** Glue between [PostRestoreCheck] and the dialog in MainActivity. */
@HiltViewModel
class PostRestoreViewModel @Inject constructor(
    private val check: PostRestoreCheck,
    private val gigaAmModelManager: GigaAmModelManager,
    private val ttsModelManager: TtsModelManager,
    private val ruStressMarker: RuStressMarker,
    private val continuousAsr: ContinuousAsr,
) : ViewModel() {

    val report: StateFlow<PostRestoreReport?> = check.report

    private val _asr = MutableStateFlow(PostRestoreDownload())
    val asr: StateFlow<PostRestoreDownload> = _asr.asStateFlow()

    private val _tts = MutableStateFlow(PostRestoreDownload())
    val tts: StateFlow<PostRestoreDownload> = _tts.asStateFlow()

    fun recheck() {
        viewModelScope.launch { check.recheck() }
    }

    fun dismiss() = check.dismiss()

    /** Same manager call and follow-up as the Settings screen's GigaAM download. */
    fun downloadAsr() {
        if (_asr.value.percent != null) return
        _asr.value = PostRestoreDownload(percent = 0)
        viewModelScope.launch {
            val result = gigaAmModelManager.download { phase, pct ->
                _asr.value = PostRestoreDownload(percent = pct, unpacking = phase == GigaAmModelManager.Phase.UNPACK)
            }
            _asr.value = PostRestoreDownload(
                failed = result.isFailure,
                shortfall = SpaceShortfall.from(result.exceptionOrNull()),
            )
            if (result.isSuccess) {
                viewModelScope.launch(Dispatchers.IO) { runCatching { continuousAsr.warmUp() } }
            }
            check.recheck()
        }
    }

    /** Same manager calls as the Settings screen's voice download. */
    fun downloadTts(voiceId: String) {
        if (_tts.value.percent != null) return
        _tts.value = PostRestoreDownload(percent = 0)
        viewModelScope.launch {
            val voice = TtsVoiceCatalog.byId(voiceId)
            val result = ttsModelManager.download(voice) { pct -> _tts.value = PostRestoreDownload(percent = pct) }
            if (result.isSuccess && ttsModelManager.ensureStressDict(voice)) ruStressMarker.preload()
            _tts.value = PostRestoreDownload(failed = result.isFailure)
            check.recheck()
        }
    }
}
