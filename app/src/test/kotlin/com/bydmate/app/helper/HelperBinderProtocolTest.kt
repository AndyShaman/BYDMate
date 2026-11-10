package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** The daemon dispatches on the transaction code alone: two verbs on one code would run the wrong one. */
class HelperBinderProtocolTest {

    @Test fun `all transaction codes are distinct`() {
        val codes = HelperBinderProtocol::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith("TX_") }
            .associate { field -> field.isAccessible = true; field.name to field.getInt(null) }
        // The enumeration must see both const and plain vals, or the check below is vacuous.
        assertTrue("found: ${codes.keys}", "TX_PING" in codes && "TX_SET_SCREEN_BACKLIGHT" in codes)
        val shared = codes.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertEquals("verbs sharing a code", emptyMap<Int, List<String>>(), shared)
    }
}
