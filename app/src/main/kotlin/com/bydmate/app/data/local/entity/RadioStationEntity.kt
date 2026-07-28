package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-added internet radio station: display name, stream URL and an optional icon.
 *
 * [iconUrl] is either an http(s) URL or a persisted content:// URI from the system file
 * picker — [com.bydmate.app.media.RadioIconLoader] resolves both.
 */
@Entity(tableName = "radio_stations")
data class RadioStationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    @ColumnInfo(name = "icon_url") val iconUrl: String? = null,
    /**
     * Lighter stream for "Экономить трафик", or null when the station publishes only one.
     *
     * Stored per station rather than derived, because a station added from the directory knows
     * its own tiers and nothing else can guess them. Built-in stations keep using the URL pair
     * in [com.bydmate.app.media.RadioPresets] instead, so their row stays untouched.
     */
    @ColumnInfo(name = "low_bitrate_url") val lowBitrateUrl: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
