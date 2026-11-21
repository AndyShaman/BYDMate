package com.bydmate.app.split

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.voice.VoicePhrase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An app set for the Android 10 head units (DiLink 3 / 4): two apps side by side, started by a
 * phrase of the driver's own choice or at the start of the service.
 *
 * [name] is what follows «сплит» in the voice command ([AppSetStore.resolve]). [leftPkg] ends up in the left half; [rightPkg] in the
 * right one, or null to leave there whatever is on screen at that moment.
 */
data class AppSet(
    val id: String,
    val name: String,
    val leftPkg: String,
    val rightPkg: String? = null,
    val autostart: Boolean = false,
)

/**
 * Whether the app sets are the split in use: on the Android 10 head units, with «Разделение экрана»
 * switched on and «Пополам 1:1» chosen ([AppSetStore.setsOn]). They run exactly as they did with
 * the split switched off: the author's split flag stays off then (its machinery idle), so only one
 * split works at a time. With «Окна BYDMate» or «Родное разделение» the widget tap, the split key,
 * the voice and the start open the saved pair, and no set opens.
 */
fun appSetsActive(legacyHeadUnit: Boolean, setsOn: Boolean): Boolean = legacyHeadUnit && setsOn

/**
 * The saved [AppSet]s, JSON in SharedPreferences("bydmate_app_sets"). Null [prefs] keeps everything
 * in memory (unit tests). At most one set starts with the service ([AppSet.autostart]).
 */
@Singleton
@Suppress("TooManyFunctions") // the sets, the phrase they answer to and the «Пополам 1:1» choice
class AppSetStore(private val prefs: SharedPreferences? = null) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val _sets = MutableStateFlow(load())
    val sets: StateFlow<List<AppSet>> = _sets.asStateFlow()

    private val _setsMode = MutableStateFlow(prefs?.getBoolean(KEY_SETS_MODE, false) ?: false)
    private val _setsOn = MutableStateFlow(prefs?.getBoolean(KEY_SETS_ON, false) ?: false)

    /** «Пополам 1:1» is the chosen mechanism (kept while the split is switched off). */
    val setsMode: StateFlow<Boolean> = _setsMode.asStateFlow()

    /** «Пополам 1:1» is chosen and the split is switched on: the sets run ([appSetsActive]). */
    val setsOn: StateFlow<Boolean> = _setsOn.asStateFlow()

    fun setSetsMode(on: Boolean) {
        _setsMode.value = on
        prefs?.edit()?.putBoolean(KEY_SETS_MODE, on)?.apply()
    }

    fun setSetsOn(on: Boolean) {
        _setsOn.value = on
        prefs?.edit()?.putBoolean(KEY_SETS_ON, on)?.apply()
    }

    /**
     * Once, on the first start of this version:
     * - the first app-set builds ran the sets with the split switched off: a head unit with sets
     *   and the split off gets «Пополам 1:1» chosen and on;
     * - the method switch («Основной / Альтернативный») kept the author's split on while the sets
     *   ran, and with it on the firmware split stopped working (on-car, 2026-11-20): it is switched
     *   back off, the sets keep running, and [onSplitOff] runs what the author's own switch-off runs
     *   next (the freeform flag back to 0).
     */
    fun migrateSetsMode(split: SplitPreferences, onSplitOff: () -> Unit = {}) {
        if (prefs == null || prefs.contains(KEY_SETS_ON)) return
        when {
            prefs.getBoolean(KEY_SETS_MODE, false) -> {
                val wasOn = split.isFeatureEnabled()
                setSetsOn(wasOn)
                split.setFeatureEnabled(false)
                if (wasOn) onSplitOff()
            }
            !split.isFeatureEnabled() && _sets.value.isNotEmpty() -> {
                setSetsMode(true)
                setSetsOn(true)
            }
            else -> {
                setSetsMode(false)
                setSetsOn(false)
            }
        }
    }

    sealed interface Check {
        data object Ok : Check
        data object NoName : Check
        data object NameTooLong : Check
        data object NoLeftApp : Check
        data object SameApp : Check
        data object TooMany : Check
        /** The name is already the phrase of another set. */
        data class NameInUse(val owner: String) : Check
    }

    /**
     * The set an utterance asks for, or null when it asks for none.
     *
     * - «сплит», «включи / открой / запусти сплит», «разделение экрана», «включи / открой разделение
     *   экрана», «раздели экран»: the default set, the first in the list.
     * - «сплит <название>» (also after «включи», «открой», «запусти»): the set with that name. The
     *   name is compared by its letters, without spaces, and a slip of the recognizer is forgiven
     *   ([nameDistanceLimit]): an invented word is written differently every time. With one set
     *   saved, whatever follows «сплит» is that set, unless [oneSetTakesAny] is false (the words
     *   name an app, which the voice then puts into a split of its own).
     *
     * The bare name never opens a set: it would collide with «открой тикток» and the like.
     */
    fun resolve(transcript: String, oneSetTakesAny: Boolean = true): AppSet? {
        val sets = _sets.value
        val heard = VoicePhrase.words(transcript)
        if (sets.isEmpty() || heard.isEmpty()) return null
        if (DEFAULT_PHRASES.any { it == heard }) return sets.first()
        val rest = heard.dropWhile { it in LEAD_VERBS }
        val prefix = rest.firstOrNull() ?: return null
        val name = rest.drop(1)
        if (name.isEmpty() || editDistance(prefix, SPLIT_WORD) > 1) return null
        if (sets.size == 1 && oneSetTakesAny) return sets.first()
        val spoken = name.joinToString("")
        val scored = sets.mapNotNull { set ->
            val key = VoicePhrase.words(set.name).joinToString("")
            val distance = editDistance(spoken, key)
            (set to distance).takeIf { distance <= nameDistanceLimit(key) }
        }
        val best = scored.minOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    /** The set the widget tap and the split key open: the first in the list. */
    fun defaultSet(): AppSet? = _sets.value.firstOrNull()

    fun autostartSet(): AppSet? = _sets.value.firstOrNull { it.autostart }

    fun get(id: String): AppSet? = _sets.value.firstOrNull { it.id == id }

    fun newId(): String = "set" + (_sets.value.mapNotNull { it.id.removePrefix("set").toIntOrNull() }.maxOrNull() ?: 0).plus(1)

    /** Whether [set] may be saved: a new one counts against [MAX_SETS], an existing one (same id) does not. */
    fun check(set: AppSet): Check {
        val name = set.name.trim()
        val norm = VoicePhrase.normalize(name)
        return when {
            norm.isBlank() -> Check.NoName
            name.length > MAX_NAME_CHARS -> Check.NameTooLong
            set.leftPkg.isBlank() -> Check.NoLeftApp
            set.leftPkg == set.rightPkg -> Check.SameApp
            _sets.value.none { it.id == set.id } && _sets.value.size >= MAX_SETS -> Check.TooMany
            else -> _sets.value.firstOrNull { it.id != set.id && VoicePhrase.normalize(it.name) == norm }
                ?.let { Check.NameInUse(it.name) } ?: Check.Ok
        }
    }

    /** Saves [set] after a successful [check]; a set that starts with the service takes that from the others. */
    fun upsert(set: AppSet) {
        val clean = set.copy(name = set.name.trim())
        val others = _sets.value.filter { it.id != clean.id }
            .map { if (clean.autostart && it.autostart) it.copy(autostart = false) else it }
        val index = _sets.value.indexOfFirst { it.id == clean.id }
        save(if (index < 0) others + clean else others.toMutableList().apply { add(index.coerceAtMost(size), clean) })
    }

    fun remove(id: String) = save(_sets.value.filter { it.id != id })

    private fun save(list: List<AppSet>) {
        _sets.value = list
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject()
                .put("id", it.id).put("name", it.name).put("left", it.leftPkg)
                .put("right", it.rightPkg ?: JSONObject.NULL).put("autostart", it.autostart))
        }
        prefs?.edit()?.putString(KEY, array.toString())?.apply()
    }

    private fun load(): List<AppSet> {
        val raw = prefs?.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                AppSet(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    leftPkg = o.getString("left"),
                    rightPkg = if (o.isNull("right")) null else o.getString("right"),
                    autostart = o.optBoolean("autostart", false),
                )
            }
        }.onFailure { Log.w(TAG, "app sets unreadable, starting empty: ${it.message}") }
            .getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "AppSetStore"
        private const val SPLIT_WORD = "сплит"
        private val LEAD_VERBS = setOf("включи", "открой", "запусти")
        private val DEFAULT_PHRASES = listOf(
            "сплит", "включи сплит", "открой сплит", "запусти сплит",
            "разделение экрана", "включи разделение экрана", "открой разделение экрана", "запусти разделение экрана",
            "включи разделение", "открой разделение", "раздели экран",
        ).map { VoicePhrase.words(it) }

        /** «сплит», «включи разделение экрана» and the like on their own: the split with no set named. */
        fun isBareSplitPhrase(transcript: String): Boolean =
            VoicePhrase.words(transcript).let { heard -> DEFAULT_PHRASES.any { it == heard } }

        /** How many letters the recognizer may have got wrong in a name of [length] letters. */
        internal fun nameDistanceLimit(key: String): Int = maxOf(1, key.length / 4)

        /** Levenshtein distance. */
        internal fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }
        const val PREFS_NAME = "bydmate_app_sets"
        const val KEY = "sets"
        const val KEY_SETS_MODE = "sets_mode"
        const val KEY_SETS_ON = "sets_on"
        const val MAX_SETS = 8
        const val MAX_NAME_CHARS = 40
    }
}
