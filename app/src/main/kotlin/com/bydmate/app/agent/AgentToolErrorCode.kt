package com.bydmate.app.agent

import org.json.JSONObject

/**
 * Fixed code of a tool's error for the trace. The error texts are Russian, written for the model
 * and the driver, and some quote a name the driver said, so the text itself never goes into the
 * trace: the first rule whose fragment it contains names it, [OTHER] when none does.
 */
internal object AgentToolErrorCode {

    const val OTHER = "other"

    // Most specific first: "сервис поиска зарядок недоступен" must not read as "поиск недоступен".
    private val RULES = listOf(
        "нет данных с машины" to "no_vehicle_data",
        "неизвестный инструмент" to "unknown_tool",
        "запущенной автоматизацией" to "not_in_automation",
        "внутренняя ошибка" to "internal",
        "сервис карт недоступен" to "maps_unavailable",
        "поиск населённых пунктов недоступен" to "settlements_unavailable",
        "сервис поиска зарядок недоступен" to "chargers_unavailable",
        "поиск недоступен" to "search_unavailable",
        "погода недоступна" to "weather_unavailable",
        "город не найден" to "city_not_found",
        "нет GPS" to "no_gps",
        "не нашёл такую точку" to "place_not_found",
        "скорость неизвестна" to "speed_unknown",
        "только на паркинге" to "park_required",
        "часть действий не выполнилась" to "partial",
        "автоматизация не найдена" to "automation_not_found",
        "контакт не найден" to "contact_not_found",
        "подтверждение на экране" to "needs_confirmation",
        "нет доступа" to "no_permission",
        "Навигатор" to "navigator",
        "сплит не запущен" to "split_not_running",
        "разделение экрана" to "split_unavailable",
        "данных" to "not_enough_data",
        "уже существует" to "exists",
        "достигнут предел" to "limit",
        "некорректн" to "bad_args",
        "не указан" to "missing_args",
        "укажи" to "missing_args",
        "нужны обе" to "missing_args",
        "нужен либо" to "missing_args",
        "неизвестн" to "unknown_value",
        "не удалось" to "failed",
        "не получилось" to "failed",
        "не выполнено" to "failed",
    )

    /** The code of [result]'s `error`, null when the tool did not fail. */
    fun of(result: String): String? {
        val error = runCatching { JSONObject(result).optString("error") }.getOrNull()
        if (error.isNullOrEmpty()) return null
        return RULES.firstOrNull { (fragment, _) -> error.contains(fragment, ignoreCase = true) }?.second ?: OTHER
    }
}
