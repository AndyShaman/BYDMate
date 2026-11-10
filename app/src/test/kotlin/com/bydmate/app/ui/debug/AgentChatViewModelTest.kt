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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val orchestrator = mockk<AgentOrchestrator>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = AgentChatViewModel(orchestrator)

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
