package com.bydmate.app.helper.offreport

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

/**
 * The daemon's pending power-off reports on disk, one file each in [dir] (under /data/local/tmp,
 * shell-owned, so they survive a daemon restart and a reboot). Written to a temp file and renamed,
 * owner-only (600, the directory 700). File: [int version, UTF id, long chatId, long powerOffMs,
 * int textBytes, UTF-8 text]. At most [MAX] kept, the oldest go first.
 *
 * Only the daemon's sender thread writes and deletes; [count] may run on any thread.
 */
internal class PendingReports(private val dir: File) {

    companion object {
        const val MAX = 10
        private const val VERSION = 1
        private const val SUFFIX = ".rep"
        private const val TMP_SUFFIX = ".tmp"
        private const val MAX_TEXT_BYTES = OFF_REPORT_MAX_TEXT * 4
        private const val TAG = "OffReport"
    }

    /** Writes [report]; true once it is on disk. Past [MAX] the oldest files are dropped. */
    fun save(report: PendingReport): Boolean {
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
            trim()
            true
        } catch (e: IOException) {
            Log.w(TAG, "offreport: pending save failed id=${report.id}: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Oldest power-off first. An unreadable file is dropped (logged) instead of blocking the rest. */
    fun list(): List<PendingReport> = files().mapNotNull { file ->
        read(file) ?: run {
            file.delete()
            Log.w(TAG, "offreport: pending dropped id=? reason=unreadable")
            null
        }
    }.sortedBy { it.powerOffMs }

    fun delete(id: String) {
        files().filter { it.name.endsWith("-${safe(id)}$SUFFIX") }.forEach { it.delete() }
    }

    /** Every pending report; returns their ids for the log. */
    fun deleteAll(): List<String> {
        val ids = list().map { it.id }
        dir.listFiles()?.forEach { it.delete() }
        return ids
    }

    fun count(): Int = files().size

    private fun files(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) }?.sortedBy { it.name }.orEmpty()

    /** Past [MAX], the oldest power-offs go. */
    private fun trim() {
        val all = list()
        all.take((all.size - MAX).coerceAtLeast(0)).forEach {
            delete(it.id)
            Log.i(TAG, "offreport: pending dropped id=${it.id} reason=cap")
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

    private fun read(file: File): PendingReport? = try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != VERSION) return null
            val id = input.readUTF().takeIf { it.isNotEmpty() && it.length <= OFF_REPORT_MAX_ID } ?: return null
            val chatId = input.readLong()
            val powerOffMs = input.readLong()
            val size = input.readInt()
            if (size !in 1..MAX_TEXT_BYTES) return null
            val text = ByteArray(size).also { input.readFully(it) }
            PendingReport(id, chatId, powerOffMs, String(text, Charsets.UTF_8))
        }
    } catch (@Suppress("SwallowedException") e: IOException) { // cut short or unreadable: the caller drops it
        null
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
