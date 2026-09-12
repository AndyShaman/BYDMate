package com.bydmate.app.data.nativestack

data class FidEntry(
    val field: String,      // matches DiParsData property name
    val device: Int,
    val fid: Int,
    val transact: Int,      // 5 = getInt, 7 = getFloat
    val decoder: Decoder,
    val scale: Double = 1.0, // used only when decoder == INT_SCALED
    /**
     * Name of this address in the firmware's own fid catalog
     * (`Prefix.FIELD` as printed by the daemon's fid dump, e.g.
     * `Statistic.STATISTIC_ELEC_PERCENTAGE`). Derived by reverse lookup of [fid]
     * in the Leopard 3 catalog — see scripts/native-stack/fid-symbols.py.
     * The BYD SDK computes fid values per platform, so on another head unit the
     * same symbol carries a different number; [FidResolver] uses it to find that
     * number. Null = the constant has no catalog symbol on Leopard 3 and can only
     * ever be used as-is.
     */
    val symbol: String? = null,
)

/**
 * Static map: each DiParsData property → autoservice address + decoder.
 * Addresses came from scripts/native-stack/fid-candidates.yaml status=validated
 * entries (validation suite); the `symbol` column is filled by reverse lookup in
 * the Leopard 3 catalog with scripts/native-stack/fid-symbols.py. Edited by hand —
 * there is no code generator for this file.
 *
 * [entries] is the polling set read on every tick (order and size are part of the
 * daemon batch contract, cap 128). [extra] holds READ addresses used outside the
 * poll loop; both share [FidEntry] so one resolver covers every read the app does.
 */
object FidMap {
    val entries: List<FidEntry> = listOf(
        // Core energy + drive
        FidEntry("soc",                  1014, 1246777400,   7, Decoder.FLOAT_PERCENT, symbol = "Statistic.STATISTIC_ELEC_PERCENTAGE"),
        FidEntry("speed",                1013, -1807745016,  7, Decoder.FLOAT_KW, symbol = "Speed.SPEED_AUTO_SPEED"),
        FidEntry("mileage",              1014, 1246765072,   5, Decoder.INT_SCALED,  scale = 0.1, symbol = "Statistic.STATISTIC_TOTAL_MILEAGE"),
        FidEntry("power",                1012, 339738656,    5, Decoder.INT_RAW, symbol = "Engine.ENGINE_POWER"),
        FidEntry("totalElecConsumption", 1014, 1032871984,   7, Decoder.FLOAT_KWH, symbol = "Statistic.STATISTIC_TOTAL_ELEC_CONSUMPTION"),
        FidEntry("voltage12v",           1001, 1128267816,   7, Decoder.FLOAT_VOLT, symbol = "Ota.OTA_BATTERY_POWER_VOLTAGE"),
        // Battery
        FidEntry("maxCellVoltage",       1014, 1147142192,   5, Decoder.INT_SCALED,  scale = 0.001, symbol = "Statistic.STATISTIC_HIGHEST_BATTERY_VOLTAGE"),
        FidEntry("minCellVoltage",       1014, 1147142160,   5, Decoder.INT_SCALED,  scale = 0.001, symbol = "Statistic.STATISTIC_LOWEST_BATTERY_VOLTAGE"),
        // Gear + state
        FidEntry("gear",                 1011, 555745336,    5, Decoder.INT_ENUM, symbol = "Gearbox.GEARBOX_AUTO_MODE_TYPE"),
        FidEntry("chargeGunState",       1009, 876609586,    5, Decoder.INT_ENUM, symbol = "Charging.CHARGING_GUN_CONNECT_STATE"),
        FidEntry("bmsState",             1009, 876609560,    5, Decoder.INT_ENUM, symbol = "Charging.CHARGING_BATTERRY_DEVICE_STATE"),  // 1=CHARGING, 2=FINISH, 13=PAUSE
        // Climate
        FidEntry("acStatus",             1000, 1077936144,   5, Decoder.INT_ENUM, symbol = "Ac.AC_POWER_STATE"),
        FidEntry("acTemp",               1000, 1077936168,   5, Decoder.INT_TEMP_C, symbol = "Ac.AC_TEMP_MAIN"),
        FidEntry("fanLevel",             1000, 1077936156,   5, Decoder.INT_RAW, symbol = "Ac.AC_WIND_LEVEL"),
        FidEntry("acCirc",               1000, 1077936148,   5, Decoder.INT_ENUM, symbol = "Ac.AC_CYCLE_MODE"),
        FidEntry("insideTemp",           1000, 1031798832,   5, Decoder.INT_TEMP_C, symbol = "Ac.AC_TEMP_INSIDE"),
        FidEntry("exteriorTemp",         1000, 1077936184,   5, Decoder.INT_TEMP_C, symbol = "Ac.AC_TEMP_OUT"),
        // Body
        FidEntry("hood",                 1001, 692060188,    5, Decoder.INT_ENUM, symbol = "Bodywork.BODYWORK_HOOD"),
        // Safety
        FidEntry("seatbeltFL",           1007, 692060184,    5, Decoder.INT_ENUM, symbol = "Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE"),
        // Tires
        FidEntry("tirePressFL",          1016, -1728052956,  5, Decoder.INT_KPA, symbol = "Tyre.TYRE_PRESSURE_VALUE_LEFT_FRONT"),
        FidEntry("tirePressFR",          1016, -1728052952,  5, Decoder.INT_KPA, symbol = "Tyre.TYRE_PRESSURE_VALUE_RIGHT_FRONT"),
        FidEntry("tirePressRL",          1016, -1728052948,  5, Decoder.INT_KPA, symbol = "Tyre.TYRE_PRESSURE_VALUE_LEFT_REAR"),
        FidEntry("tirePressRR",          1016, -1728052944,  5, Decoder.INT_KPA, symbol = "Tyre.TYRE_PRESSURE_VALUE_RIGHT_REAR"),
        // Lights
        FidEntry("lightLow",             1004, 950009866,    5, Decoder.INT_ENUM, symbol = "Light.LIGHT_LOW_BEAM_LIGHT"),
        FidEntry("drl",                  1004, 1231040528,   5, Decoder.INT_ENUM, symbol = "Light.LIGHT_CMD_DAY_RUNNING_LIGHT_STATE"),
        // Live Leopard 3 2026-07-31: 1=off, 2=left, 4=right, 6=hazard (mask stays put while blinking)
        FidEntry("turnSignal",           1004, 950009900,    5, Decoder.INT_ENUM, symbol = "Light.LIGHT_TURN_SIGNAL_LIGHT"),
        // Graduated from yaml status=candidate without formal D+ snap validation.
        // Smoke on real DiLink will surface sentinel returns or wrong values.
        // If a field shows sentinel/garbage in UI after upgrade — pull the fid from FidMap.
        // Battery temps carry a -40 CAN offset (raw 51 → 11°C). Validated against
        // D+ 10/11/11 and the competitor config offsets {-40} on Leopard 3 2026-05-29.
        FidEntry("maxBatTemp",           1014, 1148190752,   5, Decoder.INT_TEMP_C_OFS40, symbol = "Statistic.STATISTIC_HIGHEST_BATTERY_TEMP"),
        FidEntry("minBatTemp",           1014, 1148190736,   5, Decoder.INT_TEMP_C_OFS40, symbol = "Statistic.STATISTIC_LOWEST_BATTERY_TEMP"),
        FidEntry("powerState",           1023, 315621408,    5, Decoder.INT_ENUM, symbol = "Setting.SET_VEHICLE_STATE"),
        FidEntry("doorFL",               1001, 692060168,    5, Decoder.INT_ENUM, symbol = "Bodywork.BODYWORK_LEFT_HAND_FRONT_DOOR"),
        FidEntry("doorFR",               1001, 692060170,    5, Decoder.INT_ENUM, symbol = "Bodywork.BODYWORK_RIGHT_HAND_FRONT_DOOR"),
        FidEntry("doorRL",               1001, 692060172,    5, Decoder.INT_ENUM, symbol = "Bodywork.BODYWORK_LEFT_HAND_REAR_DOOR"),
        FidEntry("doorRR",               1001, 692060174,    5, Decoder.INT_ENUM, symbol = "Bodywork.BODYWORK_RIGHT_HAND_REAR_DOOR"),
        FidEntry("windowFL",             1001, 947912728,    5, Decoder.INT_PERCENT, symbol = "Bodywork.BODYWORK_WINDOW_LEFT_FRONT_PERCENT"),
        // Two L3 symbols carry 1267728400; the window one is the obvious pick (the other
        // is Setting.SET_CAR_R_VIEW, an unrelated HMI flag that happens to share the value).
        FidEntry("windowFR",             1001, 1267728400,   5, Decoder.INT_PERCENT, symbol = "Bodywork.BODYWORK_WINDOW_RIGHT_FRONT_PERCENT"),
        FidEntry("windowRL",             1001, 947912736,    5, Decoder.INT_PERCENT, symbol = "Bodywork.BODYWORK_WINDOW_LEFT_REAR_PERCENT"),
        FidEntry("windowRR",             1001, 947912752,    5, Decoder.INT_PERCENT, symbol = "Bodywork.BODYWORK_WINDOW_RIGHT_REAR_PERCENT"),
        // DiLink 3.0 catalogs expose the RR window percent under a different fid
        // (BODYWORK_WINDOW_RIGHT_REAR_PERCENT 0x4b900018). Same semantics, read as a
        // fallback when the DiLink 5.0 fid above returns a link error (#79).
        FidEntry("windowRRGen3",         1001, 1267728408,   5, Decoder.INT_PERCENT),
        // Percent fid (live Leopard 3 2026-07-30): 0=closed, 7=vent detent, 50=half, 100=open
        FidEntry("sunroof",              1001, 1101004808,   5, Decoder.INT_PERCENT, symbol = "Bodywork.BODYWORK_MOON_ROOF_OPEN_PERCENT"),
        FidEntry("trunk",                1001, 1074790416,   5, Decoder.INT_ENUM, symbol = "Bodywork.BODYWORK_BACKDOOR_CURRENT_POSITION"),
        FidEntry("lockFL",               1032, 1081081864,   5, Decoder.INT_ENUM, symbol = "Ota.OTA_LF_DOOR_LOCK"),
        FidEntry("driveMode",            1006, 555745294,    5, Decoder.INT_ENUM, symbol = "Energy.ENERGY_OPERATION_MODE"),
        // Ambiguous on L3: ENERGY_MODE_INSTRUMENT and its _44 twin hold the same value.
        // The plain name is the one the SDK getter uses.
        FidEntry("workMode",             1006, 874512420,    5, Decoder.INT_ENUM, symbol = "Energy.ENERGY_MODE_INSTRUMENT"),
        // Voice agent Phase 0 (2026-07-02): validated yaml entries, previously unwired.
        // Climate extras
        FidEntry("acDefrostFront",       1000, 1077936150,   5, Decoder.INT_ENUM, symbol = "Ac.AC_DEFROST_FRONT_STATE"),
        FidEntry("acWindMode",           1000, 1077936152,   5, Decoder.INT_ENUM, symbol = "Ac.AC_WIND_MODE"),
        FidEntry("acCtrlMode",           1000, 1077936146,   5, Decoder.INT_ENUM, symbol = "Ac.AC_CTRL_MODE"),
        // Seat heat/vent levels (READ keys, dev=1000; 0=off, 1..5=level)
        FidEntry("seatHeatDriver",       1000, 702545948,    5, Decoder.INT_RAW, symbol = "Ac.AC_MAIN_DRIVE_SEAT_HEATING_LEVEL"),
        FidEntry("seatVentDriver",       1000, 702545944,    5, Decoder.INT_RAW, symbol = "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_LEVEL"),
        FidEntry("seatHeatPassenger",    1000, 711983132,    5, Decoder.INT_RAW, symbol = "Ac.AC_PASSENGER_SEAT_HEATING_LEVEL"),
        FidEntry("seatVentPassenger",    1000, 711983128,    5, Decoder.INT_RAW, symbol = "Ac.AC_PASSENGER_SEAT_VENTILATING_LEVEL"),
        // Lights
        FidEntry("lightSide",            1004, 950009864,    5, Decoder.INT_ENUM, symbol = "Light.LIGHT_SIDE_LIGHT"),
        FidEntry("lightHigh",            1004, 950009868,    5, Decoder.INT_ENUM, symbol = "Light.LIGHT_HIGH_BEAM_LIGHT"),
        // Sensors wave (validated live on Leopard 3, 2026-07-07;
        // probe: .research/probe/sensors-probe-2026-07-07.sh)
        FidEntry("seatbeltFR",       1042, 315621439,   5, Decoder.INT_ENUM, symbol = "Safety.SAFETY_BELT_COMMAND_AREA_DEPUTY"),  // 0=unbuckled, 1=buckled
        FidEntry("occupancyFL",      1042, 824180800,   5, Decoder.INT_ENUM, symbol = "Safety.SAFETY_BELT_PASSENGER_COMMAND_FRONT_ROW_SEAT_LEFT"),  // 1=free, 2=occupied (NOT 0/1!)
        FidEntry("occupancyFR",      1042, 824180758,   5, Decoder.INT_ENUM, symbol = "Safety.SAFETY_BELT_PASSENGER_COMMAND_FRONT_ROW_SEAT_RIGHT"),
        FidEntry("occupancyRL",      1042, 824180760,   5, Decoder.INT_ENUM, symbol = "Safety.SAFETY_BELT_PASSENGER_COMMAND_SECOND_ROW_SEAT_LEFT"),
        FidEntry("occupancyRM",      1042, 824180762,   5, Decoder.INT_ENUM, symbol = "Safety.SAFETY_BELT_PASSENGER_COMMAND_SECOND_ROW_SEAT_MID"),
        FidEntry("occupancyRR",      1042, 824180764,   5, Decoder.INT_ENUM, symbol = "Safety.SAFETY_BELT_PASSENGER_COMMAND_SECOND_ROW_SEAT_RIGHT"),
        // Ambiguous on L3 (Charging.CHARGING_IG4_RELAY_STATUS shares the value); the entry
        // reads dev=1043 = SENSOR, so SENSOR_LIGHT is the symbol that belongs to it.
        FidEntry("lightLevel",       1043, 315621396,   5, Decoder.INT_RAW, symbol = "Sensor.SENSOR_LIGHT"),   // 1=dark .. 5=bright
        FidEntry("keyBatteryStatus", 1014, 402653200,   5, Decoder.INT_ENUM, symbol = "Statistic.STATISTIC_KEY_BATTERY_LEVEL"),  // 0=ok, non-zero=low
        FidEntry("wiperRelay",       1046, 1336934438,  5, Decoder.INT_ENUM, symbol = "Wiper.WIPER_RELAY_STATE"),  // 0=idle, non-zero=wiping
        FidEntry("autoWipers",       1046, 321912862,   5, Decoder.INT_ENUM, symbol = "Setting.SET_AUTO_RAIN_WIPER_SWITCH"),  // 1=rain-sensing wipe enabled
        // Tech panel wave (2026-09-05). Raw ints; the range checks live in
        // NativeParsReader.assembleSnapshot (motor/inverter temps exceed the -50..80
        // envelope INT_TEMP_C enforces). Battery temp extremes and the AC on/off flag
        // are already mapped above as maxBatTemp/minBatTemp/acStatus.
        FidEntry("insulationKohm",     1039, 1134559256,  5, Decoder.INT_RAW, symbol = "Gb.GB_BMC_INSULATION_VALUE"),   // kΩ between HV pack and body
        FidEntry("motorTempFront",     1039, 1154482192,  5, Decoder.INT_RAW, symbol = "Gb.GB_FRONT_MOTOR_TEMP"),
        FidEntry("motorTempRear",      1039, 1155530768,  5, Decoder.INT_RAW, symbol = "Gb.GB_TREAR_MOTOR_TEMP"),
        FidEntry("inverterTempFront",  1039, 1154482184,  5, Decoder.INT_RAW, symbol = "Gb.GB_FRONT_MOTOR_IPM_TEMP"),
        FidEntry("inverterTempRear",   1039, 1155530760,  5, Decoder.INT_RAW, symbol = "Gb.GB_REAR_MOTOR_IPM_TEMP"),
        FidEntry("hvVoltage",          1009, 1145045000,  5, Decoder.INT_RAW, symbol = "Charging.CHARGING_CHARGE_BATTERY_VOLT"),
        FidEntry("hvCurrent",          1009, 1145045016,  7, Decoder.FLOAT_AMP, symbol = "Charging.CHARGING_CHARGE_CURRENT"), // negative = charging (#153)
        // Unproven on the car (0.0 while parked) — logged only, not surfaced in UI.
        FidEntry("motorCurrentFront",  1009, 1186988040,  7, Decoder.FLOAT_AMP, symbol = "Charging.CHARGING_DRIVER_MOTOR_CURRENT"),
        FidEntry("motorCurrentRear",   1009, 1186988056,  7, Decoder.FLOAT_AMP, symbol = "Charging.CHARGING_REAR_DRIVER_MOTOR_CURRENT"),
        FidEntry("bmsMaxChargeKw",     1014, 877658136,   5, Decoder.INT_SCALED, scale = 0.1, symbol = "Statistic.STATISTIC_MAX_CHARGE_POWER_ALLOW"),
        FidEntry("bmsMaxDischargeKw",  1014, 1145045048,  5, Decoder.INT_RAW, symbol = "Statistic.STATISTIC_BATTERY_AVAILABLE_POWER"),
        FidEntry("motorRpmFront",      1012, 1141899272,  5, Decoder.INT_RAW, symbol = "Engine.ENGINE_FRONT_MOTOR_SPEED"),
        FidEntry("motorRpmRear",       1012, 621805576,   5, Decoder.INT_RAW, symbol = "Engine.ENGINE_REAR_MOTOR_SPEED"),
        FidEntry("compressorW",        1000, 1031798840,  5, Decoder.INT_RAW, symbol = "Power.POWER_COMPRESSOR_CONSUME_POWER"),
        FidEntry("tyreTempFL",         1007, 1246797848,  5, Decoder.INT_RAW, symbol = "Instrument.INSTRUMENT_2IN1_LF_TYRE_TEMPERATURE"),
        FidEntry("tyreTempFR",         1007, 1246797860,  5, Decoder.INT_RAW, symbol = "Instrument.INSTRUMENT_2IN1_RF_TYRE_TEMPERATURE"),
        FidEntry("tyreTempRL",         1007, 1246797872,  5, Decoder.INT_RAW, symbol = "Instrument.INSTRUMENT_2IN1_LB_TYRE_TEMPERATURE"),
        FidEntry("tyreTempRR",         1007, 1246797884,  5, Decoder.INT_RAW, symbol = "Instrument.INSTRUMENT_2IN1_RB_TYRE_TEMPERATURE"),
        FidEntry("pedalAccel",         1013, 874512392,   5, Decoder.INT_RAW, symbol = "Speed.SPEED_ACCELERATOR_S"),
        FidEntry("pedalBrake",         1013, 874512400,   5, Decoder.INT_RAW, symbol = "Speed.SPEED_BRAKE_S"),
    )

    /**
     * READ addresses used outside the poll loop: the one-shot snapshots in
     * AutoserviceClient, the observe-only fid subscriptions, and the seat
     * diagnostics block of the dump. They never enter the daemon batch the
     * poll loop sends, but they are resolved by the same [FidResolver].
     */
    val extra: List<FidEntry> = listOf(
        // AutoserviceClient snapshots (were FidRegistry constants)
        FidEntry("soh",                 1014, 1145045032,   5, Decoder.INT_PERCENT, symbol = "Statistic.STATISTIC_BATTERY_HEALTHY_INDEX"),
        FidEntry("lifetimeAvgPhm",      1014, 1246761008,   7, Decoder.FLOAT_KWH, symbol = "Statistic.STATISTIC_TOTAL_ELEC_CON_PHM"),
        FidEntry("chargingType",        1009, 876609592,    5, Decoder.INT_ENUM, symbol = "Charging.CHARGING_TYPE"),
        // Stale constant (returns a sentinel on Leopard 3): the canonical address of this
        // symbol is the one hvVoltage already reads. Left as the fallback on purpose — the
        // resolver replaces it wherever the catalog knows the symbol.
        FidEntry("chargeBatteryVolt",   1009, -1442840491,  5, Decoder.INT_RAW, symbol = "Charging.CHARGING_CHARGE_BATTERY_VOLT"),
        FidEntry("batteryType",         1009, -1728053169,  5, Decoder.INT_ENUM, symbol = "Charging.CHARGING_BATTERY_TYPE"),
        FidEntry("chargingCapacity",    1009, 666894360,    7, Decoder.FLOAT_KWH, symbol = "Charging.CHARGING_CAPACITY"),
        // Observe-only subscriptions (FidSubscriptionManager). The listener API takes the
        // fid only; dev=1038 (ADAS) is the catalog device these symbols belong to.
        FidEntry("bsdLeft",             1038, 1098907664,   5, Decoder.INT_ENUM, symbol = "Adas.ADAS_RIGHT_RADAR_LCA_WARNINGLEFT"),
        FidEntry("bsdRight",            1038, 1098907666,   5, Decoder.INT_ENUM, symbol = "Adas.ADAS_RIGHT_RADAR_LCA_WARNINGRIGHT"),
        // Seat diagnostics block of the dump (SeatsDiagnostics). Read-only candidates;
        // the levels the poll loop already reads stay in [entries] and are shared.
        FidEntry("seatVentStatusDriver", 1000, 702545928,   5, Decoder.INT_RAW, symbol = "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_STATUS"),
        FidEntry("seatHeatStatusDriver", 1000, 702545932,   5, Decoder.INT_RAW, symbol = "Ac.AC_MAIN_DRIVE_SEAT_HEATING_STATUS"),
        FidEntry("seatConfigVentLf",     1000, 715132952,   5, Decoder.INT_RAW, symbol = "Ac.AC_LF_SEAT_VENTILATION_CONFIG"),
        FidEntry("seatConfigHeatLf",     1000, 715132955,   5, Decoder.INT_RAW, symbol = "Ac.AC_LF_SEAT_HEATING_CONFIG"),
        // Ambiguous on L3 (SET_HAS_SEAT_HEATING_AND_VENTILATING shares the value); the
        // per-function symbol is the one the trim flag belongs to.
        FidEntry("seatHasDriverVent",    1023, -811597816,  5, Decoder.INT_RAW, symbol = "Setting.SET_HAS_DRIVER_SEAT_VENTILATING"),
        FidEntry("seatHasDriverHeat",    1023, -811597813,  5, Decoder.INT_RAW, symbol = "Setting.SET_HAS_DRIVER_SEAT_HEATING"),
        FidEntry("seatCandPassengerVentStatus", 1000, 711983112, 5, Decoder.INT_RAW, symbol = "Ac.AC_PASSENGER_SEAT_VENTILATING_STATUS"),
        FidEntry("seatCandPassengerHeatStatus", 1000, 711983116, 5, Decoder.INT_RAW, symbol = "Ac.AC_PASSENGER_SEAT_HEATING_STATUS"),
        FidEntry("seatCand3ceVentStatusDriver", 1000, 1021313032, 5, Decoder.INT_RAW, symbol = "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_STATUS_3CE_hal_only"),
        FidEntry("seatCand3ceHeatStatusDriver", 1000, 1021313034, 5, Decoder.INT_RAW, symbol = "Ac.AC_MAIN_DRIVE_SEAT_HEATING_STATUS_3CE_hal_only"),
        FidEntry("seatCand3ceVentLevelDriver",  1000, 1021313044, 5, Decoder.INT_RAW, symbol = "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_LEVEL_3CE_hal_only"),
        FidEntry("seatCand3ceHeatLevelDriver",  1000, 1021313048, 5, Decoder.INT_RAW, symbol = "Ac.AC_MAIN_DRIVE_SEAT_HEATING_LEVEL_3CE_hal_only"),
        FidEntry("seatCandLrseVentStatusRl",    1000, 412180522,  5, Decoder.INT_RAW, symbol = "Ac.AC_LRSE_REAR_LEFT_SEAT_VENTILATING_STATUS_SET_hal_only"),
        FidEntry("seatCandLrseHeatStatusRl",    1000, 412180526,  5, Decoder.INT_RAW, symbol = "Ac.AC_LRSE_REAR_LEFT_SEAT_HEATING_STATUS_SET_hal_only"),
        FidEntry("seatCandLrseVentStatusRr",    1000, 412180528,  5, Decoder.INT_RAW, symbol = "Ac.AC_LRSE_REAR_RIGHT_SEAT_VENTILATING_STATUS_SET_hal_only"),
        FidEntry("seatCandLrseHeatStatusRr",    1000, 412180532,  5, Decoder.INT_RAW, symbol = "Ac.AC_LRSE_REAR_RIGHT_SEAT_HEATING_STATUS_SET_hal_only"),
        FidEntry("seatCandLrseLevel1",          1000, 412180536,  5, Decoder.INT_RAW, symbol = "Ac.AC_LRSE_REAR_LEFT_SEAT_VENTILATING_LEVEL_SET_hal_only"),
        FidEntry("seatCandLrseLevel2",          1000, 412180540,  5, Decoder.INT_RAW, symbol = "Ac.AC_LRSE_REAR_LEFT_SEAT_HEATING_LEVEL_SET_hal_only"),
        FidEntry("seatCandLrseLevel3",          1000, 412180544,  5, Decoder.INT_RAW, symbol = "Ac.AC_LRSE_REAR_RIGHT_SEAT_VENTILATING_LEVEL_SET_hal_only"),
        FidEntry("seatCandLrseLevel4",          1000, 412180548,  5, Decoder.INT_RAW, symbol = "Ac.AC_LRSE_REAR_RIGHT_SEAT_HEATING_LEVEL_SET_hal_only"),
    )

    /** Every READ address the app knows, polled or not. */
    val all: List<FidEntry> = entries + extra

    val byField: Map<String, FidEntry> = all.associateBy { it.field }
}
