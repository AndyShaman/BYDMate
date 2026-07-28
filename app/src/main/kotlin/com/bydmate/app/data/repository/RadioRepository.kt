package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.RadioStationDao
import com.bydmate.app.data.local.entity.RadioStationEntity
import com.bydmate.app.media.RadioPresets
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioRepository @Inject constructor(
    private val radioStationDao: RadioStationDao,
    private val settingsRepository: SettingsRepository
) {
    fun getAll(): Flow<List<RadioStationEntity>> = radioStationDao.getAll()
    suspend fun getAllSnapshot(): List<RadioStationEntity> = radioStationDao.getAllSnapshot()
    suspend fun getById(id: Long): RadioStationEntity? = radioStationDao.getById(id)
    suspend fun insert(station: RadioStationEntity): Long = radioStationDao.insert(station)
    suspend fun update(station: RadioStationEntity) = radioStationDao.update(station)
    suspend fun delete(station: RadioStationEntity) = radioStationDao.delete(station)

    /**
     * Brings an existing station list in line with the current build: retires stations dropped
     * from [RadioPresets.ALL], then seeds the ones that were never inserted. Called whenever the
     * Radio tab opens and when the feature is switched on.
     */
    suspend fun syncPresets() {
        retireDroppedPresets()
        seedPresetsIfNeeded()
    }

    /**
     * Deletes built-in stations that later builds removed — currently only Русское Радио, whose
     * HE-AAC mount never played on DiLink.
     *
     * Runs once per install and only against an untouched preset row (same stream URL *and* still
     * carrying the preset icon reference): a station the user renamed, re-pointed or added back by
     * hand is theirs, and is left alone.
     */
    suspend fun retireDroppedPresets() {
        if (settingsRepository.isRadioPresetsRetired()) return
        val presetIcon = RadioPresets.iconRef("rusradio")
        radioStationDao.getAllSnapshot()
            .filter { it.url == RadioPresets.RETIRED_RUSRADIO_URL && it.iconUrl == presetIcon }
            .forEach { radioStationDao.delete(it) }
        settingsRepository.setRadioPresetsRetired()
    }

    /**
     * Inserts [RadioPresets.ALL] the first time the Radio tab is opened.
     *
     * Guarded by a persisted flag, deliberately not by "is the list empty": a user who deletes
     * every built-in station must not get them all back on the next launch. Stations already
     * present under the same stream URL are skipped, so a partially applied seed (process killed
     * mid-way) cannot produce duplicates on retry.
     */
    suspend fun seedPresetsIfNeeded() {
        if (settingsRepository.isRadioPresetsSeeded()) return
        insertMissingPresets()
        settingsRepository.setRadioPresetsSeeded()
    }

    /**
     * Re-adds the built-in stations the user has deleted, on explicit request from Settings.
     * Existing stations — built-in or hand-added — are left untouched; returns how many rows were
     * actually inserted so the caller can report "nothing to restore".
     */
    suspend fun restorePresets(): Int {
        val added = insertMissingPresets()
        settingsRepository.setRadioPresetsSeeded()
        return added
    }

    /** Inserts every preset whose stream URL is not already in the table. Returns the count. */
    private suspend fun insertMissingPresets(): Int {
        val existingUrls = radioStationDao.getAllSnapshot().mapTo(HashSet()) { it.url }
        val missing = RadioPresets.ALL.filterNot { it.url in existingUrls }
        missing.forEach { preset ->
            radioStationDao.insert(
                RadioStationEntity(
                    name = preset.name,
                    url = preset.url,
                    iconUrl = RadioPresets.iconRef(preset.key)
                )
            )
        }
        return missing.size
    }

    fun observeEnabled(): Flow<Boolean> = settingsRepository.observeRadioEnabled()

    fun observeDataSaver(): Flow<Boolean> = settingsRepository.observeRadioDataSaver()

    suspend fun setDataSaver(enabled: Boolean) = settingsRepository.setRadioDataSaver(enabled)

    suspend fun isDataSaver(): Boolean = settingsRepository.isRadioDataSaver()

    suspend fun isEnabled(): Boolean = settingsRepository.isRadioEnabled()

    /** Turning the feature on seeds the built-in stations if they were never inserted. */
    suspend fun setEnabled(enabled: Boolean) {
        settingsRepository.setRadioEnabled(enabled)
        if (enabled) syncPresets()
    }
}
