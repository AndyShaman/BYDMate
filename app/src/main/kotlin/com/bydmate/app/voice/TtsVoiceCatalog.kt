package com.bydmate.app.voice

import com.bydmate.app.R

enum class TtsGender { MALE, FEMALE }

/** PIPER voices are single-speaker sherpa-onnx archives (model + tokens.txt +
 *  espeak-ng-data). VITS_MULTI is a single multispeaker archive (model +
 *  tokens.txt + stress.tsv, no espeak-ng-data) shared by several [TtsVoice]s
 *  that differ only by [TtsVoice.speakerId]. SUPERTONIC is a single
 *  multispeaker archive (4 onnx + tts.json + unicode_indexer.bin + voice.bin,
 *  no tokens.txt). */
enum class TtsVoiceEngine { PIPER, VITS_MULTI, SUPERTONIC }

/** One downloadable offline TTS voice. [id] is the pref value + UI key.
 *  [modelDirId] is the on-disk dir under filesDir/tts/<modelDirId> and the
 *  archive filename -- several voices can share one [modelDirId] (a
 *  multispeaker archive), selected apart by [speakerId] at synthesis time. */
data class TtsVoice(
    val id: String,
    val labelRes: Int,
    val url: String,
    val gender: TtsGender,
    val engine: TtsVoiceEngine,
    val modelDirId: String,
    val speakerId: Int,
    val sizeMb: Int,
)

/** The 6 Russian voices offered in Settings: 3 single-speaker piper archives
 *  (dmitri, ruslan, irina), 1 speaker (alena) of the VITS multispeaker
 *  archive, and 2 speakers (mark, sofia) of the Supertonic archive. */
object TtsVoiceCatalog {
    private const val VITS_MULTI_URL =
        "https://github.com/AndyShaman/BYDMate/releases/download/tts-voices-v1/vits-ru-multi.tar.bz2"
    private const val SUPERTONIC_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"

    val ALL: List<TtsVoice> = listOf(
        TtsVoice(
            id = "dmitri", labelRes = R.string.settings_tts_voice_dmitri, url = urlFor("dmitri"),
            gender = TtsGender.MALE, engine = TtsVoiceEngine.PIPER,
            modelDirId = "dmitri", speakerId = 0, sizeMb = 21,
        ),
        TtsVoice(
            id = "alena", labelRes = R.string.settings_tts_voice_alena, url = VITS_MULTI_URL,
            gender = TtsGender.FEMALE, engine = TtsVoiceEngine.VITS_MULTI,
            modelDirId = "vits-ru-multi", speakerId = 0, sizeMb = 18,
        ),
        TtsVoice(
            id = "ruslan", labelRes = R.string.settings_tts_voice_ruslan, url = urlFor("ruslan"),
            gender = TtsGender.MALE, engine = TtsVoiceEngine.PIPER,
            modelDirId = "ruslan", speakerId = 0, sizeMb = 21,
        ),
        TtsVoice(
            id = "irina", labelRes = R.string.settings_tts_voice_irina, url = urlFor("irina"),
            gender = TtsGender.FEMALE, engine = TtsVoiceEngine.PIPER,
            modelDirId = "irina", speakerId = 0, sizeMb = 21,
        ),
        TtsVoice(
            id = "mark", labelRes = R.string.settings_tts_voice_mark, url = SUPERTONIC_URL,
            gender = TtsGender.MALE, engine = TtsVoiceEngine.SUPERTONIC,
            modelDirId = "supertonic-ru", speakerId = 7, sizeMb = 145,
        ),
        TtsVoice(
            id = "sofia", labelRes = R.string.settings_tts_voice_sofia, url = SUPERTONIC_URL,
            gender = TtsGender.FEMALE, engine = TtsVoiceEngine.SUPERTONIC,
            modelDirId = "supertonic-ru", speakerId = 3, sizeMb = 145,
        ),
    )

    /** Unknown/corrupted pref value -- including the retired "artem"/"denis" ids --
     *  falls back to the new default voice. This IS the prefs migration. */
    fun byId(id: String): TtsVoice = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == "dmitri" }

    fun byGender(gender: TtsGender): List<TtsVoice> = ALL.filter { it.gender == gender }

    /** The other-gender speaker of the same engine; when the engine has no such speaker
     *  (alena is the sole VITS_MULTI voice), fall back to the first catalog voice of the
     *  other gender (list order puts the defaults first). */
    fun counterpart(voice: TtsVoice): TtsVoice =
        ALL.firstOrNull { it.engine == voice.engine && it.gender != voice.gender }
            ?: ALL.first { it.gender != voice.gender }

    private fun urlFor(id: String) =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-$id-medium-int8.tar.bz2"
}
