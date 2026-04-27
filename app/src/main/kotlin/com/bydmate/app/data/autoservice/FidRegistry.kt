package com.bydmate.app.data.autoservice

/**
 * autoservice Binder addresses validated on Leopard 3 2026-04-25.
 *
 * Source: BydDiagnoseToolV2.apk decompile + adb shell service call validation.
 * See .research/leopard3-pulled/AUTOSERVICE-CATALOG-2026-04-25.md for the
 * full catalog (47 device types, sentinel map, decoders).
 *
 * IMPORTANT: read-only constants. NEVER add a setInt fid here — the
 * regex barrier in AutoserviceClientImpl will reject any tx=6 attempt.
 */
object FidRegistry {

    // transact codes — only read-side codes are listed
    const val TX_GET_INT = 5
    const val TX_GET_FLOAT = 7

    // device types — verified against AUTOSERVICE-CATALOG-2026-04-25.md
    const val DEV_STATISTIC = 1014   // BMS lifetime statistics
    const val DEV_CHARGING = 1009    // Charging gun + charger state
    const val DEV_BODYWORK = 1001    // 12V battery voltage on bodywork

    // === Statistic fids (dev=1014) ===
    /** BMS State of Health, percent (transact 5=int, raw 0..100). */
    const val FID_SOH = 1145045032
    /** Lifetime energy throughput, kWh (transact 7=float). */
    const val FID_LIFETIME_KWH = 1032871984
    /** Lifetime average consumption, kWh/100km (transact 7=float). */
    const val FID_LIFETIME_AVG_PHM = 1246761008
    /** Lifetime mileage, km ×10 (transact 5=int, divide by 10). */
    const val FID_LIFETIME_MILEAGE = 1246765072
    /** Current SOC, percent (transact 7=float). */
    const val FID_SOC = 1246777400

    // === Charging fids (dev=1009) ===
    /** Charging gun connect state: 1=NONE, 2=AC, 3=DC, 4=GB_DC. */
    const val FID_GUN_CONNECT_STATE = -1442840496
    /** Charging type after handshake: 1=DEFAULT/none, 2=AC, 4=GB_DC. */
    const val FID_CHARGING_TYPE = -1442840495
    /** Charger HV voltage, V (int). */
    const val FID_CHARGE_BATTERY_VOLT = -1442840491
    /** Battery type: 1=IRON/LFP, 2=NCM. */
    const val FID_BATTERY_TYPE = -1442840482
    /** Per-session charged energy, kWh (transact 7=float). Persists across DiLink
     *  power-cycle; resets on new charging session (gun reconnect or BMS reset). */
    const val FID_CHARGING_CAPACITY = 666894360
    /** BMS charging state: 1=CHARGING, 2=FINISH, 13=PAUSE (other values observed
     *  but not used). transact 5 (int). */
    const val FID_CHARGING_BMS_STATE = 876609560

    // === Bodywork fids (dev=1001) ===
    /** 12V auxiliary battery voltage, V (transact 7=float). */
    const val FID_OTA_BATTERY_POWER_VOLTAGE = 1128267816
}
