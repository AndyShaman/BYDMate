package com.bydmate.app.data.repository

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.trips.TripResetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// Russian numeric keyboards emit "71,8" — bare toDoubleOrNull() returns null
// on the comma and we fall back to the default. That made ABRP / Charges /
// SoH read the default 72.9 instead of the user's setting (issue #19).
internal fun String.parseNumericSetting(): Double? =
    replace(',', '.').trim().toDoubleOrNull()

@Singleton
open class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao,
    private val localePreferences: LocalePreferences,
) {
    companion object {
        const val KEY_BATTERY_CAPACITY = "battery_capacity_kwh"
        const val KEY_HOME_TARIFF = "home_tariff"
        const val KEY_DC_TARIFF = "dc_tariff"
        const val KEY_UNITS = "units" // "km" or "miles"
        const val KEY_CURRENCY = "currency" // "BYN", "RUB", "USD", "EUR", "CNY"
        const val KEY_TRIP_COST_TARIFF = "trip_cost_tariff" // "home", "dc", or numeric
        const val KEY_CONSUMPTION_GOOD = "consumption_good_threshold"
        const val KEY_CONSUMPTION_BAD = "consumption_bad_threshold"
        /** "auto" (historical/live blend, default) or "manual" (user temperature table). */
        const val KEY_RANGE_CALC_METHOD = "range_calc_method"
        const val KEY_MANUAL_RANGE_TABLE = "manual_range_table_json"
        const val KEY_LAST_KNOWN_SOC = "last_known_soc"
        const val KEY_LAST_SOC_TIMESTAMP = "last_soc_timestamp"
        const val KEY_LAST_ENERGYDATA_IMPORT_TS = "last_energydata_import_ts"
        const val KEY_SETUP_COMPLETED = "setup_completed"
        const val KEY_DEDUP_CLEANUP_DONE = "dedup_cleanup_done"
        const val KEY_IDLE_DRAIN_CLEANUP_DONE = "idle_drain_cleanup_done"
        const val KEY_CONSUMPTION_RECALC_DONE = "consumption_recalc_done"
        const val KEY_IDLE_DRAIN_V2_CLEANUP = "idle_drain_v2_cleanup"
        /** DriveMode trigger value "0" (old "NORMAL") rewritten to the real NORMAL code "3". */
        const val KEY_DRIVEMODE_RULE_MIGRATION = "drivemode_rule_migration_v2"
        const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        const val KEY_OPENROUTER_MODEL = "openrouter_model"
        /** Exa (api.exa.ai) BYOK for the web_search tool. Blank = openrouter:web_search server tool
         *  (billed from OpenRouter credits); non-blank = Exa. */
        const val KEY_EXA_API_KEY = "exa_api_key"
        // Wave J: multi-provider LLM connections for the voice agent
        const val KEY_ZAI_API_KEY = "zai_api_key"
        const val KEY_CUSTOM_NAME = "custom_llm_name"
        const val KEY_CUSTOM_BASE_URL = "custom_llm_base_url"
        const val KEY_CUSTOM_API_KEY = "custom_llm_api_key"
        const val KEY_CUSTOM_MODEL = "custom_llm_model"
        /** "openrouter" | "zai" | "custom"; blank = openrouter (pre-wave-J default). */
        const val KEY_AGENT_PRIMARY_CONN = "agent_primary_conn"
        /** Same values; blank = no fallback. */
        const val KEY_AGENT_FALLBACK_CONN = "agent_fallback_conn"
        const val KEY_ALICE_ENDPOINT = "alice_endpoint"
        const val KEY_ALICE_API_KEY = "alice_api_key"
        const val KEY_ALICE_ENABLED = "alice_enabled"
        /** Передавать живые данные DiPars в A Better Route Planner (Iternio Telemetry API). GPS передаётся только если включён KEY_ABRP_SEND_LOCATION. */
        const val KEY_ABRP_ENABLED = "abrp_telemetry_enabled"
        /** API-ключ приложения Iternio ([abetterrouteplanner.com/resources/api](https://abetterrouteplanner.com/resources/api)). */
        const val KEY_ABRP_API_KEY = "abrp_api_key"
        /** Токен живых данных автомобиля из ABRP. */
        const val KEY_ABRP_USER_TOKEN = "abrp_user_token"
        /** Необязательный код модели автомобиля из библиотеки ABRP. */
        const val KEY_ABRP_CAR_MODEL = "abrp_car_model"
        /** Отправлять GPS-координаты и курс в телеметрию ABRP (opt-in, по умолчанию выкл). */
        const val KEY_ABRP_SEND_LOCATION = "abrp_send_location"
        const val KEY_DATA_SOURCE = "data_source"
        const val KEY_MAP_TILE_SOURCE = "map_tile_source"
        const val KEY_AUTOSERVICE_ENABLED = "autoservice_enabled"
        /** "true" hides the native BYD voice assistant (pm disable-user); default "false". */
        const val KEY_DISABLE_NATIVE_ASSISTANT = "disable_native_assistant"
        const val KEY_LAST_MILEAGE_KM = "last_mileage_km"
        const val KEY_LAST_CAPACITY_KWH = "last_capacity_kwh"
        const val KEY_LAST_STATE_TS = "last_state_ts"
        // ChargingStateStore baseline. Kept separate from KEY_LAST_KNOWN_SOC
        // (which TrackingService overwrites on every DiPars poll) so the
        // cascade detector's pre-charging baseline survives polling and
        // runCatchUp can compute a real SOC delta on cold start.
        const val KEY_CHARGING_BASELINE_SOC = "charging_baseline_soc"
        // Set when runCatchUp saw the gun connected (a charge session is in
        // progress around the stored baseline); cleared once the session is
        // reconstructed or dismissed. Lets a later catch-up create the row
        // even if the odometer moved before the first successful run.
        const val KEY_CHARGE_PENDING = "charge_pending"
        // Persistent ring buffer of recent runCatchUp decisions (CatchUpJournal).
        // Included in the diagnostic dump — logcat rotates out the startup
        // window within minutes on DiLink, so field reports need this.
        const val KEY_CATCHUP_JOURNAL = "catchup_journal"
        const val KEY_MIGRATION_V2_4_17 = "migration_v2_4_17_done"
        const val KEY_INSIGHT_CACHE_V2_MIGRATION_DONE = "insight_cache_v2_migration_done"
        // One-shot migration flag: v2.8.1 — clear stale "DIPLUS" data_source value
        // left from pre-native-stack versions. The DataSource.DIPLUS enum was removed
        // in the native-stack migration; getDataSource() now always returns ENERGYDATA.
        const val KEY_MIGRATION_V281_DATA_SOURCE = "migration_v281_data_source_done"

        // Voice feature keys (also mirrored into SharedPreferences("voice") for SteeringWheelKeyService)
        const val KEY_VOICE_ENABLED = "voice_enabled"
        /** "" = follow app language; "RU" or "EN" to override */
        const val KEY_VOICE_LANG = "voice_lang"
        const val KEY_VOICE_KEYCODE = "voice_keycode"
        /** Offline TTS for agent replies; also mirrored into SharedPreferences("voice") for VoiceGate. */
        const val KEY_TTS_ENABLED = "tts_enabled"
        // Wave N: online TTS backends (provider selection is wired in a later task)
        const val KEY_MINIMAX_TTS_PROVIDER = "minimax_tts_provider" // "official" | "fal" | "replicate", default "official"
        const val KEY_MINIMAX_TTS_KEY = "minimax_tts_key"

        const val KEY_AGENT_ENABLED = "agent_enabled"
        // legacy, unused since field-fix wave (agent uses KEY_OPENROUTER_MODEL)
        const val KEY_AGENT_MODEL = "agent_model"

        const val DEFAULT_BATTERY_CAPACITY = "72.9"
        const val DEFAULT_HOME_TARIFF = "0.20"
        const val DEFAULT_DC_TARIFF = "0.73"
        const val DEFAULT_UNITS = "km"
        const val DEFAULT_CURRENCY = "BYN"
        const val DEFAULT_CONSUMPTION_GOOD = "20"
        const val DEFAULT_CONSUMPTION_BAD = "30"
        const val DEFAULT_MAP_TILE_SOURCE = "osm" // "osm" or "amap"
        const val RANGE_CALC_AUTO = "auto"
        const val RANGE_CALC_MANUAL = "manual"
        const val DEFAULT_RANGE_CALC_METHOD = RANGE_CALC_AUTO

        /** Ported defaults from the nordpool1hprices companion app's BYD Atto 3 reference table
         *  (kWh/km converted to kWh/100km to match this app's existing consumption unit). */
        fun defaultManualRangeTable(): List<ManualRangePoint> = listOf(
            ManualRangePoint(20, 16.3, 350.0),
            ManualRangePoint(10, 18.5, 250.0),
            ManualRangePoint(0, 20.6, 200.0),
            ManualRangePoint(-10, 25.0, 180.0),
            ManualRangePoint(-20, 27.2, 161.0),
        )

        val CURRENCIES = listOf(
            Currency("BYN", "BYN"),
            Currency("RUB", "₽"),
            Currency("UAH", "₴"),
            Currency("KZT", "₸"),
            Currency("USD", "$"),
            Currency("EUR", "€"),
            Currency("CNY", "¥"),
            Currency("UZS", "UZS"),
        )
    }

    data class Currency(val code: String, val symbol: String)

    enum class DataSource { ENERGYDATA }

    /**
     * One row of the user-editable manual range table: consumption (and optionally the
     * vehicle's own 100%-SOC range) at a reference temperature. [ManualRangeCalculator]
     * interpolates between rows for the vehicle's current average battery temperature.
     */
    data class ManualRangePoint(
        val temperatureC: Int,
        val consumptionKwhPer100Km: Double,
        val rangeKmAt100Soc: Double? = null,
    )

    suspend fun getString(key: String, default: String): String =
        settingsDao.get(key) ?: default

    fun observeString(key: String): Flow<String?> = settingsDao.observe(key)

    suspend fun setString(key: String, value: String) =
        settingsDao.set(SettingEntity(key, value))

    /** Writes all key/value pairs in one Room transaction (all or nothing). */
    suspend fun setStrings(values: Map<String, String>) =
        settingsDao.setAll(values.map { (k, v) -> SettingEntity(k, v) })

    suspend fun getBatteryCapacity(): Double =
        getString(KEY_BATTERY_CAPACITY, DEFAULT_BATTERY_CAPACITY).parseNumericSetting() ?: 72.9

    suspend fun getHomeTariff(): Double =
        getString(KEY_HOME_TARIFF, DEFAULT_HOME_TARIFF).parseNumericSetting() ?: 0.20

    suspend fun getDcTariff(): Double =
        getString(KEY_DC_TARIFF, DEFAULT_DC_TARIFF).parseNumericSetting() ?: 0.73

    suspend fun getCurrency(): Currency {
        val code = getString(KEY_CURRENCY, DEFAULT_CURRENCY)
        return CURRENCIES.find { it.code == code } ?: CURRENCIES.first()
    }

    suspend fun getCurrencySymbol(): String = getCurrency().symbol

    suspend fun getTripCostTariff(): Double {
        val raw = getString(KEY_TRIP_COST_TARIFF, "home")
        return when (raw) {
            "home" -> getHomeTariff()
            "dc" -> getDcTariff()
            else -> raw.parseNumericSetting() ?: getHomeTariff()
        }
    }

    suspend fun getTripCostTariffKey(): String =
        getString(KEY_TRIP_COST_TARIFF, "home")

    suspend fun getConsumptionGoodThreshold(): Double =
        getString(KEY_CONSUMPTION_GOOD, DEFAULT_CONSUMPTION_GOOD).parseNumericSetting() ?: 20.0

    suspend fun getConsumptionBadThreshold(): Double =
        getString(KEY_CONSUMPTION_BAD, DEFAULT_CONSUMPTION_BAD).parseNumericSetting() ?: 30.0

    /** Live (good, bad) pair for UI coloring. Emits on every Settings edit. */
    fun observeConsumptionThresholds(): Flow<Pair<Double, Double>> = combine(
        observeString(KEY_CONSUMPTION_GOOD).map {
            it?.parseNumericSetting() ?: DEFAULT_CONSUMPTION_GOOD.toDouble()
        },
        observeString(KEY_CONSUMPTION_BAD).map {
            it?.parseNumericSetting() ?: DEFAULT_CONSUMPTION_BAD.toDouble()
        },
    ) { good, bad -> good to bad }

    suspend fun getRangeCalcMethod(): String =
        getString(KEY_RANGE_CALC_METHOD, DEFAULT_RANGE_CALC_METHOD)

    suspend fun setRangeCalcMethod(value: String) =
        setString(KEY_RANGE_CALC_METHOD, value)

    fun observeRangeCalcMethod(): Flow<String> =
        observeString(KEY_RANGE_CALC_METHOD).map { it ?: DEFAULT_RANGE_CALC_METHOD }

    private fun manualRangePointToJson(p: ManualRangePoint): JSONObject = JSONObject().apply {
        put("temperatureC", p.temperatureC)
        put("consumptionKwhPer100Km", p.consumptionKwhPer100Km)
        put("rangeKmAt100Soc", p.rangeKmAt100Soc ?: JSONObject.NULL)
    }

    private fun manualRangePointFromJson(o: JSONObject): ManualRangePoint = ManualRangePoint(
        temperatureC = o.getInt("temperatureC"),
        consumptionKwhPer100Km = o.getDouble("consumptionKwhPer100Km"),
        rangeKmAt100Soc = if (o.isNull("rangeKmAt100Soc")) null else o.getDouble("rangeKmAt100Soc"),
    )

    suspend fun getManualRangeTable(): List<ManualRangePoint> {
        val raw = getString(KEY_MANUAL_RANGE_TABLE, "")
        if (raw.isBlank()) return defaultManualRangeTable()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> manualRangePointFromJson(arr.getJSONObject(i)) }
                .ifEmpty { defaultManualRangeTable() }
        } catch (_: Exception) {
            defaultManualRangeTable()
        }
    }

    suspend fun setManualRangeTable(points: List<ManualRangePoint>) {
        val arr = JSONArray()
        points.sortedByDescending { it.temperatureC }.forEach { arr.put(manualRangePointToJson(it)) }
        setString(KEY_MANUAL_RANGE_TABLE, arr.toString())
    }

    suspend fun resetManualRangeTable() = setManualRangeTable(defaultManualRangeTable())

    suspend fun saveLastKnownSoc(soc: Int) {
        setString(KEY_LAST_KNOWN_SOC, soc.toString())
        setString(KEY_LAST_SOC_TIMESTAMP, System.currentTimeMillis().toString())
    }

    suspend fun getLastKnownSoc(): Int? =
        getString(KEY_LAST_KNOWN_SOC, "").toIntOrNull()

    suspend fun getLastSocTimestamp(): Long =
        getString(KEY_LAST_SOC_TIMESTAMP, "0").toLongOrNull() ?: 0L

    suspend fun getLastEnergyImportTs(): Long =
        getString(KEY_LAST_ENERGYDATA_IMPORT_TS, "0").toLongOrNull() ?: 0L

    suspend fun setLastEnergyImportTs(ts: Long) =
        setString(KEY_LAST_ENERGYDATA_IMPORT_TS, ts.toString())

    suspend fun isSetupCompleted(): Boolean =
        getString(KEY_SETUP_COMPLETED, "false") == "true"

    suspend fun setSetupCompleted() {
        setString(KEY_SETUP_COMPLETED, "true")
        localePreferences.markSetupCompletedMirror()  // sync mirror
    }

    suspend fun isDedupCleanupDone(): Boolean =
        getString(KEY_DEDUP_CLEANUP_DONE, "false") == "true"

    suspend fun setDedupCleanupDone() =
        setString(KEY_DEDUP_CLEANUP_DONE, "true")

    suspend fun isIdleDrainCleanupDone(): Boolean =
        getString(KEY_IDLE_DRAIN_CLEANUP_DONE, "false") == "true"

    suspend fun setIdleDrainCleanupDone() =
        setString(KEY_IDLE_DRAIN_CLEANUP_DONE, "true")

    suspend fun isConsumptionRecalcDone(): Boolean =
        getString(KEY_CONSUMPTION_RECALC_DONE, "false") == "true"

    suspend fun setConsumptionRecalcDone() =
        setString(KEY_CONSUMPTION_RECALC_DONE, "true")

    suspend fun getMapTileSource(): String =
        getString(KEY_MAP_TILE_SOURCE, DEFAULT_MAP_TILE_SOURCE)

    suspend fun setMapTileSource(source: String) =
        setString(KEY_MAP_TILE_SOURCE, source)

    suspend fun isIdleDrainV2CleanupDone(): Boolean =
        getString(KEY_IDLE_DRAIN_V2_CLEANUP, "false") == "true"

    suspend fun setIdleDrainV2CleanupDone() =
        setString(KEY_IDLE_DRAIN_V2_CLEANUP, "true")

    suspend fun isDriveModeRuleMigrationDone(): Boolean =
        getString(KEY_DRIVEMODE_RULE_MIGRATION, "false") == "true"

    suspend fun setDriveModeRuleMigrationDone() =
        setString(KEY_DRIVEMODE_RULE_MIGRATION, "true")

    suspend fun getDataSource(): DataSource = DataSource.ENERGYDATA

    fun observeDataSource(): Flow<String?> = observeString(KEY_DATA_SOURCE)

    suspend fun getChargingBaselineSoc(): Int? =
        getString(KEY_CHARGING_BASELINE_SOC, "").toIntOrNull()

    suspend fun setChargingBaselineSoc(soc: Int) =
        setString(KEY_CHARGING_BASELINE_SOC, soc.toString())

    suspend fun getChargePending(): Boolean =
        getString(KEY_CHARGE_PENDING, "") == "true"

    suspend fun setChargePending(pending: Boolean) =
        setString(KEY_CHARGE_PENDING, pending.toString())

    suspend fun getLastMileageKm(): Float? =
        getString(KEY_LAST_MILEAGE_KM, "").toFloatOrNull()

    suspend fun setLastMileageKm(km: Float?) =
        setString(KEY_LAST_MILEAGE_KM, km?.toString() ?: "")

    suspend fun getLastCapacityKwh(): Float? =
        getString(KEY_LAST_CAPACITY_KWH, "").toFloatOrNull()

    suspend fun setLastCapacityKwh(kwh: Float?) =
        setString(KEY_LAST_CAPACITY_KWH, kwh?.toString() ?: "")

    suspend fun getLastStateTs(): Long =
        getString(KEY_LAST_STATE_TS, "0").toLongOrNull() ?: 0L

    suspend fun setLastStateTs(ts: Long) =
        setString(KEY_LAST_STATE_TS, ts.toString())

    /** Trip 1/2 dashboard counter anchors (n = 1 or 2). Generic settings rows, no migration. */
    suspend fun getTripResetState(n: Int): TripResetState = TripResetState(
        resetTs = getString("trip${n}_reset_ts", "0").toLongOrNull() ?: 0L,
        corrKm = getString("trip${n}_corr_km", "0").toDoubleOrNull() ?: 0.0,
        corrKwh = getString("trip${n}_corr_kwh", "0").toDoubleOrNull() ?: 0.0,
        corrMs = getString("trip${n}_corr_ms", "0").toLongOrNull() ?: 0L,
        excludeStraddling = getString("trip${n}_corr_excl", "0") == "1",
    )

    /** All 5 keys written in a single Room transaction so a process kill cannot
     *  leave a partial anchor (e.g. resetTs updated but corrMs stale). */
    suspend fun setTripResetState(n: Int, state: TripResetState) = setStrings(mapOf(
        "trip${n}_reset_ts" to state.resetTs.toString(),
        "trip${n}_corr_km" to state.corrKm.toString(),
        "trip${n}_corr_kwh" to state.corrKwh.toString(),
        "trip${n}_corr_ms" to state.corrMs.toString(),
        "trip${n}_corr_excl" to if (state.excludeStraddling) "1" else "0",
    ))

    suspend fun isMigrationV2_4_17Done(): Boolean =
        getString(KEY_MIGRATION_V2_4_17, "false") == "true"

    suspend fun setMigrationV2_4_17Done() =
        setString(KEY_MIGRATION_V2_4_17, "true")

    suspend fun isInsightCacheV2MigrationDone(): Boolean =
        getString(KEY_INSIGHT_CACHE_V2_MIGRATION_DONE, "false") == "true"

    suspend fun setInsightCacheV2MigrationDone() =
        setString(KEY_INSIGHT_CACHE_V2_MIGRATION_DONE, "true")

    // --- Voice settings ---

    suspend fun isVoiceEnabled(): Boolean =
        getString(KEY_VOICE_ENABLED, "false") == "true"

    suspend fun setVoiceEnabled(enabled: Boolean) =
        setString(KEY_VOICE_ENABLED, enabled.toString())

    suspend fun getVoiceLang(): String =
        getString(KEY_VOICE_LANG, "")

    suspend fun setVoiceLang(lang: String) =
        setString(KEY_VOICE_LANG, lang)

    suspend fun getVoiceKeycode(): Int =
        getString(KEY_VOICE_KEYCODE, "").toIntOrNull() ?: 0

    suspend fun setVoiceKeycode(keycode: Int) =
        setString(KEY_VOICE_KEYCODE, keycode.toString())

    suspend fun isTtsEnabled(): Boolean =
        getString(KEY_TTS_ENABLED, "false") == "true"

    suspend fun setTtsEnabled(enabled: Boolean) =
        setString(KEY_TTS_ENABLED, enabled.toString())

    suspend fun isAgentEnabled(): Boolean = getString(KEY_AGENT_ENABLED, "false") == "true"
    suspend fun setAgentEnabled(enabled: Boolean) = setString(KEY_AGENT_ENABLED, enabled.toString())

    /**
     * One-shot: if KEY_DATA_SOURCE is still "DIPLUS" (persisted by a pre-native-stack
     * version), overwrite it to "ENERGYDATA". The DIPLUS enum value was removed in
     * the native-stack migration; leaving the stale string in storage is harmless but
     * confusing for future readers.
     */
    suspend fun migrateDataSourceIfNeeded() {
        if (getString(KEY_MIGRATION_V281_DATA_SOURCE, "false") == "true") return
        val stored = getString(KEY_DATA_SOURCE, "")
        if (stored == "DIPLUS") {
            setString(KEY_DATA_SOURCE, "ENERGYDATA")
        }
        setString(KEY_MIGRATION_V281_DATA_SOURCE, "true")
    }
}
