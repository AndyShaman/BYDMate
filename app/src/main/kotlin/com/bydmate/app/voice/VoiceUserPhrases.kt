package com.bydmate.app.voice

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.agent.AgentCommandCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** A built-in offline command the user may give own phrases: stable catalog id, Russian name
 *  (voice is Russian-only) and the dispatch string. */
data class VoiceUserCommand(val id: String, val name: String, val command: String)

/**
 * The user's own phrases for built-in offline commands (like the competitor's
 * `voice_cmd_overrides`): commandId → phrases, JSON in SharedPreferences("voice"). A phrase
 * matches only when it is the whole utterance, word for word after [VoicePhrase.isExact]
 * normalization. Null [prefs] keeps everything in memory (unit tests).
 */
@Singleton
class VoiceUserPhrases(private val prefs: SharedPreferences? = null) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val _phrases = MutableStateFlow(load())
    val phrases: StateFlow<Map<String, List<String>>> = _phrases.asStateFlow()

    sealed interface Check {
        data object Ok : Check
        data object Empty : Check
        data object TooLong : Check
        data object TooMany : Check
        /** The phrase already belongs to [owner]: another command's name or an automation's. */
        data class InUse(val owner: String) : Check
    }

    /** The command whose user phrase is the whole of [transcript]. */
    fun match(transcript: String): VoiceUserCommand? =
        _phrases.value.entries.firstNotNullOfOrNull { (id, list) ->
            byId[id]?.takeIf { list.any { VoicePhrase.isExact(transcript, it) } }
        }

    /** Normalized phrase ([VoicePhrase.normalize]) → command name, for the automation editor's
     *  collision check. */
    fun owners(): Map<String, String> = buildMap {
        for ((id, list) in _phrases.value) {
            val name = byId[id]?.name ?: continue
            list.forEach { putIfAbsent(VoicePhrase.normalize(it), name) }
        }
    }

    /** Whether [phrase] may be added to [commandId]. [automationPhrases] maps normalized
     *  automation phrases to rule names: automations are resolved first, so a clash would
     *  never reach the command. */
    fun check(commandId: String, phrase: String, automationPhrases: Map<String, String>): Check {
        val norm = VoicePhrase.normalize(phrase)
        if (norm.isBlank()) return Check.Empty
        if (phrase.trim().length > MAX_CHARS) return Check.TooLong
        if (_phrases.value[commandId].orEmpty().size >= MAX_PHRASES) return Check.TooMany
        for ((id, list) in _phrases.value) {
            if (list.any { VoicePhrase.isExact(phrase, it) }) return Check.InUse(byId[id]?.name ?: id)
        }
        automationPhrases[norm]?.let { return Check.InUse(it) }
        return Check.Ok
    }

    fun add(commandId: String, phrase: String) {
        val list = _phrases.value[commandId].orEmpty()
        save(_phrases.value + (commandId to (list + phrase.trim())))
    }

    fun remove(commandId: String, phrase: String) {
        val list = _phrases.value[commandId].orEmpty() - phrase
        save(if (list.isEmpty()) _phrases.value - commandId else _phrases.value + (commandId to list))
    }

    private fun save(map: Map<String, List<String>>) {
        _phrases.value = map
        val json = JSONObject()
        map.forEach { (id, list) -> json.put(id, JSONArray(list)) }
        prefs?.edit()?.putString(KEY, json.toString())?.apply()
    }

    private fun load(): Map<String, List<String>> {
        val raw = prefs?.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { id ->
                val arr = json.getJSONArray(id)
                (0 until arr.length()).map { arr.getString(it) }
            }
        }.onFailure { Log.w(TAG, "user phrases unreadable, starting empty: ${it.message}") }
            .getOrDefault(emptyMap())
    }

    companion object {
        private const val TAG = "VoiceUserPhrases"
        const val PREFS = "voice"
        const val KEY = "voice_cmd_overrides"
        const val MAX_PHRASES = 5
        const val MAX_CHARS = 40

        /** Fixed built-in offline commands (the parser's own catalog), named from the agent
         *  catalog. Ranged ones (temperature) need a number and cannot hang on a fixed phrase. */
        val COMMANDS: List<VoiceUserCommand> by lazy {
            val offline = VoiceCatalog.ALL.filter { it.value == null }.map { it.command(null) }.toSet()
            AgentCommandCatalog.ALL
                .filter { it.value == null && it.chinese(null) in offline }
                .map { VoiceUserCommand(it.id, it.ru, it.chinese(null)) }
        }

        private val byId: Map<String, VoiceUserCommand> by lazy { COMMANDS.associateBy { it.id } }
    }
}
