package com.bydmate.app.voice

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.ActionDispatcher.BlockReason
import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline «открой / закрой передний багажник»: the front commands, held by the speed gate. */
class NluParserFrontTrunkTest {

    @Test fun `front trunk phrases parse to the front commands and the gate holds them`() {
        val cmd = NluParser.parse("открой передний багажник")
        assertEquals(ParseResult.Command("前备箱打开"), cmd)
        val s = (cmd as ParseResult.Command).command!!
        assertTrue(ActionDispatcher.isFrontTrunkOpenCommand(s))
        assertEquals(BlockReason.FrunkMoving(5), ActionDispatcher.safetyBlockReason(s, diParsData(speed = 5)))
        assertNull(ActionDispatcher.safetyBlockReason(s, diParsData(speed = 0)))
        assertEquals(BlockReason.FrunkSpeedUnknown, ActionDispatcher.safetyBlockReason(s, null))
    }

    @Test fun `closing the front trunk is not gated`() {
        val cmd = NluParser.parse("закрой передний багажник")
        assertEquals(ParseResult.Command("前备箱关闭"), cmd)
        val s = (cmd as ParseResult.Command).command!!
        assertFalse(ActionDispatcher.isFrontTrunkOpenCommand(s))
        assertNull(ActionDispatcher.safetyBlockReason(s, diParsData(speed = 50)))
    }

    @Test fun `the bare trunk is still the rear one`() {
        assertEquals(ParseResult.Command("开后备箱"), NluParser.parse("открой багажник"))
        assertEquals(ParseResult.Command("关后备箱"), NluParser.parse("закрой багажник"))
    }
}
