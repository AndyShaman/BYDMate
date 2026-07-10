package com.bydmate.app.ui.automation

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationVoiceActionFactoryTest {
    private val context = mockk<Context> { every { getString(any()) } returns "Название" }

    @Test fun `speak factory produces kind speak with empty text payload`() {
        val a = newSpeakAction(context)
        assertEquals("speak", a.kind)
        assertEquals("", a.speakText())
    }

    @Test fun `speak payload round-trips`() {
        val a = newSpeakAction(context).withSpeakText("Позвони жене")
        assertEquals("Позвони жене", a.speakText())
    }

    @Test fun `agent query factory produces kind agent_query with empty prompt`() {
        val a = newAgentQueryAction(context)
        assertEquals("agent_query", a.kind)
        assertEquals("", a.agentPrompt())
    }

    @Test fun `agent prompt payload round-trips`() {
        val a = newAgentQueryAction(context).withAgentPrompt("расскажи сводку погоды")
        assertEquals("расскажи сводку погоды", a.agentPrompt())
    }
}
