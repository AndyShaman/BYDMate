package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** The daemon dispatches on the transaction code alone: two verbs on one code would run the wrong one. */
class HelperBinderProtocolTest {

    private fun declaredCodes(): Map<String, Int> = HelperBinderProtocol::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith("TX_") }
        .associate { field -> field.isAccessible = true; field.name to field.getInt(null) }

    @Test fun `all transaction codes are distinct`() {
        val codes = declaredCodes()
        // The enumeration must see both const and plain vals, or the check below is vacuous.
        assertTrue("found: ${codes.keys}", "TX_PING" in codes && "TX_SET_SCREEN_BACKLIGHT" in codes)
        val shared = codes.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertEquals("verbs sharing a code", emptyMap<Int, List<String>>(), shared)
    }

    /**
     * A daemon started by an older app build can outlive the update and keeps dispatching on the
     * numbers it was built with, so a verb that moves to another number runs a different operation
     * on the car. The numbers below are the ones shipped in 4.3.1: a new verb is appended with the
     * next free number, an existing one never changes (4 and 5 were retired and stay unused).
     */
    @Test fun `every verb keeps the wire number it shipped with`() {
        assertEquals(WIRE_NUMBERS.toSortedMap(), declaredCodes().toSortedMap())
    }
}

private val WIRE_NUMBERS: Map<String, Int> by lazy {
    mapOf(
        "TX_PING" to 1, "TX_READ" to 2, "TX_WRITE" to 3,
        "TX_CREATE_VIRTUAL_DISPLAY" to 6, "TX_RELEASE_VIRTUAL_DISPLAY" to 7, "TX_LAUNCH_APP" to 8,
        "TX_GET_TASK_ID" to 9, "TX_MOVE_TASK_TO_DISPLAY" to 10, "TX_SET_TASK_BOUNDS" to 11,
        "TX_SET_FOCUSED_TASK" to 12, "TX_SET_TASK_WINDOWING_MODE" to 13, "TX_GRANT_OVERLAY_PERMISSION" to 14,
        "TX_LAUNCH_AND_FORCE" to 15, "TX_ENABLE_ACCESSIBILITY" to 16, "TX_PUT_GLOBAL_SETTING" to 17,
        "TX_SET_APP_HIDDEN" to 18, "TX_ENABLE_NOTIFICATION_LISTENER" to 19, "TX_SET_CLUSTER_MODE" to 20,
        "TX_READ_BATCH" to 21, "TX_LAUNCH_FREEFORM" to 22, "TX_SET_DISPLAY_DENSITY" to 23,
        "TX_GRANT_READ_LOGS" to 24, "TX_SET_HOTSPOT" to 25, "TX_GET_TASK_STATE" to 26,
        "TX_APP_SPLIT_SUPPORTED" to 27, "TX_FORCE_STOP" to 28, "TX_GET_VERSION" to 29,
        "TX_GET_TOP_PACKAGE" to 30, "TX_RAISE_FREEFORM_TASK" to 31, "TX_DUMP_FIDS" to 32,
        "TX_GET_TOP_TASK" to 33, "TX_SPLIT37_ENTER" to 34, "TX_SPLIT37_AREA_INFO" to 35,
        "TX_SPLIT37_MOVE_TASK" to 36, "TX_SPLIT37_TASK_AREA" to 37, "TX_SPLIT37_SWAP" to 38,
        "TX_SPLIT37_CHANGE_MODE" to 39, "TX_RECOVER_ACCESSIBILITY" to 40, "TX_CLUSTER_DISPLAY_DIAG" to 41,
        "TX_LIST_DISPLAYS" to 42, "TX_CLUSTER_WM_DIAG" to 43, "TX_REGISTER_CLIENT" to 44,
        "TX_PUSH_SUBSCRIBE" to 45, "TX_PUSH_UNSUBSCRIBE" to 46, "TX_PUSH_STATUS" to 47,
        "TX_PUSH_EVENT" to 48, "TX_REC_START" to 49, "TX_REC_STOP" to 50,
        "TX_REC_STATUS" to 51, "TX_GET_GLOBAL_SETTING" to 52, "TX_OFFREPORT_ARM" to 53,
        "TX_OFFREPORT_DISARM" to 54, "TX_OFFREPORT_STATUS" to 55, "TX_HUD_NAVI_STATUS" to 56,
        "TX_WRITE_BUFFER" to 57, "TX_HUD_SDK" to 58, "TX_SET_SCREEN_BACKLIGHT" to 59,
        "TX_SPLIT37_ROOT_STATE" to 60, "TX_SET_AUTO_VOICE" to 61, "TX_DTC_SCAN_START" to 62,
        "TX_DTC_SCAN_STATUS" to 63, "TX_DTC_ERASE_START" to 64, "TX_DISALLOW_NOTIFICATION_LISTENER" to 65,
    )
}
