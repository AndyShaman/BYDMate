package com.bydmate.app.data.local

import android.content.Context
import com.bydmate.app.data.local.dao.CounterSnapshotDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.nativestack.kindMonitor
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.OdometerMarks
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.data.trips.CounterSnapshotWriter
import com.bydmate.app.domain.cost.CostCalculator
import io.mockk.mockk

/**
 * Builds [HistoryImporter] for tests: every collaborator defaults to a relaxed mock (an EV
 * [kindMonitor] for the vehicle kind), a test passes by name only the ones it stubs, seeds,
 * reads or verifies. One parameter per constructor argument of [HistoryImporter], hence the
 * long list.
 */
@Suppress("LongParameterList")
fun historyImporter(
    context: Context = mockk(relaxed = true),
    energyDataReader: EnergyDataReader = mockk(relaxed = true),
    tripRepository: TripRepository = mockk(relaxed = true),
    tripDao: TripDao = mockk(relaxed = true),
    tripPointDao: TripPointDao = mockk(relaxed = true),
    idleDrainDao: IdleDrainDao = mockk(relaxed = true),
    settingsRepository: SettingsRepository = mockk(relaxed = true),
    lastSessionRepository: LastSessionRepository = mockk(relaxed = true),
    tripTombstoneDao: TripTombstoneDao = mockk(relaxed = true),
    costCalculator: CostCalculator = mockk(relaxed = true),
    odometerMarks: OdometerMarks = mockk(relaxed = true),
    vehicleKind: VehicleKindMonitor = kindMonitor(),
    counterSnapshotDao: CounterSnapshotDao = mockk(relaxed = true),
    counterSnapshotWriter: CounterSnapshotWriter = mockk(relaxed = true),
    lastStateDao: LastStateDao = mockk(relaxed = true),
) = HistoryImporter(
    context, energyDataReader, tripRepository, tripDao, tripPointDao, idleDrainDao,
    settingsRepository, lastSessionRepository, tripTombstoneDao, costCalculator, odometerMarks,
    vehicleKind, counterSnapshotDao, counterSnapshotWriter, lastStateDao,
)
