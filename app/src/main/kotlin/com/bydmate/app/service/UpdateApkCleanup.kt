package com.bydmate.app.service

import android.util.Log
import java.io.File

/**
 * Deletes stale `BYDMate-*.apk` update files left behind in the public Download
 * folder by [UpdateChecker] (and by users downloading release assets by hand).
 * Only versions older than the installed one are removed, so Download doesn't
 * silently fill up over repeated updates; a newer APK the user has not installed
 * yet, the current one and any name we cannot parse are left alone.
 */
object UpdateApkCleanup {
    private const val TAG = "UpdateApkCleanup"
    private val NAME_PATTERN = Regex("^BYDMate-v?(\\d+(?:\\.\\d+)*)\\.apk$", RegexOption.IGNORE_CASE)
    /** Numeric head of the installed version: "3.17.5-test" compares as 3.17.5. */
    private val CURRENT_PATTERN = Regex("^v?(\\d+(?:\\.\\d+)*)")

    /** APKs whose version is strictly older than [currentVersion]; empty when it has no numeric version. */
    fun staleApks(files: List<File>, currentVersion: String): List<File> {
        val current = CURRENT_PATTERN.find(currentVersion)?.groupValues?.get(1)?.let(::parts) ?: return emptyList()
        return files.filter { file ->
            val version = NAME_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.let(::parts)
            version != null && isOlder(version, current)
        }
    }

    private fun parts(version: String): List<Int>? =
        version.split('.').map { it.toIntOrNull() ?: return null }

    /** Numeric comparison with zero padding, as in [UpdateChecker.isNewer]: 3.9 < 3.10, 3.17 == 3.17.0. */
    private fun isOlder(version: List<Int>, current: List<Int>): Boolean {
        for (i in 0 until maxOf(version.size, current.size)) {
            val a = version.getOrElse(i) { 0 }
            val b = current.getOrElse(i) { 0 }
            if (a != b) return a < b
        }
        return false
    }

    /** Deletes stale APKs in [downloadDir]; returns how many were deleted. Never throws. */
    @Suppress("TooGenericExceptionCaught")
    fun run(downloadDir: File, currentVersion: String): Int {
        return try {
            // Files only: a folder that happens to carry an APK name is not ours to delete.
            val files = downloadDir.listFiles()?.filter { it.isFile }.orEmpty()
            var deleted = 0
            for (file in staleApks(files, currentVersion)) {
                val sizeMb = file.length() / (1024.0 * 1024.0)
                if (file.delete()) {
                    deleted++
                    Log.i(TAG, "deleted ${file.name} ${"%.1f".format(sizeMb)} MB")
                } else {
                    Log.w(TAG, "failed to delete ${file.name}")
                }
            }
            Log.i(TAG, "deleted $deleted, kept current=$currentVersion")
            deleted
        } catch (e: Exception) {
            Log.w(TAG, "cleanup failed", e)
            0
        }
    }
}
