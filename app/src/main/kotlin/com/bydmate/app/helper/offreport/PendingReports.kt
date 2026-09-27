package com.bydmate.app.helper.offreport

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A power-off report the daemon could not deliver yet: [text] has its time filled in. Never the
 * token: a pending report goes out with the bot of the report armed at the time. toString never
 * shows the chat or the text.
 */
internal class PendingReport(val id: String, val chatId: Long, val powerOffMs: Long, val text: String) {
    override fun toString(): String = "PendingReport(id=$id, len=${text.length})"
}

/** What [PendingReports.deleteAll] removed; [complete] false when any file could not be deleted. */
internal class Cleared(val ids: List<String>, val complete: Boolean)

/**
 * The daemon's pending power-off reports on disk, one file each in [dir] (under /data/local/tmp,
 * shell-owned, so they survive a daemon restart and a reboot). Written to a temp file, synced and
 * renamed, owner-only (600, the directory 700); the directory is synced after every rename and
 * delete ([syncDir], best effort). File: [int version, UTF id, long chatId, long powerOffMs,
 * int textBytes, UTF-8 text]. At most [MAX] kept, the oldest go first.
 *
 * A temp file left by a daemon that died mid-save is recovered on the next scan when it reads back
 * whole, and deleted otherwise: a death before the temp file was fully written loses that report.
 *
 * Every method holds this store's monitor: a save on the vendor thread, a scan or delete on the
 * sender and the disarm's delete on a binder thread never see each other's half-done files. Disk
 * only, never the network, runs under it.
 */
@Suppress("TooManyFunctions") // save, scan, recovery, deletes, sync and the codec of one file format
internal class PendingReports(
    private val dir: File,
    private val syncDir: (File) -> Unit = ::fsyncDir,
) {

    companion object {
        const val MAX = 10
        private const val VERSION = 1
        private const val SUFFIX = ".rep"
        private const val TMP_SUFFIX = ".tmp"
        private const val MAX_TEXT_BYTES = OFF_REPORT_MAX_TEXT * 4
        private const val TAG = "OffReport"
    }

    private var syncFailLogged = false

    /**
     * Writes [report] unless [wanted], asked under this store's monitor right before the write, says
     * no; true once it is on disk. Past [MAX] the oldest files are dropped.
     */
    @Synchronized
    fun save(report: PendingReport, wanted: () -> Boolean = { true }): Boolean {
        if (!wanted()) return false
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs")
            ownerOnly(dir, directory = true)
            val name = "${report.powerOffMs}-${safe(report.id)}"
            val tmp = File(dir, name + TMP_SUFFIX)
            FileOutputStream(tmp).use { out ->
                ownerOnly(tmp, directory = false)
                out.write(encode(report))
                out.fd.sync()
            }
            if (!tmp.renameTo(File(dir, name + SUFFIX))) throw IOException("rename")
            syncDirQuietly()
            trim()
            true
        } catch (e: IOException) {
            Log.w(TAG, "offreport: pending save failed id=${report.id}: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Oldest power-off first. An unreadable file is dropped (logged) instead of blocking the rest. */
    @Synchronized
    fun list(): List<PendingReport> {
        recover()
        var dropped = false
        val reports = files().mapNotNull { file ->
            read(file) ?: run {
                file.delete()
                dropped = true
                Log.w(TAG, "offreport: pending dropped id=? reason=unreadable")
                null
            }
        }
        if (dropped) syncDirQuietly()
        return reports.sortedBy { it.powerOffMs }
    }

    @Synchronized
    fun delete(id: String) {
        val gone = files().filter { it.name.endsWith("-${safe(id)}$SUFFIX") }
        gone.forEach { it.delete() }
        if (gone.isNotEmpty()) syncDirQuietly()
    }

    /** Every pending report and temp file, each delete checked; the ids go to the log. */
    @Synchronized
    fun deleteAll(): Cleared {
        val ids = list().map { it.id }
        val entries = if (dir.exists()) dir.listFiles() ?: return Cleared(ids, complete = false) else emptyArray()
        var complete = true
        entries.forEach { if (!it.delete()) complete = false }
        if (entries.isNotEmpty()) syncDirQuietly()
        return Cleared(ids, complete)
    }

    @Synchronized
    fun count(): Int = files().size

    private fun files(suffix: String = SUFFIX): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) }?.sortedBy { it.name }.orEmpty()

    /** Temp files a dead daemon left: whole ones become reports again, the rest go. */
    private fun recover() {
        val temps = files(TMP_SUFFIX)
        if (temps.isEmpty()) return
        for (tmp in temps) {
            val name = tmp.name.removeSuffix(TMP_SUFFIX)
            val id = name.substringAfter('-')
            if (read(tmp) == null) {
                tmp.delete()
                Log.i(TAG, "offreport: pending dropped id=$id reason=partial")
            } else if (tmp.renameTo(File(dir, name + SUFFIX))) {
                Log.i(TAG, "offreport: pending recovered id=$id")
            }
        }
        syncDirQuietly()
    }

    /** Past [MAX], the oldest power-offs go. */
    private fun trim() {
        val all = list()
        all.take((all.size - MAX).coerceAtLeast(0)).forEach {
            delete(it.id)
            Log.i(TAG, "offreport: pending dropped id=${it.id} reason=cap")
        }
    }

    /** A rename or delete survives a power cut only once the directory itself is synced. */
    @Suppress("TooGenericExceptionCaught") // ErrnoException or whatever the platform throws: best effort
    private fun syncDirQuietly() {
        try {
            syncDir(dir)
        } catch (e: Exception) {
            if (!syncFailLogged) {
                syncFailLogged = true
                Log.w(TAG, "offreport: pending dir sync failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun encode(report: PendingReport): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val text = report.text.toByteArray(Charsets.UTF_8)
            out.writeInt(VERSION)
            out.writeUTF(report.id)
            out.writeLong(report.chatId)
            out.writeLong(report.powerOffMs)
            out.writeInt(text.size)
            out.write(text)
        }
        return bytes.toByteArray()
    }

    /** Null unless the whole file is one report: version, lengths and no trailing bytes. */
    private fun read(file: File): PendingReport? = try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != VERSION) return null
            val id = input.readUTF().takeIf { it.isNotEmpty() && it.length <= OFF_REPORT_MAX_ID } ?: return null
            val chatId = input.readLong()
            val powerOffMs = input.readLong()
            val size = input.readInt()
            if (size !in 1..MAX_TEXT_BYTES) return null
            val text = ByteArray(size).also { input.readFully(it) }
            if (input.read() != -1) return null
            PendingReport(id, chatId, powerOffMs, String(text, Charsets.UTF_8))
        }
    } catch (@Suppress("SwallowedException") e: IOException) { // cut short or unreadable: the caller drops it
        null
    }
}

/** fsync of the directory itself (the daemon runs under app_process, android.system.Os is there). */
private fun fsyncDir(dir: File) {
    val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
}

/** rw------- (rwx------ for the directory): the text carries the car's place and state. */
private fun ownerOnly(file: File, directory: Boolean) {
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
    if (directory) file.setExecutable(true, true)
}

/** Report ids are 8 hex chars; anything else never reaches a path unescaped. */
private fun safe(id: String): String = id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
