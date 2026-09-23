package com.bydmate.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateApkCleanupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `older version apk is stale`() {
        val files = listOf(File("BYDMate-3.17.4.apk"), File("BYDMate-3.17.5.apk"))
        val stale = UpdateApkCleanup.staleApks(files, "3.17.5")
        assertEquals(listOf(File("BYDMate-3.17.4.apk")), stale)
    }

    @Test
    fun `newer version downloaded but not installed yet is kept`() {
        val files = listOf(File("BYDMate-v3.18.0.apk"), File("BYDMate-3.17.5.apk"))
        val stale = UpdateApkCleanup.staleApks(files, "3.17.5")
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `versions compare numerically, not as text`() {
        val files = listOf(File("BYDMate-3.9.9.apk"), File("BYDMate-3.100.0.apk"), File("BYDMate-3.17.apk"))
        val stale = UpdateApkCleanup.staleApks(files, "3.17.0")
        assertEquals(listOf(File("BYDMate-3.9.9.apk")), stale)
    }

    @Test
    fun `unparseable version is kept`() {
        val files = listOf(
            File("BYDMate-v3.17.3-test.apk"),
            File("BYDMate-v3.17.4 (1).apk"),
            File("BYDMate-latest.apk"),
        )
        val stale = UpdateApkCleanup.staleApks(files, "3.17.5")
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `current version with a suffix still deletes older numeric versions`() {
        val files = listOf(File("BYDMate-3.17.4.apk"), File("BYDMate-3.17.5.apk"))
        val stale = UpdateApkCleanup.staleApks(files, "3.17.5-test")
        assertEquals(listOf(File("BYDMate-3.17.4.apk")), stale)
    }

    @Test
    fun `unparseable current version deletes nothing`() {
        val files = listOf(File("BYDMate-3.17.4.apk"))
        assertTrue(UpdateApkCleanup.staleApks(files, "dev").isEmpty())
    }

    @Test
    fun `v and no-v forms of the current version are both kept`() {
        val files = listOf(File("BYDMate-3.17.5.apk"), File("BYDMate-v3.17.5.apk"))
        val stale = UpdateApkCleanup.staleApks(files, "3.17.5")
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `current version stays current when currentVersion itself has a leading v`() {
        val files = listOf(File("BYDMate-3.17.5.apk"), File("BYDMate-3.17.4.apk"))
        val stale = UpdateApkCleanup.staleApks(files, "v3.17.5")
        assertEquals(listOf(File("BYDMate-3.17.4.apk")), stale)
    }

    @Test
    fun `non-apk and unrelated files are untouched`() {
        val files = listOf(
            File("bydmate_backup_2026-09-23.zip"),
            File("BYDMate-fids-2026-09-23.txt"),
            File("random.apk"),
            File("notes.txt"),
        )
        val stale = UpdateApkCleanup.staleApks(files, "3.17.5")
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `run deletes stale apks and returns the count`() {
        val old = tempFolder.newFile("BYDMate-3.17.4.apk")
        old.writeBytes(ByteArray(10))
        tempFolder.newFile("BYDMate-3.17.5.apk")
        tempFolder.newFile("notes.txt")

        val deleted = UpdateApkCleanup.run(tempFolder.root, "3.17.5")

        assertEquals(1, deleted)
        assertTrue(!old.exists())
        assertTrue(File(tempFolder.root, "BYDMate-3.17.5.apk").exists())
        assertTrue(File(tempFolder.root, "notes.txt").exists())
    }

    @Test
    fun `run leaves a folder with an old apk name alone`() {
        val folder = tempFolder.newFolder("BYDMate-3.17.4.apk")

        assertEquals(0, UpdateApkCleanup.run(tempFolder.root, "3.17.5"))
        assertTrue(folder.isDirectory)
    }

    @Test
    fun `run on empty dir returns zero`() {
        assertEquals(0, UpdateApkCleanup.run(tempFolder.root, "3.17.5"))
    }

    @Test
    fun `run on missing dir returns zero`() {
        val missing = File(tempFolder.root, "does-not-exist")
        assertEquals(0, UpdateApkCleanup.run(missing, "3.17.5"))
    }
}
