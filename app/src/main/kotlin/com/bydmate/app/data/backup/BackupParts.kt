package com.bydmate.app.data.backup

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
        "last_energydata_import_ts",
        "dedup_cleanup_done",
        "idle_drain_cleanup_done",
        "consumption_recalc_done",
        "idle_drain_v2_cleanup",
        "energydata_kwh_sanity_v1_done",
        "migration_v2_4_17_done",
        "insight_cache_v2_migration_done",
        "migration_v281_data_source_done",
    ) + (1..2).flatMap { n ->
        listOf("reset_ts", "corr_km", "corr_kwh", "corr_ms", "corr_excl").map { "trip${n}_$it" }
    }

    /** Secrets and the addresses they are sent to. */
    val KEYS_SETTINGS_KEYS: Set<String> = setOf(
        "openrouter_api_key",
        "exa_api_key",
        "zai_api_key",
        "custom_llm_api_key",
        "alice_api_key",
        "abrp_api_key",
        "abrp_user_token",
        "webhook_secret",
        "tg_backup_token",
        "tg_backup_chat_id",
        "minimax_tts_key",
        "alice_endpoint",
        "webhook_url",
        "custom_llm_base_url",
        "tg_backup_bot_name",
        "tg_backup_chat_name",
    )

    val RUNTIME_SETTINGS_KEYS: Set<String> = setOf(
        "last_known_soc",
        "last_soc_timestamp",
        "charging_baseline_soc",
        "charge_pending",
        "catchup_journal",
        "last_mileage_km",
        "last_capacity_kwh",
        "last_state_ts",
        "batch_read_state",
        "batch_read_fp",
        "batch_read_ok",
        "batch_read_mm",
        "auto_backup_last_ts",
        "auto_backup_last_result",
        "auto_backup_pending_upload",
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
