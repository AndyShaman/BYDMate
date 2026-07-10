package com.bydmate.app.voice

import kotlin.random.Random

/** Voice-agent personality: short spoken phrases for terminal outcomes (random pick
 *  per announce) plus a runtime system-prompt block. Pure Kotlin, no Android deps. */
enum class AgentPersona(val id: String) {
    SNARKY("snarky"), NAVIGATOR("navigator"), ENGINEER("engineer");

    /** Maps a canonical terse spoken outcome ("Готово"/"Не получилось"/"Не понял"/"Ошибка")
     *  to a random persona phrase; any other string passes through unchanged. */
    fun spokenPhrase(spoken: String, random: Random = Random.Default): String =
        pools(this)[spoken]?.random(random) ?: spoken

    companion object {
        fun fromId(id: String?): AgentPersona = entries.firstOrNull { it.id == id } ?: NAVIGATOR

        private val SNARKY_POOLS = mapOf(
            "Готово" to listOf("Готово, блин.", "Сделал. Чудо, да?", "Есть. Не благодари.",
                "Ну сделал, сделал.", "Опа, сработало. Сам в шоке."),
            "Не получилось" to listOf("Хрен там. Не вышло.", "Облом. Машина упёрлась.",
                "Не вышло, зараза.", "Нет. Просто нет."),
            "Не понял" to listOf("Чего?", "Это чё было?", "Ещё раз, по-человечески."),
            "Ошибка" to listOf("Всё сломалось. Красота.", "Опять ошибка. Ну класс."),
        )
        private val NAVIGATOR_POOLS = mapOf(
            "Готово" to listOf("Готово. Что-нибудь ещё?", "Сделано, командир.",
                "Выполнил. Хорошей дороги.", "Готово."),
            "Не получилось" to listOf("Не получилось. Давай попробуем иначе.",
                "Не вышло, попробуем ещё раз."),
            "Не понял" to listOf("Не расслышал. Повтори, пожалуйста.", "Не понял, скажи иначе."),
            "Ошибка" to listOf("Возникла ошибка. Разберёмся.", "Что-то пошло не так."),
        )
        private val ENGINEER_POOLS = mapOf(
            "Готово" to listOf("Есть.", "Выполнено.", "Принято. Сделано.", "Готово."),
            "Не получилось" to listOf("Отказ. Система не ответила.", "Не выполнено."),
            "Не понял" to listOf("Не разобрал. Повтори.", "Вводная неясна. Повтори."),
            "Ошибка" to listOf("Сбой. Подробности в журнале.", "Ошибка системы."),
        )
        private fun pools(p: AgentPersona): Map<String, List<String>> = when (p) {
            SNARKY -> SNARKY_POOLS
            NAVIGATOR -> NAVIGATOR_POOLS
            ENGINEER -> ENGINEER_POOLS
        }
    }
}

/** User-configured agent identity: display/wake name + persona + gender. Read from
 *  SharedPreferences("voice") via a DI-provided lambda (see VoiceModule). */
data class AgentIdentity(
    val name: String,
    val persona: AgentPersona,
    val gender: TtsGender = TtsGender.MALE,
)

/** Runtime system-prompt addition. Appended AFTER the const SYSTEM_PROMPT, never edits it. */
object AgentPersonaPrompt {
    private val STYLE = mapOf(
        AgentPersona.SNARKY to "Ты едкий, саркастичный напарник. Ерничай, подкалывай, " +
            "отвечай коротко и с характером, допустимы грубоватые словечки (блин, чёрт, хрен). " +
            "Не оскорбляй водителя всерьёз. Факты и результаты команд передавай точно.",
        AgentPersona.NAVIGATOR to "Ты спокойный, вежливый штурман-напарник. Дружелюбный " +
            "профессионал: подскажешь, поддержишь, изредка уместно пошутишь.",
        AgentPersona.ENGINEER to "Ты невозмутимый бортинженер. Говоришь предельно кратко и " +
            "по-технически: только суть, цифры и статус. Сухой юмор в редких случаях.",
    )
    private const val SMALL_TALK = "Можешь поддержать короткий разговор на автомобильные и " +
        "близкие темы, рассказать шутку или анекдот, если попросят. Управление машиной всегда " +
        "важнее разговора: если во фразе есть команда, сначала выполни её. Стиль не отменяет " +
        "краткость: 1-2 коротких предложения."

    fun block(identity: AgentIdentity): String = buildString {
        append("\nХАРАКТЕР: ").append(STYLE.getValue(identity.persona))
        if (identity.name.isNotBlank()) {
            append("\nТебя зовут ").append(identity.name)
                .append(". Если спросят, как тебя зовут, назови это имя.")
        }
        append("\n").append(SMALL_TALK)
        append(
            when (identity.gender) {
                TtsGender.MALE -> "\nТы говоришь о себе в мужском роде (сделал, включил)."
                TtsGender.FEMALE -> "\nТы говоришь о себе в женском роде (сделала, включила)."
            }
        )
    }
}
