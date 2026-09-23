package com.bydmate.app.data.backup

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoBackupRunnerTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private lateinit var dir: File
    private lateinit var settings: SettingsRepository
    private val backupManager: BackupManager = mockk()
    private val sink: TelegramBackupSink = mockk()
    private var now = 1_000_000_000_000L
    private var exportCount = 0

    @Before fun setUp() {
        dir = tmp.newFolder("Download")
        settings = SettingsRepository(FakeSettingsDao(), mockk<LocalePreferences>(relaxed = true))
        runBlocking { settings.setAutoBackupPeriod(AutoBackupPeriod.DAILY) }
        every { backupManager.export(any()) } answers {
            exportCount++
            File(dir, "bydmate_backup_20260923_1000${exportCount.toString().padStart(2, '0')}.zip").apply {
                writeBytes(ByteArray(2048))
                setLastModified(now)
            }
        }
    }

    private fun runner() = AutoBackupRunner(backupManager, sink, settings) { now }

    private suspend fun configureTelegram() {
        settings.saveTgBackup("123:abc", "my_backup_bot", 42L, "Andy")
    }

    // --- due decision ---

    @Test fun `off is never due, even with a pending upload`() {
        assertFalse(isAutoBackupDue(AutoBackupPeriod.OFF, 0L, now, pendingUpload = false))
        assertFalse(isAutoBackupDue(AutoBackupPeriod.OFF, 0L, now, pendingUpload = true))
    }

    @Test fun `never run before is due for every enabled period`() {
        for (period in listOf(AutoBackupPeriod.DAILY, AutoBackupPeriod.WEEKLY, AutoBackupPeriod.MONTHLY)) {
            assertTrue(period.name, isAutoBackupDue(period, 0L, now, pendingUpload = false))
        }
    }

    @Test fun `each period is due exactly once its interval has passed`() {
        val hour = 60L * 60 * 1000
        val day = 24 * hour
        val cases = mapOf(
            AutoBackupPeriod.DAILY to day,
            AutoBackupPeriod.WEEKLY to 7 * day,
            AutoBackupPeriod.MONTHLY to 30 * day,
        )
        for ((period, interval) in cases) {
            assertFalse(period.name, isAutoBackupDue(period, now - interval + hour, now, pendingUpload = false))
            assertTrue(period.name, isAutoBackupDue(period, now - interval, now, pendingUpload = false))
        }
    }

    @Test fun `pending upload makes an enabled period due before its interval`() {
        assertTrue(isAutoBackupDue(AutoBackupPeriod.MONTHLY, now - 1000L, now, pendingUpload = true))
    }

    @Test fun `last run in the future after a clock reset is due`() {
        assertTrue(isAutoBackupDue(AutoBackupPeriod.MONTHLY, now + 1000L, now, pendingUpload = false))
    }

    @Test fun `unknown stored period falls back to off`() {
        assertEquals(AutoBackupPeriod.OFF, AutoBackupPeriod.fromKey("hourly"))
        assertEquals(AutoBackupPeriod.OFF, AutoBackupPeriod.fromKey(null))
        assertEquals(AutoBackupPeriod.WEEKLY, AutoBackupPeriod.fromKey("weekly"))
    }

    // --- export, rename, rotation ---

    @Test fun `export is renamed to the auto name and kept local when telegram is not configured`() = runTest {
        val outcome = runner().run()

        assertEquals(RunOutcome.SUCCESS, outcome)
        val names = dir.list()!!.toList()
        assertEquals(listOf("bydmate_backup_auto_20260923_100001.zip"), names)
        assertEquals(now, settings.getAutoBackupLastTs())
        assertEquals(AutoBackupRunner.RESULT_LOCAL_ONLY, settings.getAutoBackupLastResult())
        assertEquals("", settings.getAutoBackupPendingUpload())
    }

    @Test fun `rotation keeps the five newest auto backups and never touches manual exports`() = runTest {
        val manual = File(dir, "bydmate_backup_20200101_000000.zip").apply {
            writeBytes(ByteArray(1)); setLastModified(now - 1_000_000)
        }
        val other = File(dir, "notes.zip").apply { writeBytes(ByteArray(1)); setLastModified(now - 1_000_000) }
        val oldAuto = (1..6).map { i ->
            File(dir, "bydmate_backup_auto_2026090${i}_000000.zip").apply {
                writeBytes(ByteArray(1)); setLastModified(now - (10 - i) * 60_000L)
            }
        }

        runner().run()

        val autoLeft = dir.listFiles()!!.filter { it.name.startsWith(AutoBackupRunner.AUTO_PREFIX) }.map { it.name }.toSet()
        assertEquals(AutoBackupRunner.KEEP_LOCAL, autoLeft.size)
        assertTrue("bydmate_backup_auto_20260923_100001.zip" in autoLeft)
        // The two oldest of the six pre-existing auto files are gone, the four newest stay.
        assertFalse(oldAuto[0].exists())
        assertFalse(oldAuto[1].exists())
        assertTrue(oldAuto.drop(2).all { it.exists() })
        assertTrue(manual.exists())
        assertTrue(other.exists())
    }

    @Test fun `fresh export survives rotation even when older files carry later timestamps`() = runTest {
        val skewed = (1..6).map { i ->
            File(dir, "bydmate_backup_auto_2030010${i}_000000.zip").apply {
                writeBytes(ByteArray(1)); setLastModified(now + i * 60_000L)
            }
        }

        runner().run()

        assertTrue(File(dir, "bydmate_backup_auto_20260923_100001.zip").exists())
        assertEquals(AutoBackupRunner.KEEP_LOCAL, dir.list()!!.size)
        assertFalse(skewed[0].exists())
        assertFalse(skewed[1].exists())
        assertTrue(skewed.drop(2).all { it.exists() })
    }

    @Test fun `rotation leaves a directory with an auto backup name alone`() = runTest {
        val folder = File(dir, "bydmate_backup_auto_20200101_000000.zip").apply { mkdir(); setLastModified(now - 1_000_000) }
        (1..5).forEach { i ->
            File(dir, "bydmate_backup_auto_2026090${i}_000000.zip").apply {
                writeBytes(ByteArray(1)); setLastModified(now - (10 - i) * 60_000L)
            }
        }

        runner().run()

        assertTrue(folder.isDirectory)
        assertEquals(AutoBackupRunner.KEEP_LOCAL, dir.listFiles()!!.count { it.isFile })
    }

    // --- period switched off after enqueueing ---

    @Test fun `scheduled run with the period off does nothing`() = runTest {
        configureTelegram()
        settings.setAutoBackupPeriod(AutoBackupPeriod.OFF)

        assertEquals(RunOutcome.SUCCESS, runner().run())

        verify(exactly = 0) { backupManager.export(any()) }
        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any()) }
        assertEquals("", settings.getAutoBackupLastResult())
    }

    @Test fun `manual run works with the period off, retries included`() = runTest {
        configureTelegram()
        settings.setAutoBackupPeriod(AutoBackupPeriod.OFF)
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns
            Result.failure(TelegramSinkException(TelegramError.NO_NETWORK))
        assertEquals(RunOutcome.RETRY, runner().run(force = true, manual = true))

        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.success(Unit)
        assertEquals(RunOutcome.SUCCESS, runner().run(force = false, manual = true))

        verify(exactly = 1) { backupManager.export(any()) }
        assertEquals(AutoBackupRunner.RESULT_SENT, settings.getAutoBackupLastResult())
    }

    // --- delivery ---

    @Test fun `file is pending before the upload starts, so a cancelled upload keeps it`() = runTest {
        configureTelegram()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } throws CancellationException("worker stopped")

        runCatching { runner().run() }

        assertTrue(settings.getAutoBackupPendingUpload().endsWith("bydmate_backup_auto_20260923_100001.zip"))
    }

    @Test fun `ok false answer with a permanent code leaves nothing pending`() = runTest {
        configureTelegram()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns
            Result.failure(TelegramSinkException(TelegramError.HTTP, 403))

        assertEquals(RunOutcome.SUCCESS, runner().run())

        assertEquals("", settings.getAutoBackupPendingUpload())
        assertEquals("send_error:HTTP:403", settings.getAutoBackupLastResult())
    }

    @Test fun `caption carries the file date, not the upload time`() = runTest {
        configureTelegram()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.success(Unit)
        val exportedAt = now
        val runner = AutoBackupRunner(backupManager, sink, settings) { exportedAt + 3L * 24 * 60 * 60 * 1000 }

        runner.run()

        val date = SimpleDateFormat("d MMMM HH:mm", Locale("ru")).format(Date(exportedAt))
        coVerify { sink.sendDocument(any(), any(), any(), match { it.startsWith("BYDMate: бэкап $date, ") }) }
    }

    @Test fun `file over the telegram limit is a permanent error without an upload`() = runTest {
        configureTelegram()
        every { backupManager.export(any()) } answers {
            File(dir, "bydmate_backup_20260923_100001.zip").apply {
                RandomAccessFile(this, "rw").use { it.setLength(TelegramBackupSink.MAX_UPLOAD_BYTES + 1) }
            }
        }

        assertEquals(RunOutcome.SUCCESS, runner().run())

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any()) }
        assertEquals("send_error:TOO_LARGE", settings.getAutoBackupLastResult())
        assertEquals("", settings.getAutoBackupPendingUpload())
    }

    @Test fun `forced run exports afresh even with a pending upload`() = runTest {
        configureTelegram()
        settings.setAutoBackupPeriod(AutoBackupPeriod.WEEKLY)
        settings.setAutoBackupLastTs(now - 1000L)
        val stuck = File(dir, "bydmate_backup_auto_20260901_000000.zip").apply {
            writeBytes(ByteArray(1)); setLastModified(now - 1_000_000)
        }
        settings.setAutoBackupPendingUpload(stuck.absolutePath)
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.success(Unit)

        assertEquals(RunOutcome.SUCCESS, runner().run(force = true))

        verify(exactly = 1) { backupManager.export(any()) }
        val fresh = File(dir, "bydmate_backup_auto_20260923_100001.zip")
        coVerify(exactly = 1) { sink.sendDocument(any(), any(), match { it.absolutePath == fresh.absolutePath }, any()) }
        assertEquals("", settings.getAutoBackupPendingUpload())
    }

    @Test fun `successful upload reports it and leaves nothing pending`() = runTest {
        configureTelegram()
        coEvery { sink.sendDocument("123:abc", 42L, any(), any()) } returns Result.success(Unit)

        assertEquals(RunOutcome.SUCCESS, runner().run())

        assertEquals(AutoBackupRunner.RESULT_SENT, settings.getAutoBackupLastResult())
        assertEquals("", settings.getAutoBackupPendingUpload())
        coVerify { sink.sendDocument("123:abc", 42L, match { it.name.startsWith("bydmate_backup_auto_") }, match { it.startsWith("BYDMate: бэкап ") && it.endsWith(" МБ") }) }
    }

    @Test fun `transient upload failure keeps the file pending and asks for a retry`() = runTest {
        configureTelegram()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns
            Result.failure(TelegramSinkException(TelegramError.NO_NETWORK))

        assertEquals(RunOutcome.RETRY, runner().run())

        val pending = settings.getAutoBackupPendingUpload()
        assertTrue(pending.endsWith("bydmate_backup_auto_20260923_100001.zip"))
        assertEquals("send_error:NO_NETWORK", settings.getAutoBackupLastResult())
    }

    @Test fun `next run uploads the pending file without exporting again`() = runTest {
        configureTelegram()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns
            Result.failure(TelegramSinkException(TelegramError.NO_NETWORK))
        runner().run()
        val firstTs = settings.getAutoBackupLastTs()
        val pending = settings.getAutoBackupPendingUpload()

        now += 3_600_000L
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.success(Unit)
        assertEquals(RunOutcome.SUCCESS, runner().run())

        verify(exactly = 1) { backupManager.export(any()) }
        coVerify { sink.sendDocument(any(), any(), match { it.absolutePath == pending }, any()) }
        assertEquals("", settings.getAutoBackupPendingUpload())
        assertEquals(AutoBackupRunner.RESULT_SENT, settings.getAutoBackupLastResult())
        assertEquals(firstTs, settings.getAutoBackupLastTs())
    }

    @Test fun `pending path pointing to a deleted file exports a fresh backup`() = runTest {
        settings.setAutoBackupPendingUpload(File(dir, "bydmate_backup_auto_gone.zip").absolutePath)

        assertEquals(RunOutcome.SUCCESS, runner().run())

        verify(exactly = 1) { backupManager.export(any()) }
        assertEquals("", settings.getAutoBackupPendingUpload())
    }

    @Test fun `stale pending path is cleared even when the fresh export fails`() = runTest {
        settings.setAutoBackupPendingUpload(File(dir, "bydmate_backup_auto_gone.zip").absolutePath)
        every { backupManager.export(any()) } throws IllegalStateException("База данных занята")

        assertEquals(RunOutcome.FAILURE, runner().run())

        assertEquals("", settings.getAutoBackupPendingUpload())
    }

    @Test fun `pending upload is sent without a new export while the period has not elapsed`() = runTest {
        configureTelegram()
        settings.setAutoBackupPeriod(AutoBackupPeriod.WEEKLY)
        settings.setAutoBackupLastTs(now - 24L * 60 * 60 * 1000)
        val stuck = File(dir, "bydmate_backup_auto_20260901_000000.zip").apply { writeBytes(ByteArray(1)) }
        settings.setAutoBackupPendingUpload(stuck.absolutePath)
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.success(Unit)

        assertEquals(RunOutcome.SUCCESS, runner().run())

        verify(exactly = 0) { backupManager.export(any()) }
        coVerify { sink.sendDocument(any(), any(), match { it.absolutePath == stuck.absolutePath }, any()) }
        assertEquals("", settings.getAutoBackupPendingUpload())
    }

    @Test fun `stuck pending upload is replaced by a fresh export once the period is due`() = runTest {
        configureTelegram()
        settings.setAutoBackupPeriod(AutoBackupPeriod.WEEKLY)
        settings.setAutoBackupLastTs(now - 8L * 24 * 60 * 60 * 1000)
        val stuck = File(dir, "bydmate_backup_auto_20260901_000000.zip").apply {
            writeBytes(ByteArray(1)); setLastModified(now - 1_000_000)
        }
        settings.setAutoBackupPendingUpload(stuck.absolutePath)
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns
            Result.failure(TelegramSinkException(TelegramError.NO_NETWORK))

        assertEquals(RunOutcome.RETRY, runner().run())

        verify(exactly = 1) { backupManager.export(any()) }
        val fresh = File(dir, "bydmate_backup_auto_20260923_100001.zip")
        coVerify(exactly = 1) { sink.sendDocument(any(), any(), match { it.absolutePath == fresh.absolutePath }, any()) }
        assertEquals(fresh.absolutePath, settings.getAutoBackupPendingUpload())
        assertEquals(now, settings.getAutoBackupLastTs())
    }

    @Test fun `permanent upload failure does not pin later runs to the same file`() = runTest {
        configureTelegram()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns
            Result.failure(TelegramSinkException(TelegramError.TOO_LARGE, 413))

        assertEquals(RunOutcome.SUCCESS, runner().run())

        assertEquals("", settings.getAutoBackupPendingUpload())
        assertEquals("send_error:TOO_LARGE", settings.getAutoBackupLastResult())
        assertEquals(1, dir.list()!!.size)
    }

    @Test fun `export failure keeps the last timestamp so the next ignition retries`() = runTest {
        settings.setAutoBackupLastTs(123L)
        every { backupManager.export(any()) } throws IllegalStateException("База данных занята")

        assertEquals(RunOutcome.FAILURE, runner().run())

        assertEquals(123L, settings.getAutoBackupLastTs())
        assertEquals("export_error", settings.getAutoBackupLastResult())
    }

    // --- parts (#238) ---

    @Test fun `auto save exports tables and settings until the parts are chosen, then the stored parts`() = runTest {
        runner().run()
        verify(exactly = 1) { backupManager.export(setOf(BackupPart.TABLES, BackupPart.SETTINGS)) }

        settings.setAutoBackupParts(setOf(BackupPart.TABLES, BackupPart.KEYS))
        now += 2L * 24 * 60 * 60 * 1000
        runner().run()
        verify(exactly = 1) { backupManager.export(setOf(BackupPart.TABLES, BackupPart.KEYS)) }
    }

    @Test fun `manual save with a bot sends one copy and leaves the auto backup state alone`() = runTest {
        configureTelegram()
        settings.setAutoBackupLastTs(123L)
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.success(Unit)

        val result = runner().saveManual(setOf(BackupPart.KEYS))

        verify(exactly = 1) { backupManager.export(setOf(BackupPart.KEYS)) }
        coVerify(exactly = 1) {
            sink.sendDocument("123:abc", 42L, match { it.name == "bydmate_backup_20260923_100001.zip" }, match { it.startsWith("BYDMate: бэкап ") })
        }
        assertTrue(result.sent)
        assertEquals(null, result.sendError)
        assertTrue(result.file.exists())
        assertEquals("", settings.getAutoBackupPendingUpload())
        assertEquals("", settings.getAutoBackupLastResult())
        assertEquals(123L, settings.getAutoBackupLastTs())
    }

    @Test fun `manual save that fails to send reports why and queues nothing`() = runTest {
        configureTelegram()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns
            Result.failure(TelegramSinkException(TelegramError.NO_NETWORK))

        val result = runner().saveManual(BackupPart.ALL)

        assertFalse(result.sent)
        assertEquals("NO_NETWORK", result.sendError)
        assertTrue(result.file.exists())
        assertEquals("", settings.getAutoBackupPendingUpload())
    }

    @Test fun `manual save without a bot stays in Download`() = runTest {
        val result = runner().saveManual(BackupPart.DEFAULT)

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any()) }
        assertFalse(result.sent)
        assertEquals(null, result.sendError)
        assertEquals(listOf("bydmate_backup_20260923_100001.zip"), dir.list()!!.toList())
    }
}
