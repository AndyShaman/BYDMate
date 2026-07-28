package com.bydmate.app.data.repository

import com.bydmate.app.data.local.dao.RadioStationDao
import com.bydmate.app.data.local.entity.RadioStationEntity
import com.bydmate.app.media.RadioPresets
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioRepositorySeedTest {

    private val dao = mockk<RadioStationDao>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val repository = RadioRepository(dao, settings)

    @Test
    fun `first open inserts every preset and marks the flag`() = runTest {
        coEvery { settings.isRadioPresetsSeeded() } returns false
        coEvery { dao.getAllSnapshot() } returns emptyList()
        val inserted = mutableListOf<RadioStationEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 1L

        repository.seedPresetsIfNeeded()

        assertEquals(RadioPresets.ALL.size, inserted.size)
        assertEquals(RadioPresets.ALL.map { it.name }, inserted.map { it.name })
        assertEquals(RadioPresets.ALL.map { it.url }, inserted.map { it.url })
        assertTrue(inserted.all { RadioPresets.fromIconRef(it.iconUrl) != null })
        coVerify(exactly = 1) { settings.setRadioPresetsSeeded() }
    }

    @Test
    fun `deleted presets do not come back on a later open`() = runTest {
        // The user wiped the list after the first seed: the flag is set, so nothing is re-inserted.
        coEvery { settings.isRadioPresetsSeeded() } returns true
        coEvery { dao.getAllSnapshot() } returns emptyList()

        repository.seedPresetsIfNeeded()

        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { settings.setRadioPresetsSeeded() }
    }

    @Test
    fun `a station already added by hand is not duplicated`() = runTest {
        val preset = RadioPresets.ALL.first()
        coEvery { settings.isRadioPresetsSeeded() } returns false
        coEvery { dao.getAllSnapshot() } returns listOf(
            RadioStationEntity(id = 7, name = "Моя станция", url = preset.url)
        )
        val inserted = mutableListOf<RadioStationEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 1L

        repository.seedPresetsIfNeeded()

        assertEquals(RadioPresets.ALL.size - 1, inserted.size)
        assertTrue(inserted.none { it.url == preset.url })
    }

    @Test
    fun `restore re-adds only the built-ins that are missing`() = runTest {
        val kept = RadioPresets.ALL.take(2)
        coEvery { dao.getAllSnapshot() } returns kept.map {
            RadioStationEntity(name = it.name, url = it.url, iconUrl = RadioPresets.iconRef(it.key))
        }
        val inserted = mutableListOf<RadioStationEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 1L

        val added = repository.restorePresets()

        assertEquals(RadioPresets.ALL.size - kept.size, added)
        assertEquals(added, inserted.size)
        assertTrue(inserted.none { row -> row.url in kept.map { it.url } })
    }

    @Test
    fun `restore reports zero when nothing is missing`() = runTest {
        coEvery { dao.getAllSnapshot() } returns RadioPresets.ALL.map {
            RadioStationEntity(name = it.name, url = it.url)
        }

        assertEquals(0, repository.restorePresets())

        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `enabling radio switches the flag on and seeds the built-ins`() = runTest {
        coEvery { settings.isRadioPresetsSeeded() } returns false
        coEvery { dao.getAllSnapshot() } returns emptyList()
        val inserted = mutableListOf<RadioStationEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 1L

        repository.setEnabled(true)

        coVerify(exactly = 1) { settings.setRadioEnabled(true) }
        assertEquals(RadioPresets.ALL.size, inserted.size)
    }

    @Test
    fun `disabling radio writes the flag and touches no stations`() = runTest {
        repository.setEnabled(false)

        coVerify(exactly = 1) { settings.setRadioEnabled(false) }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `an untouched dropped preset is retired once`() = runTest {
        coEvery { settings.isRadioPresetsRetired() } returns false
        val retired = RadioStationEntity(
            id = 3,
            name = "Русское Радио",
            url = RadioPresets.RETIRED_RUSRADIO_URL,
            iconUrl = RadioPresets.iconRef("rusradio")
        )
        val keep = RadioStationEntity(id = 4, name = "Хит FM", url = RadioPresets.ALL[2].url)
        coEvery { dao.getAllSnapshot() } returns listOf(retired, keep)

        repository.retireDroppedPresets()

        coVerify(exactly = 1) { dao.delete(retired) }
        coVerify(exactly = 0) { dao.delete(keep) }
        coVerify(exactly = 1) { settings.setRadioPresetsRetired() }
    }

    @Test
    fun `a dropped station the user made their own survives`() = runTest {
        coEvery { settings.isRadioPresetsRetired() } returns false
        // Same stream, but re-added by hand: no preset icon reference, so it is the user's row.
        val mine = RadioStationEntity(
            id = 9,
            name = "РР — мой поток",
            url = RadioPresets.RETIRED_RUSRADIO_URL,
            iconUrl = null
        )
        coEvery { dao.getAllSnapshot() } returns listOf(mine)

        repository.retireDroppedPresets()

        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `retirement runs only once per install`() = runTest {
        coEvery { settings.isRadioPresetsRetired() } returns true

        repository.retireDroppedPresets()

        coVerify(exactly = 0) { dao.getAllSnapshot() }
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `the dropped station is gone and the new ones are in`() {
        val urls = RadioPresets.ALL.map { it.url }
        assertTrue(RadioPresets.RETIRED_RUSRADIO_URL !in urls)
        assertEquals(
            listOf("loveradio", "record", "hitfm"),
            listOf("loveradio", "record", "hitfm").filter { key ->
                RadioPresets.ALL.any { it.key == key }
            }
        )
    }

    @Test
    fun `presets are well formed`() {
        val presets = RadioPresets.ALL
        assertEquals(8, presets.size)
        assertEquals(presets.size, presets.map { it.key }.toSet().size)
        assertEquals(presets.size, presets.map { it.url }.toSet().size)
        assertTrue(presets.all { it.url.startsWith("https://") })
        assertTrue(presets.all { it.name.isNotBlank() })
        assertTrue(presets.all { it.monogram.length in 1..3 })
        // Every reference must resolve back, otherwise tiles silently lose their icon.
        assertTrue(presets.all { RadioPresets.fromIconRef(RadioPresets.iconRef(it.key)) == it })
    }

    @Test
    fun `unknown or foreign icon references do not resolve to a preset`() {
        assertEquals(null, RadioPresets.fromIconRef(null))
        assertEquals(null, RadioPresets.fromIconRef("https://cdn.example/logo.png"))
        assertEquals(null, RadioPresets.fromIconRef("bydmate://preset/does-not-exist"))
    }
}
