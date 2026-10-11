package com.bydmate.app.data.nativestack

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/** A [VehicleKindMonitor] that reports [kind] and nothing else. */
internal fun kindMonitor(kind: VehicleKind = VehicleKind.EV): VehicleKindMonitor =
    mockk { every { this@mockk.kind } returns MutableStateFlow(kind) }
