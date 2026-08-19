package com.bydmate.app.di

import com.bydmate.app.agent.AgentBackend
import com.bydmate.app.agent.DriverMemory
import com.bydmate.app.agent.LlmAgentBackend
import com.bydmate.app.voice.VoiceGate
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {
    @Binds abstract fun bindAgentBackend(impl: LlmAgentBackend): AgentBackend

    companion object {
        // Terse-mode signal for AgentOrchestrator: same speed source ActionDispatcher's
        // >80 km/h window-open gate uses. No new fid read — fail-soft to false on unknown speed.
        @Provides
        fun provideIsMoving(gate: VoiceGate): () -> Boolean =
            { (gate.vehicleSnapshot()?.speed ?: 0) > 0 }

        // Driver facts for the system prompt. Read lazily per turn so a remember_fact call
        // shows up in the very next prompt without rebuilding the orchestrator.
        @Provides
        @Singleton
        fun provideDriverMemoryBlock(memory: DriverMemory): () -> String =
            { memory.promptBlock() }
    }
}
