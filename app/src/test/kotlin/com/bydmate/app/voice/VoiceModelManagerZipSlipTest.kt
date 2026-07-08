package com.bydmate.app.voice

import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Verifies the Zip Slip guard added to VoiceModelManager.unzipFlatten().
 *
 * A malicious zip entry like "model/../../escaped.txt" — when naively joined with
 * the target dir — resolves outside the target, enabling arbitrary file writes.
 * The guard must silently skip such entries while still extracting benign ones.
 */
class VoiceModelManagerZipSlipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Build a small ZIP with one benign entry and one Zip Slip entry, return the File. */
    private fun buildTestZip(zipFile: File): File {
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            // Benign entry: top-level folder "model/" then a real file underneath.
            zos.putNextEntry(ZipEntry("model/"))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("model/conf"))
            zos.write("config-data".toByteArray())
            zos.closeEntry()

            // Malicious entry: path traversal via "../../" after the top-level folder strip.
            // unzipFlatten strips the "model/" prefix, leaving "../../escaped.txt".
            zos.putNextEntry(ZipEntry("model/../../escaped.txt"))
            zos.write("malicious-payload".toByteArray())
            zos.closeEntry()
        }
        return zipFile
    }

    @Test
    fun `zip slip entry is skipped and benign entry is extracted`() {
        val targetDir = tmp.newFolder("model-out")
        val zipFile = tmp.newFile("test-model.zip")
        buildTestZip(zipFile)

        // VoiceModelManager needs a Context and OkHttpClient — neither is used by
        // unzipFlatten(), so relaxed mocks are sufficient.
        val manager = VoiceModelManager(mockk(relaxed = true), mockk(relaxed = true))
        manager.unzipFlatten(zipFile, targetDir)

        // Benign entry must be extracted inside targetDir.
        assertTrue(
            "benign entry 'conf' must exist under targetDir",
            File(targetDir, "conf").exists()
        )

        // Malicious entry must NOT be written outside targetDir.
        // The traversal "../../escaped.txt" relative to targetDir would land in tmp.root.
        // Use tmp.root directly rather than chaining nullable parentFile calls.
        val escaped = File(tmp.root, "escaped.txt")
        assertFalse(
            "Zip Slip entry must not escape targetDir (file must not exist at ${escaped.absolutePath})",
            escaped.exists()
        )

        // Also verify nothing called "escaped.txt" was created anywhere in tmp.root subtree.
        val anyEscaped = tmp.root.walkTopDown().any { it.name == "escaped.txt" && !it.absolutePath.startsWith(targetDir.absolutePath) }
        assertFalse("escaped.txt must not appear outside targetDir", anyEscaped)
    }
}
