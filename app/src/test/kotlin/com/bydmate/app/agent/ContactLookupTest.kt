package com.bydmate.app.agent

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ContactLookupTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun hasPermission_false_when_not_granted() {
        val lookup = ContactLookup(context)
        assertFalse(lookup.hasPermission())
    }

    @Test fun hasPermission_true_when_granted() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        val lookup = ContactLookup(context)
        assertTrue(lookup.hasPermission())
    }

    // findByName goes through the resolver seam — ContentResolver itself is never
    // touched in a JVM test.
    @Test fun findByName_delegates_to_resolver_seam() = runTest {
        val lookup = ContactLookup(context)
        lookup.resolver = { query -> listOf(ContactLookup.Match("Найдено: $query", "+70000000000")) }
        val matches = lookup.findByName("Мама")
        assertEquals(1, matches.size)
        assertEquals("Найдено: Мама", matches[0].name)
    }

    @Test fun findByName_empty_resolver_result_is_empty_list() = runTest {
        val lookup = ContactLookup(context)
        lookup.resolver = { emptyList() }
        assertTrue(lookup.findByName("Никто").isEmpty())
    }

    // filterRows is the pure per-row logic queryContacts feeds from the cursor — no SQL
    // selection is applied upstream, so this is where Cyrillic case-insensitivity actually
    // has to hold.
    @Test fun filterRows_matches_cyrillic_case_insensitively() {
        val lookup = ContactLookup(context)
        val rows = sequenceOf("Маша Петрова" to "+70000000001", "Иван Сидоров" to "+70000000002")
        val matches = lookup.filterRows(rows, "маша")
        assertEquals(1, matches.size)
        assertEquals("Маша Петрова", matches[0].name)
    }

    @Test fun filterRows_dedupes_by_name_keeping_first_phone() {
        val lookup = ContactLookup(context)
        val rows = sequenceOf("Мама" to "+70000000001", "Мама" to "+70000000002")
        val matches = lookup.filterRows(rows, "Мама")
        assertEquals(1, matches.size)
        assertEquals("+70000000001", matches[0].phone)
    }

    @Test fun filterRows_caps_at_ten_matches() {
        val lookup = ContactLookup(context)
        val rows = (1..20).asSequence().map { "Контакт $it" to "+7000000000$it" }
        val matches = lookup.filterRows(rows, "Контакт")
        assertEquals(10, matches.size)
    }

    @Test fun filterRows_skips_null_name_or_phone() {
        val lookup = ContactLookup(context)
        val rows = sequenceOf(null to "+70000000001", "Аноним" to null, "Мама" to "+70000000002")
        val matches = lookup.filterRows(rows, "")
        assertEquals(1, matches.size)
        assertEquals("Мама", matches[0].name)
    }
}
