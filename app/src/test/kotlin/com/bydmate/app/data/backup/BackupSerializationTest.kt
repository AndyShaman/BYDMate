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
    fun `round-trip preserves every value type`() {
        // label to the one prefs file serialized and read back on its own
        val cases = listOf(
            "String" to mapOf<String, Any?>("key_str" to "hello world"),
            "Int" to mapOf<String, Any?>("key_int" to 42),
            "Long" to mapOf<String, Any?>("key_long" to 1_234_567_890_123L),
            // Float stored as Double then read back; compare with a small tolerance
            "Float" to mapOf<String, Any?>("key_float" to 3.14f),
            "Boolean true and false" to mapOf<String, Any?>("flag_true" to true, "flag_false" to false),
            "StringSet" to mapOf<String, Any?>("key_set" to setOf("alpha", "beta", "gamma")),
        )
        for ((label, prefs) in cases) {
            val input = mapOf("file1" to prefs)
            val result = BackupManager.deserializePrefs(BackupManager.serializePrefs(input))
            for ((key, value) in prefs) {
                val restored = result["file1"]!![key]
                if (value is Float) {
                    assertEquals("case: $label", value, restored as Float, 0.0001f)
                } else {
                    assertEquals("case: $label", value, restored)
                }
            }
        }
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
    fun `isRestorable accepts an equal or older backup schema and refuses a newer one`() {
        // label, backup schema (current is 15), expected restorable
        val cases = listOf(
            Triple("equal to current", 15, true),
            Triple("older than current", 14, true),
            Triple("newer than current", 16, false),
            Triple("significantly newer", 20, false),
        )
        for ((label, backup, expected) in cases) {
            assertEquals(
                "case: $label",
                expected,
                BackupManager.isRestorable(backupSchemaVersion = backup, currentSchemaVersion = 15),
            )
        }
    }
}
