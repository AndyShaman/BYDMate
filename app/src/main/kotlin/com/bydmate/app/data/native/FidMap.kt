package com.bydmate.app.data.native

data class FidEntry(
    val field: String,      // matches DiParsData property name
    val device: Int,
    val fid: Int,
    val transact: Int,      // 5 = getInt, 7 = getFloat
    val decoder: Decoder,
)

/**
 * Static map: each DiParsData property → autoservice address + decoder.
 * Generated from scripts/native-stack/fid-candidates.yaml status=validated entries.
 * Update via the validation suite, not by hand.
 */
object FidMap {
    val entries: List<FidEntry> = listOf(
        FidEntry("soc",                  1014, 1246777400, 7, Decoder.FLOAT_PERCENT),
        FidEntry("mileage",              1014, 1246765072, 5, Decoder.INT_DIV10),
        FidEntry("power",                1012, 339738656,  5, Decoder.INT_RAW),
        FidEntry("chargeGunState",       1009, 876609586,  5, Decoder.INT_ENUM),
        FidEntry("chargingStatus",       1009, 876609560,  5, Decoder.INT_ENUM),
        FidEntry("totalElecConsumption", 1014, 1032871984, 7, Decoder.FLOAT_KWH),
        FidEntry("voltage12v",           1001, 1128267816, 7, Decoder.FLOAT_VOLT),
        // TODO: extend list with every remaining DiParsData field once
        // fid-candidates.yaml graduates it to `status: validated` (Task 19).
    )

    val byField: Map<String, FidEntry> = entries.associateBy { it.field }
}
