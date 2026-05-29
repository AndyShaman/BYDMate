package com.bydmate.app.data.nativestack

data class FidEntry(
    val field: String,      // matches DiParsData property name
    val device: Int,
    val fid: Int,
    val transact: Int,      // 5 = getInt, 7 = getFloat
    val decoder: Decoder,
    val scale: Double = 1.0, // used only when decoder == INT_SCALED
)

/**
 * Static map: each DiParsData property → autoservice address + decoder.
 * Generated from scripts/native-stack/fid-candidates.yaml status=validated entries.
 * Update via the validation suite, not by hand.
 *
 * Validated yaml params excluded here (no DiParsData field — Phase 2 reads):
 *   ACDefrostFront, ACWindMode, ACCtrlMode,
 *   SeatHeatD, SeatVentD, SeatHeatP, SeatVentP,
 *   LightSide, LightHigh
 */
object FidMap {
    val entries: List<FidEntry> = listOf(
        // Core energy + drive
        FidEntry("soc",                  1014, 1246777400,   7, Decoder.FLOAT_PERCENT),
        FidEntry("speed",                1013, -1807745016,  7, Decoder.FLOAT_KW),
        FidEntry("mileage",              1014, 1246765072,   5, Decoder.INT_SCALED,  scale = 0.1),
        FidEntry("power",                1012, 339738656,    5, Decoder.INT_RAW),
        FidEntry("totalElecConsumption", 1014, 1032871984,   7, Decoder.FLOAT_KWH),
        FidEntry("voltage12v",           1001, 1128267816,   7, Decoder.FLOAT_VOLT),
        // Battery
        FidEntry("maxCellVoltage",       1014, 1147142192,   5, Decoder.INT_SCALED,  scale = 0.001),
        FidEntry("minCellVoltage",       1014, 1147142160,   5, Decoder.INT_SCALED,  scale = 0.001),
        // Gear + state
        FidEntry("gear",                 1011, 555745336,    5, Decoder.INT_ENUM),
        FidEntry("chargeGunState",       1009, 876609586,    5, Decoder.INT_ENUM),
        // Climate
        FidEntry("acStatus",             1000, 1077936144,   5, Decoder.INT_ENUM),
        FidEntry("acTemp",               1000, 1077936168,   5, Decoder.INT_TEMP_C),
        FidEntry("fanLevel",             1000, 1077936156,   5, Decoder.INT_RAW),
        FidEntry("acCirc",               1000, 1077936148,   5, Decoder.INT_ENUM),
        FidEntry("insideTemp",           1000, 1031798832,   5, Decoder.INT_TEMP_C),
        FidEntry("exteriorTemp",         1000, 1077936184,   5, Decoder.INT_TEMP_C),
        // Body
        FidEntry("hood",                 1001, 692060188,    5, Decoder.INT_ENUM),
        // Safety
        FidEntry("seatbeltFL",           1007, 692060184,    5, Decoder.INT_ENUM),
        // Tires
        FidEntry("tirePressFL",          1016, -1728052956,  5, Decoder.INT_KPA),
        FidEntry("tirePressFR",          1016, -1728052952,  5, Decoder.INT_KPA),
        FidEntry("tirePressRL",          1016, -1728052948,  5, Decoder.INT_KPA),
        FidEntry("tirePressRR",          1016, -1728052944,  5, Decoder.INT_KPA),
        // Lights
        FidEntry("lightLow",             1004, 950009866,    5, Decoder.INT_ENUM),
        FidEntry("drl",                  1004, 1231040528,   5, Decoder.INT_ENUM),
        // Graduated from yaml status=candidate without formal D+ snap validation.
        // Smoke on real DiLink will surface sentinel returns or wrong values.
        // If a field shows sentinel/garbage in UI after upgrade — pull the fid from FidMap.
        // Battery temps carry a -40 CAN offset (raw 51 → 11°C). Validated against
        // D+ 10/11/11 and the competitor config offsets {-40} on Leopard 3 2026-05-29.
        FidEntry("maxBatTemp",           1014, 1148190752,   5, Decoder.INT_TEMP_C_OFS40),
        FidEntry("minBatTemp",           1014, 1148190736,   5, Decoder.INT_TEMP_C_OFS40),
        FidEntry("powerState",           1023, 315621408,    5, Decoder.INT_ENUM),
        FidEntry("doorFL",               1001, 692060168,    5, Decoder.INT_ENUM),
        FidEntry("doorFR",               1001, 692060170,    5, Decoder.INT_ENUM),
        FidEntry("doorRL",               1001, 692060172,    5, Decoder.INT_ENUM),
        FidEntry("doorRR",               1001, 692060174,    5, Decoder.INT_ENUM),
        FidEntry("windowFL",             1001, 947912728,    5, Decoder.INT_PERCENT),
        FidEntry("windowFR",             1001, 1267728400,   5, Decoder.INT_PERCENT),
        FidEntry("windowRL",             1001, 947912736,    5, Decoder.INT_PERCENT),
        FidEntry("windowRR",             1001, 947912752,    5, Decoder.INT_PERCENT),
        FidEntry("trunk",                1001, 1074790416,   5, Decoder.INT_ENUM),
        FidEntry("lockFL",               1032, 1081081864,   5, Decoder.INT_ENUM),
        FidEntry("driveMode",            1006, 555745294,    5, Decoder.INT_ENUM),
        FidEntry("workMode",             1006, 874512420,    5, Decoder.INT_ENUM),
    )
}
