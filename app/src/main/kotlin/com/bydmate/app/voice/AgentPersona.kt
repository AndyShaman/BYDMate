package com.bydmate.app.voice

import kotlin.random.Random

/** Voice-agent personality: short spoken phrases for terminal outcomes (random pick
 *  per announce) plus a runtime system-prompt block. Pure Kotlin, no Android deps. */
enum class AgentPersona(val id: String) {
    SNARKY("snarky"), NAVIGATOR("navigator"), ENGINEER("engineer");

    /** Maps a canonical terse spoken outcome ("Готово"/"Не получилось"/"Не понял"/"Ошибка")
     *  to a random persona phrase; any other string passes through unchanged. */
    fun spokenPhrase(
        spoken: String,
        random: Random = Random.Default,
        gender: TtsGender = TtsGender.MALE,
    ): String = pools(this)[spoken]?.random(random)?.forGender(gender) ?: spoken

    /** A random short filler ("Сейчас посмотрю.") spoken while a slow tool call is in flight,
     *  so the driver hears something before the real answer. */
    fun fillerPhrase(random: Random = Random.Default): String = FILLER_POOLS.getValue(this).random(random)

    /** Every phrase this persona can speak (outcome pools + fillers), for the online-voice
     *  TTS precache -- nothing here is picked at runtime by index, only by content. */
    fun phrases(gender: TtsGender = TtsGender.MALE): List<String> =
        pools(this).values.flatten().map { it.forGender(gender) } + FILLER_POOLS.getValue(this)

    companion object {
        fun fromId(id: String?): AgentPersona = entries.firstOrNull { it.id == id } ?: NAVIGATOR

        /** A spoken phrase with a feminine form; [f] defaults to [m] when no verb is gendered. */
        private class Phrase(val m: String, val f: String = m) {
            fun forGender(gender: TtsGender) = if (gender == TtsGender.FEMALE) f else m
        }
        private fun ph(vararg phrases: Any): List<Phrase> =
            phrases.map { if (it is Phrase) it else Phrase(it as String) }

        private val SNARKY_POOLS = mapOf(
            "Готово" to ph("Готово, блин.", Phrase("Сделал. Чудо, да?", "Сделала. Чудо, да?"), "Есть. Не благодари.",
                Phrase("Ну сделал, сделал.", "Ну сделала, сделала."), Phrase("Опа, сработало. Сам в шоке.", "Опа, сработало. Сама в шоке.")),
            "Не получилось" to ph("Хрен там. Не вышло.", "Облом. Машина упёрлась.",
                "Не вышло, зараза.", "Нет. Просто нет."),
            "Не понял" to ph("Чего?", "Это чё было?", "Ещё раз, по-человечески."),
            "Ошибка" to ph("Всё сломалось. Красота.", "Опять ошибка. Ну класс."),
        )
        private val NAVIGATOR_POOLS = mapOf(
            "Готово" to ph("Готово. Что-нибудь ещё?", "Сделано, командир.",
                Phrase("Выполнил. Хорошей дороги.", "Выполнила. Хорошей дороги."), "Готово."),
            "Не получилось" to ph("Не получилось. Давай попробуем иначе.",
                "Не вышло, попробуем ещё раз."),
            "Не понял" to ph(Phrase("Не расслышал. Повтори, пожалуйста.", "Не расслышала. Повтори, пожалуйста."), Phrase("Не понял, скажи иначе.", "Не поняла, скажи иначе.")),
            "Ошибка" to ph("Возникла ошибка. Разберёмся.", "Что-то пошло не так."),
        )
        private val ENGINEER_POOLS = mapOf(
            "Готово" to ph("Есть.", "Выполнено.", "Принято. Сделано.", "Готово."),
            "Не получилось" to ph("Отказ. Система не ответила.", "Не выполнено."),
            "Не понял" to ph(Phrase("Не разобрал. Повтори.", "Не разобрала. Повтори."), "Вводная неясна. Повтори."),
            "Ошибка" to ph("Сбой. Подробности в журнале.", "Ошибка системы."),
        )
        private fun pools(p: AgentPersona): Map<String, List<Phrase>> = when (p) {
            SNARKY -> SNARKY_POOLS
            NAVIGATOR -> NAVIGATOR_POOLS
            ENGINEER -> ENGINEER_POOLS
        }

        private val FILLER_POOLS = mapOf(
            NAVIGATOR to listOf("Сейчас посмотрю.", "Секунду, проверяю.", "Минутку.", "Уже смотрю."),
            SNARKY to listOf("Сейчас гляну.", "Погоди, смотрю.", "Секунду, не торопи.", "Минуту, ищу."),
            ENGINEER to listOf("Запрос принят.", "Проверяю.", "Обрабатываю.", "Секунду."),
        )
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
