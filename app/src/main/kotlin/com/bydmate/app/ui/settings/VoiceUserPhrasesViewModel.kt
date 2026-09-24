package com.bydmate.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.bydmate.app.R
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.voice.VoicePhrase
import com.bydmate.app.voice.VoiceUserPhrases
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class VoiceUserPhrasesViewModel @Inject constructor(
    private val userPhrases: VoiceUserPhrases,
    private val ruleDao: RuleDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val phrases: StateFlow<Map<String, List<String>>> = userPhrases.phrases

    /** Adds [phrase] to [commandId]; returns the localized refusal, or null when added. */
    suspend fun add(commandId: String, phrase: String): String? {
        val automations = buildMap {
            for (rule in ruleDao.getAllList()) {
                TriggerDef.listFromJson(rule.triggers)
                    .filter { it.kind == "voice" && it.value.isNotBlank() }
                    .forEach { putIfAbsent(VoicePhrase.normalize(it.value), rule.name) }
            }
        }
        val ctx = context.appLocalizedContext()
        return when (val c = userPhrases.check(commandId, phrase, automations)) {
            VoiceUserPhrases.Check.Ok -> { userPhrases.add(commandId, phrase); null }
            VoiceUserPhrases.Check.Empty -> ctx.getString(R.string.automation_voice_phrase_empty)
            VoiceUserPhrases.Check.TooLong -> ctx.getString(R.string.voice_user_phrases_too_long, VoiceUserPhrases.MAX_CHARS)
            VoiceUserPhrases.Check.TooMany -> ctx.getString(R.string.voice_user_phrases_too_many, VoiceUserPhrases.MAX_PHRASES)
            is VoiceUserPhrases.Check.InUse -> ctx.getString(R.string.automation_voice_phrase_taken, c.owner)
        }
    }

    fun remove(commandId: String, phrase: String) = userPhrases.remove(commandId, phrase)
}
