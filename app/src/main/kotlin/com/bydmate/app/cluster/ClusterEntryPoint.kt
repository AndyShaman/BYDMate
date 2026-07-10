package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.voice.VoiceController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bridges the @Singleton HelperClient/HelperBootstrap/VoiceController into SteeringWheelKeyService,
 * which is an AccessibilityService (instantiated by the framework, not Hilt).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClusterEntryPoint {
    fun helperClient(): HelperClient
    fun helperBootstrap(): HelperBootstrap
    fun voiceController(): VoiceController
}
