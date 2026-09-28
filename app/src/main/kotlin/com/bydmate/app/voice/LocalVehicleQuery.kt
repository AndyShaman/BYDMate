package com.bydmate.app.voice

import com.bydmate.app.data.remote.DiParsData

/**
 * Latency-critical read-only questions that can be answered from the live vehicle snapshot.
 *
 * Build 64014 proved the direct Local path (GigaAM -> local resolver -> car) feels immediate.
 * These narrow queries use the same principle instead of paying an OpenRouter round-trip for
 * data BYDMate already has. Anything ambiguous stays on the normal agent path.
 */
internal object LocalVehicleQuery {

    data class Reply(val kind: String, val text: String)

    fun answer(text: String, data: DiParsData?): Reply? {
        val q = text.lowercase().replace('ё', 'е').trim()
        return temperatureAnswer(q, data) ?: batteryAnswer(q, data)
    }

    private fun temperatureAnswer(q: String, data: DiParsData?): Reply? = when {
        isOutsideTemperature(q) -> Reply(
            "outside_temp",
            data?.exteriorTemp?.let { "Снаружи $it градусов." }
                ?: "Температура снаружи недоступна.",
        )
        isInsideTemperature(q) -> Reply(
            "inside_temp",
            data?.insideTemp?.let { "В салоне $it градусов." }
                ?: "Температура в салоне недоступна.",
        )
        isClimateSetpoint(q) -> Reply(
            "climate_setpoint",
            data?.acTemp?.let { "Климат установлен на $it градусов." }
                ?: "Температура климата недоступна.",
        )
        else -> null
    }

    private fun batteryAnswer(q: String, data: DiParsData?): Reply? =
        if (isBatteryCharge(q)) {
            Reply(
                "soc",
                data?.soc?.let { "Заряд $it%." }
                    ?: "Заряд батареи сейчас недоступен.",
            )
        } else {
            null
        }

    private fun isOutsideTemperature(q: String): Boolean =
        OUTSIDE_MARKERS.any(q::contains) &&
            (mentionsTemperature(q) || OUTSIDE_QUESTION_MARKERS.any(q::contains))

    private fun isInsideTemperature(q: String): Boolean =
        mentionsTemperature(q) && INSIDE_MARKERS.any(q::contains)

    private fun isClimateSetpoint(q: String): Boolean =
        mentionsTemperature(q) && CLIMATE_MARKERS.any(q::contains) && looksLikeRead(q)

    private fun isBatteryCharge(q: String): Boolean {
        val mentionsCharge = "заряд" in q || ("батаре" in q && "процент" in q)
        return mentionsCharge && looksLikeRead(q)
    }

    private fun mentionsTemperature(q: String): Boolean =
        "температур" in q || "градус" in q

    private fun looksLikeRead(q: String): Boolean =
        q == "заряд" || READ_PREFIXES.any(q::startsWith) || "уровень заряда" in q

    private val OUTSIDE_MARKERS = listOf("на улице", "на улиц", "снаруж", "за борт")
    // GigaAM can clip "какая температура на улице" to fragments such as "которая на улице".
    // The outside marker keeps this narrow enough to answer locally without guessing other topics.
    private val OUTSIDE_QUESTION_MARKERS = listOf("какая", "какой", "которая", "сколько")
    private val INSIDE_MARKERS = listOf(
        "в салон", "внутри", "внутри салон", "внутри машин", "в машине", "в машин", "в автомоб",
    )
    private val CLIMATE_MARKERS = listOf("климат", "кондиционер")
    private val READ_PREFIXES = listOf(
        "заряд ",
        "температура",
        "сколько",
        "какой",
        "какая",
        "покажи",
        "скажи",
    )
}
