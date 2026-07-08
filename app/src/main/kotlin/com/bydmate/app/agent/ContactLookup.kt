package com.bydmate.app.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local contact-book lookup for the call_contact voice tool. Reads ONLY the
 * contacts matching a requested name — the phone book as a whole is never
 * queried in bulk, and phone numbers never leave [Match] into the LLM-facing
 * tool response (AgentTools strips them before responding on ambiguous matches).
 */
@Singleton
class ContactLookup @Inject constructor(@ApplicationContext private val context: Context) {

    data class Match(val name: String, val phone: String)

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    /** Case-insensitive partial match on display name; ≤10 matches. ContentResolver.query
     *  runs synchronously, so this hops off the caller's thread (same pattern as WeatherClient). */
    suspend fun findByName(query: String): List<Match> = withContext(Dispatchers.IO) { resolver(query) }

    // internal query seam for JVM tests (ContentResolver не мокается напрямую):
    internal var resolver: (String) -> List<Match> = { q -> queryContacts(q) }

    // No SQL selection: SQLite's LIKE is case-sensitive for non-ASCII (Cyrillic), so a
    // "DISPLAY_NAME LIKE ?" prefilter would silently DROP matching rows before Kotlin ever
    // sees them. Contact lists on a head unit are small, so a full scan + Kotlin-side
    // case-insensitive filter is both correct and cheap.
    private fun queryContacts(query: String): List<Match> {
        val rows = mutableListOf<Pair<String?, String?>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                rows += cursor.getString(nameIdx) to cursor.getString(numberIdx)
            }
        }
        return filterRows(rows.asSequence(), query)
    }

    /** Pure row filter: case-insensitive contains match, skips null name/phone, dedupes
     *  by display name (first phone wins), caps at [MAX_MATCHES]. Unit-testable without
     *  a ContentResolver. */
    internal fun filterRows(rows: Sequence<Pair<String?, String?>>, query: String): List<Match> {
        val matches = LinkedHashMap<String, String>()
        for ((name, phone) in rows) {
            if (matches.size >= MAX_MATCHES) break
            if (name == null || phone == null) continue
            if (!name.contains(query, ignoreCase = true)) continue
            matches.putIfAbsent(name, phone)
        }
        return matches.map { (name, phone) -> Match(name, phone) }
    }

    companion object {
        private const val MAX_MATCHES = 10
    }
}
