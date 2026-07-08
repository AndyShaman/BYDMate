package com.bydmate.app.ui.debug

import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val orchestrator = mockk<AgentOrchestrator>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = AgentChatViewModel(orchestrator)

    @Test fun `answer produces user and agent lines, busy false at the end`() = runTest(dispatcher) {
        coEvery { orchestrator.ask("какой заряд") } returns AgentResult.Answer("80%")
        val viewModel = vm()

        viewModel.send("какой заряд")
        advanceUntilIdle()

        val lines = viewModel.uiState.value.lines
        assertEquals(2, lines.size)
        assertEquals(AgentChatViewModel.ChatLine(true, "какой заряд"), lines[0])
        assertEquals(AgentChatViewModel.ChatLine(false, "80%"), lines[1])
        assertFalse(viewModel.uiState.value.busy)
    }

    @Test fun `busy is true immediately after send, false after advanceUntilIdle`() = runTest(dispatcher) {
        coEvery { orchestrator.ask(any()) } returns AgentResult.Answer("80%")
        val viewModel = vm()

        viewModel.send("какой заряд")
        assertTrue(viewModel.uiState.value.busy)

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.busy)
    }

    @Test fun `Disabled maps to the settings hint line`() = runTest(dispatcher) {
        coEvery { orchestrator.ask(any()) } returns AgentResult.Disabled
        val viewModel = vm()

        viewModel.send("привет")
        advanceUntilIdle()

        assertEquals(
            "Агент выключен или не настроен (Настройки → Агент)",
            viewModel.uiState.value.lines.last().text,
        )
    }

    @Test fun `Error maps to Ошибка prefix with message`() = runTest(dispatcher) {
        coEvery { orchestrator.ask(any()) } returns AgentResult.Error("нет сети")
        val viewModel = vm()

        viewModel.send("привет")
        advanceUntilIdle()

        assertEquals("Ошибка: нет сети", viewModel.uiState.value.lines.last().text)
    }

    @Test fun `blank input is ignored, orchestrator never called`() = runTest(dispatcher) {
        val viewModel = vm()

        viewModel.send("  ")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.lines.isEmpty())
        coVerify(exactly = 0) { orchestrator.ask(any()) }
    }

    @Test fun `send while busy is ignored until the first completes`() = runTest(dispatcher) {
        coEvery { orchestrator.ask("первый") } returns AgentResult.Answer("ответ1")
        val viewModel = vm()

        viewModel.send("первый")
        viewModel.send("второй") // ignored while busy == true

        advanceUntilIdle()

        coVerify(exactly = 1) { orchestrator.ask(any()) }
        coVerify(exactly = 0) { orchestrator.ask("второй") }
        assertEquals(2, viewModel.uiState.value.lines.size)
    }
}
