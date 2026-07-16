package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.hud.HudController
import com.bydmate.app.voice.VoiceController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bridges the @Singleton HelperClient/HelperBootstrap/VoiceController/HudController into
 * SteeringWheelKeyService and SettingsScreen composables (framework-instantiated, not Hilt).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClusterEntryPoint {
    fun helperClient(): HelperClient
    fun helperBootstrap(): HelperBootstrap
    fun voiceController(): VoiceController
    fun hudController(): HudController
}
