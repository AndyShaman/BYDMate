package com.bydmate.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for BackupManager serialization helpers.
 * No Android context required — all helpers are in the companion object.
 */
class BackupSerializationTest {

    // -------------------------------------------------------------------------
    // serializePrefs / deserializePrefs round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `round-trip preserves String values`() {
        val input = mapOf("file1" to mapOf<String, Any?>("key_str" to "hello world"))
        val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))
        assertEquals("hello world", result["file1"]!!["key_str"])
    }

    @Test
    fun `round-trip preserves Int values`() {
        val input = mapOf("file1" to mapOf<String, Any?>("key_int" to 42))
        val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))
        assertEquals(42, result["file1"]!!["key_int"])
    }

    @Test
    fun `round-trip preserves Long values`() {
        val input = mapOf("file1" to mapOf<String, Any?>("key_long" to 1_234_567_890_123L))
        val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))
        assertEquals(1_234_567_890_123L, result["file1"]!!["key_long"])
    }

    @Test
    fun `round-trip preserves Float values`() {
        val input = mapOf("file1" to mapOf<String, Any?>("key_float" to 3.14f))
        val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))
        // Float stored as Double then read back; compare with a small tolerance
        val restored = result["file1"]!!["key_float"] as Float
        assertEquals(3.14f, restored, 0.0001f)
    }

    @Test
    fun `round-trip preserves Boolean true and false`() {
        val input = mapOf(
            "file1" to mapOf<String, Any?>(
                "flag_true" to true,
                "flag_false" to false,
            )
        )
        val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))
        assertEquals(true, result["file1"]!!["flag_true"])
        assertEquals(false, result["file1"]!!["flag_false"])
    }

    @Test
    fun `round-trip preserves StringSet values`() {
        val original: Set<String> = setOf("alpha", "beta", "gamma")
        val input = mapOf("file1" to mapOf<String, Any?>("key_set" to original))
        val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))
        @Suppress("UNCHECKED_CAST")
        val restored = result["file1"]!!["key_set"] as Set<String>
        assertEquals(original, restored)
    }

    @Test
    fun `round-trip across multiple prefs files`() {
        val input = mapOf(
            "bydmate_locale" to mapOf<String, Any?>("lang" to "ru"),
            "cluster_projection" to mapOf<String, Any?>(
                "trigger_keycode" to 351,
                "enabled" to true,
            ),
            "bydmate_range_prefs" to mapOf<String, Any?>(
                "soc_100_km" to 450.5f,
            ),
        )
        val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))

        assertEquals("ru", result["bydmate_locale"]!!["lang"])
        assertEquals(351, result["cluster_projection"]!!["trigger_keycode"])
        assertEquals(true, result["cluster_projection"]!!["enabled"])
        val soc = result["bydmate_range_prefs"]!!["soc_100_km"] as Float
        assertEquals(450.5f, soc, 0.01f)
    }

    @Test
    fun `null values are skipped during serialization`() {
        val input = mapOf(
            "file1" to mapOf<String, Any?>(
                "present" to "yes",
                "absent" to null,
            )
        )
        val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))
        assertEquals("yes", result["file1"]!!["present"])
        assertFalse("null key should be absent", result["file1"]!!.containsKey("absent"))
    }

    @Test
    fun `empty prefs map round-trips to empty`() {
        val input = emptyMap<String, Map<String, Any?>>()
        val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `deserialize skips entries with an unknown type tag`() {
        // A forward-incompatible backup may carry a type tag this version cannot decode.
        // Such keys must be dropped, not crash, and known keys in the same file survive.
        val json = """
            {"file1":{
                "good":{"t":"String","v":"ok"},
                "weird":{"t":"Decimal","v":1.5}
            }}
        """.trimIndent()
        val result = BackupManager.deserializePrefs(json)
        assertEquals("ok", result["file1"]!!["good"])
        assertFalse("unknown type tag must be skipped", result["file1"]!!.containsKey("weird"))
    }

    // -------------------------------------------------------------------------
    // isRestorable boundary tests
    // -------------------------------------------------------------------------

    @Test
    fun `isRestorable returns true when backup schema equals current`() {
        assertTrue(BackupManager.isRestorable(backupSchemaVersion = 15, currentSchemaVersion = 15))
    }

    @Test
    fun `isRestorable returns true when backup schema is older than current`() {
        assertTrue(BackupManager.isRestorable(backupSchemaVersion = 14, currentSchemaVersion = 15))
    }

    @Test
    fun `isRestorable returns false when backup schema is newer than current`() {
        assertFalse(BackupManager.isRestorable(backupSchemaVersion = 16, currentSchemaVersion = 15))
    }

    @Test
    fun `isRestorable returns false for significantly newer backup`() {
        assertFalse(BackupManager.isRestorable(backupSchemaVersion = 20, currentSchemaVersion = 15))
    }
}
