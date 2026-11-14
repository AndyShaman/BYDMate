package com.bydmate.app.data.backup

import com.bydmate.app.data.nativestack.BatchReadGate
import com.bydmate.app.data.repository.SettingsRepository

/**
 * The three independent parts of a configuration archive (#238). [id] is the value stored in the
 * manifest `parts` array and in the `auto_backup_parts` / `manual_backup_parts` settings.
 */
enum class BackupPart(val id: String) {
    TABLES("tables"),
    SETTINGS("settings"),
    KEYS("keys");

    companion object {
        val ALL: Set<BackupPart> = entries.toSet()

        /** What the automatic and the manual save pick until the user chooses: everything but the keys. */
        val DEFAULT: Set<BackupPart> = setOf(TABLES, SETTINGS)

        /** Unknown ids are dropped; an empty or absent value falls back to [DEFAULT]. */
        fun parseCsv(csv: String?): Set<BackupPart> =
            csv.orEmpty().split(',').mapNotNull { id -> fromId(id.trim()) }.toSet().ifEmpty { DEFAULT }

        fun toCsv(parts: Set<BackupPart>): String = entries.filter { it in parts }.joinToString(",") { it.id }

        fun fromId(id: String): BackupPart? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which Room tables and `settings` keys belong to which [BackupPart]. A settings key absent from
 * every list belongs to [BackupPart.SETTINGS]; runtime tables and keys never leave the device.
 */
object BackupParts {

    /** Parent before children: inserts run in this order, deletes in the reverse one. */
    val TABLES_TABLES = listOf(
        "trips", "trip_points", "trip_tombstones",
        "charges", "charge_points",
        "battery_snapshots", "idle_drains",
    )

    /** Tariff periods travel with the flat tariff keys they mirror, which are settings. */
    val SETTINGS_TABLES = listOf("automation_rules", "places", "tariff_periods")

    /** Live state of this head unit: emptied in every archive, kept as is by a merge. */
    val RUNTIME_TABLES = listOf("automation_log", "vehicle_write_log", "odometer_samples", "last_state")

    /** Settings keys tied to the trip and charge history. */
    val TABLES_SETTINGS_KEYS: Set<String> = setOf(
        SettingsRepository.KEY_LAST_ENERGYDATA_IMPORT_TS,
        SettingsRepository.KEY_DEDUP_CLEANUP_DONE,
        SettingsRepository.KEY_ENERGY_IDS_RECYCLED,
        SettingsRepository.KEY_IDLE_DRAIN_CLEANUP_DONE,
        SettingsRepository.KEY_CONSUMPTION_RECALC_DONE,
        SettingsRepository.KEY_IDLE_DRAIN_V2_CLEANUP,
        SettingsRepository.KEY_ENERGY_KWH_SANITY_DONE,
        SettingsRepository.KEY_NATIVE_KWH_SANITY_DONE,
        SettingsRepository.KEY_MIGRATION_V2_4_17,
        SettingsRepository.KEY_INSIGHT_CACHE_V2_MIGRATION_DONE,
        SettingsRepository.KEY_MIGRATION_V281_DATA_SOURCE,
    ) + (1..2).flatMap { n ->
        listOf("reset_ts", "corr_km", "corr_kwh", "corr_ms", "corr_excl").map { "trip${n}_$it" }
    }

    /** Secrets and the addresses they are sent to. */
    val KEYS_SETTINGS_KEYS: Set<String> = setOf(
        SettingsRepository.KEY_OPENROUTER_API_KEY,
        SettingsRepository.KEY_EXA_API_KEY,
        SettingsRepository.KEY_ZAI_API_KEY,
        SettingsRepository.KEY_CUSTOM_API_KEY,
        SettingsRepository.KEY_ALICE_API_KEY,
        SettingsRepository.KEY_ABRP_API_KEY,
        SettingsRepository.KEY_ABRP_USER_TOKEN,
        SettingsRepository.KEY_WEBHOOK_SECRET,
        SettingsRepository.KEY_TG_BACKUP_TOKEN,
        SettingsRepository.KEY_TG_BACKUP_CHAT_ID,
        SettingsRepository.KEY_MINIMAX_TTS_KEY,
        SettingsRepository.KEY_ALICE_ENDPOINT,
        SettingsRepository.KEY_WEBHOOK_URL,
        SettingsRepository.KEY_CUSTOM_BASE_URL,
        SettingsRepository.KEY_TG_BACKUP_BOT_NAME,
        SettingsRepository.KEY_TG_BACKUP_CHAT_NAME,
    )

    val RUNTIME_SETTINGS_KEYS: Set<String> = setOf(
        "last_known_soc",
        "last_soc_timestamp",
        SettingsRepository.KEY_CHARGING_BASELINE_SOC,
        SettingsRepository.KEY_CHARGE_PENDING,
        SettingsRepository.KEY_CATCHUP_JOURNAL,
        SettingsRepository.KEY_LAST_MILEAGE_KM,
        SettingsRepository.KEY_LAST_CAPACITY_KWH,
        SettingsRepository.KEY_LAST_STATE_TS,
        BatchReadGate.KEY_STATE,
        BatchReadGate.KEY_FP,
        BatchReadGate.KEY_OK,
        BatchReadGate.KEY_MM,
        SettingsRepository.KEY_AUTO_BACKUP_LAST_TS,
        SettingsRepository.KEY_AUTO_BACKUP_LAST_RESULT,
        SettingsRepository.KEY_AUTO_BACKUP_PENDING_UPLOAD,
        SettingsRepository.KEY_TG_REPORT_OUTBOX,
    )

    fun tablesOf(part: BackupPart): List<String> = when (part) {
        BackupPart.TABLES -> TABLES_TABLES
        BackupPart.SETTINGS -> SETTINGS_TABLES
        BackupPart.KEYS -> emptyList()
    }

    /** SQL condition on `settings.key` selecting the keys of [part], with its bind arguments. */
    fun settingsKeyFilter(part: BackupPart): Pair<String, Array<String>> {
        fun inList(keys: Collection<String>) = keys.joinToString(",", "(", ")") { "?" }
        return when (part) {
            BackupPart.TABLES -> "key IN ${inList(TABLES_SETTINGS_KEYS)}" to TABLES_SETTINGS_KEYS.toTypedArray()
            BackupPart.KEYS -> "key IN ${inList(KEYS_SETTINGS_KEYS)}" to KEYS_SETTINGS_KEYS.toTypedArray()
            BackupPart.SETTINGS -> {
                val others = TABLES_SETTINGS_KEYS + KEYS_SETTINGS_KEYS + RUNTIME_SETTINGS_KEYS
                "key NOT IN ${inList(others)}" to others.toTypedArray()
            }
        }
    }
}
