package com.bydmate.app.ui.automation

import com.bydmate.app.data.local.entity.TriggerDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class ButtonPressTriggerTest {

    @Test fun `factory builds button_press TriggerDef with neutral placeholders`() {
        val t = newButtonPressTrigger(3)
        assertEquals("button_press", t.kind)
        assertEquals("3", t.value)
        assertEquals("button", t.param)
        assertEquals("==", t.operator)
        assertEquals("", t.chineseName)
        assertEquals("Кнопка 3", t.displayName)
        assertNull(t.placeId)
        assertNull(t.placeName)
    }

    @Test fun `button_press survives toJson then fromJson round-trip`() {
        val original = newButtonPressTrigger(5)
        val json: JSONObject = original.toJson()
        val restored = TriggerDef.fromJson(json)
        assertEquals(original, restored)
    }

    @Test fun `button_press survives list round-trip via column JSON`() {
        val list = listOf(newButtonPressTrigger(1), newButtonPressTrigger(6))
        val columnJson = TriggerDef.listToJson(list)
        val restored = TriggerDef.listFromJson(columnJson)
        assertEquals(list, restored)
    }
}
