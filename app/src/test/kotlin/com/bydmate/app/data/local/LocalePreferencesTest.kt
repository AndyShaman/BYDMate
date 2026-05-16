package com.bydmate.app.data.local

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalePreferencesTest {

    private fun mockSetup(initial: Map<String, Any?>): Pair<Context, SharedPreferences> {
        val store = initial.toMutableMap()
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putString(any(), any()) } answers {
            store[firstArg<String>()] = secondArg<String?>(); editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            store[firstArg<String>()] = secondArg<Boolean>(); editor
        }
        every { editor.remove(any()) } returns editor
        every { editor.clear() } returns editor
        every { editor.apply() } just Runs
        every { editor.commit() } returns true
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } answers {
            store[firstArg<String>()] as String? ?: secondArg<String?>()
        }
        every { prefs.contains(any()) } answers { store.containsKey(firstArg<String>()) }
        every { prefs.edit() } returns editor
        val ctx = mockk<Context>()
        every { ctx.getSharedPreferences("bydmate_locale", Context.MODE_PRIVATE) } returns prefs
        return ctx to prefs
    }

    @Test
    fun `getLanguage returns null when unset`() {
        val (ctx, _) = mockSetup(emptyMap())
        assertNull(LocalePreferences(ctx).getLanguage())
    }

    @Test
    fun `getLanguage returns stored value`() {
        val (ctx, _) = mockSetup(mapOf("app_language" to "en"))
        assertEquals("en", LocalePreferences(ctx).getLanguage())
    }

    @Test
    fun `setLanguage writes value`() {
        val (ctx, prefs) = mockSetup(emptyMap())
        val editor = prefs.edit()  // capture the editor
        LocalePreferences(ctx).setLanguage("en")
        // Verify write happened synchronously (commit, not apply)
        verify { editor.putString("app_language", "en") }
        verify { editor.commit() }
    }

    @Test
    fun `setupCompletedMirror flag round-trip`() {
        val (ctx, _) = mockSetup(emptyMap())
        val lp = LocalePreferences(ctx)
        assertFalse(lp.isSetupCompletedMirror())
        lp.markSetupCompletedMirror()
        assertTrue(lp.isSetupCompletedMirror())
    }
}
