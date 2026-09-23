package com.bydmate.app.data.backup

/** How often the automatic backup runs (#237). [key] is the persisted settings value. */
enum class AutoBackupPeriod(val key: String, val intervalMs: Long) {
    OFF("off", 0L),
    DAILY("daily", DAY_MS),
    WEEKLY("weekly", 7 * DAY_MS),
    MONTHLY("monthly", 30 * DAY_MS);

    companion object {
        /** Unknown or absent value maps to OFF. */
        fun fromKey(key: String?): AutoBackupPeriod = entries.firstOrNull { it.key == key } ?: OFF
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
