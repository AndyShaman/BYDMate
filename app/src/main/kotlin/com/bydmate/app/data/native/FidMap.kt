package com.bydmate.app.data.native

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
        // TODO: remaining DiParsData fields added as fid-candidates.yaml entries
        // graduate to `status: validated` (Phase 1a continues):
        // maxBatTemp, minBatTemp, avgBatTemp, chargingStatus, batteryCapacityKwh,
        // powerState, driveMode, workMode, doorFL, doorFR, doorRL, doorRR,
        // windowFL, windowFR, windowRL, windowRR, sunroof, trunk, lockFL,
        // autoPark, rain (wiper sensitivity — not a live sensor)
    )

    val byField: Map<String, FidEntry> = entries.associateBy { it.field }
}
