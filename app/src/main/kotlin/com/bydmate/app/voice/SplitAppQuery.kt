package com.bydmate.app.voice

import com.bydmate.app.split.AppSetStore

/**
 * The split with an app named on the spot (alternative method of the Android 10 head units): the
 * app goes into the left half, whatever is on screen stays in the right one. The name of the app is
 * what [name] returns; the app table decides whether it is an app ([LocalAppLaunchQuery]'s table).
 *
 * - «сплит <приложение>», «сплит с <приложение>» (also after «включи», «открой», «запусти»);
 * - «раздели экран с <приложение>», «разделение экрана с <приложение>»;
 * - «добавь <приложение> в сплит», «добавь в сплит <приложение>», «добавь <приложение> в
 *   разделение экрана», «добавь <приложение> к сплиту».
 *
 * A set name said after «сплит» wins over an app: the caller asks the sets first. Pure.
 */
internal object SplitAppQuery {

    fun name(text: String): String? {
        val words = VoiceDictionary.words(text).dropWhile { it in LEAD_VERBS }
        val rest = when {
            words.isEmpty() -> null
            isSplitWord(words[0]) -> words.drop(1).let { if (it.firstOrNull() == WITH) it.drop(1) else it }
            words.take(3) == SPLIT_SCREEN_WITH || words.take(3) == SPLIT_SCREEN_NOUN_WITH -> words.drop(3)
            words[0] == ADD -> addForm(words.drop(1))
            else -> null
        }
        return rest?.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** After «добавь»: «в сплит <приложение>», or «<приложение>» followed by one of [ADD_TAILS]. */
    private fun addForm(words: List<String>): List<String>? {
        if (words.size > 2 && words[0] == INTO && isSplitWord(words[1])) return words.drop(2)
        return ADD_TAILS.firstOrNull { tail -> words.size > tail.size && words.takeLast(tail.size) == tail }
            ?.let { words.dropLast(it.size) }
    }

    /** «сплит» or a recognizer slip of one letter, as for the sets ([AppSetStore.editDistance]). */
    private fun isSplitWord(word: String): Boolean = AppSetStore.editDistance(word, SPLIT_WORD) <= 1

    private const val SPLIT_WORD = "сплит"
    private const val WITH = "с"
    private const val INTO = "в"
    private const val ADD = "добавь"
    private val LEAD_VERBS = setOf("включи", "открой", "запусти")
    private val SPLIT_SCREEN_WITH = listOf("раздели", "экран", WITH)
    private val SPLIT_SCREEN_NOUN_WITH = listOf("разделение", "экрана", WITH)
    private val ADD_TAILS = listOf(
        listOf(INTO, SPLIT_WORD), listOf(INTO, "разделение", "экрана"), listOf("к", "сплиту"),
    )
}
