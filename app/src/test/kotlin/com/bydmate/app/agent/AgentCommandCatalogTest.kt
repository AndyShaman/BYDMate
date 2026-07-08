package com.bydmate.app.agent

import com.bydmate.app.voice.DeviceSlot
import com.bydmate.app.voice.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentCommandCatalogTest {

    @Test fun `covers every fixed VoiceCatalog command string`() {
        val voiceFixed = VoiceCatalog.ALL.filter { it.value == null }.map { it.command(null) }.toSet()
        val catalogFixed = AgentCommandCatalog.ALL.filter { it.value == null }.map { it.chinese(null) }.toSet()
        // One-directional: the agent catalog must cover everything the legacy Vosk
        // NLU fast-path can do, but it is allowed to know MORE (fridge, front trunk,
        // etc.) — commands with no VoiceCatalog/NLU surface at all. Asserting exact
        // equality here would break every time the agent catalog grows past NLU.
        assertEquals(emptySet<String>(), voiceFixed - catalogFixed)
    }

    @Test fun `ac_set_temp range and template match VoiceCatalog AC_TEMP`() {
        val voiceSpec = VoiceCatalog.ALL.first { it.device == DeviceSlot.AC_TEMP }
        val catalogCmd = AgentCommandCatalog.ALL.first { it.id == "ac_set_temp" }
        assertEquals(voiceSpec.value!!.min, catalogCmd.value!!.first)
        assertEquals(voiceSpec.value!!.max, catalogCmd.value!!.last)
        assertEquals(voiceSpec.command(22), catalogCmd.chinese(22))
    }

    @Test fun `resolve returns chinese string for fixed command`() {
        assertEquals("车窗关闭", AgentCommandCatalog.resolve("windows_close_all", null))
    }

    @Test fun `resolve applies value for ranged command`() {
        assertEquals("设置温度22", AgentCommandCatalog.resolve("ac_set_temp", 22))
    }

    @Test fun `resolve rejects out-of-range value`() {
        assertNull(AgentCommandCatalog.resolve("ac_set_temp", 40))
    }

    @Test fun `resolve rejects unknown id`() {
        assertNull(AgentCommandCatalog.resolve("nonsense", null))
    }

    @Test fun `idsDoc has no Chinese and lists all ids`() {
        val doc = AgentCommandCatalog.idsDoc()
        assertEquals(AgentCommandCatalog.ALL.size, doc.lines().size)
        assertEquals(false, doc.any { it.code > 0x2E80 })
    }
}
