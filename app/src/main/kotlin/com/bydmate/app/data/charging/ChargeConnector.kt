package com.bydmate.app.data.charging

/** The car's charging connector, picked in Settings: the voice agent lists stations that have
 *  it. [key] is the persisted settings value, [label] the name drivers and station maps use,
 *  [standards] the connector codes of the BETA station map it matches. */
enum class ChargeConnector(val key: String, val label: String, private val standards: Set<String>) {
    GBT("gbt", "GB/T", setOf("GBT_DC", "GBT_AC")),
    CCS2("ccs2", "CCS2", setOf("CCS2")),
    TYPE2("type2", "Type2", setOf("Type2")),
    CHADEMO("chademo", "CHAdeMO", setOf("CHAdeMO"));

    fun matches(standard: String): Boolean = standard in standards

    companion object {
        /** Unknown or absent value maps to GB/T, the connector of Chinese-market BYD cars. */
        fun fromKey(key: String?): ChargeConnector = entries.firstOrNull { it.key == key } ?: GBT

        /** A tool argument: key or label in any case; null when it names no known connector. */
        fun parse(value: String?): ChargeConnector? {
            val v = value?.trim().orEmpty()
            return entries.firstOrNull { it.key.equals(v, ignoreCase = true) || it.label.equals(v, ignoreCase = true) }
        }
    }
}
