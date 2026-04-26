# Phase 2 — autoservice-readonly: UI surface + adblib integration

**Branch:** `feature/autoservice-readonly` (Phase 1 HEAD `63ec8bc`, off `main@850d8d8`)
**Spec:** `docs/superpowers/specs/2026-04-26-autoservice-readonly-design.md` § 5.4–5.9
**UI mock (approved 2026-04-26):** `docs/superpowers/mocks/2026-04-26-phase2-ui.html`
**Phase 1 plan (closed, 17/17):** `docs/superpowers/plans/2026-04-26-autoservice-readonly-phase1.md`

## Goal

Дать юзеру **end-to-end рабочий** autoservice flow: включил toggle в Настройках → app pair'ится с DiLink ADB через minimal-pairing dialog → BatteryStateRepository отдаёт реальные данные → catch-up детектор пишет SoH-замеры → новый таб «Зарядки» в Trips-style показывает иерархию + Lifetime AC/DC + Эквив. циклов + mini-графики SoH/Capacity. Battery card на Главной кликабельна, ведёт в BatteryHealthScreen. TopBar показывает «ADB не отвечает» если соединение упало.

## Decisions (фиксируем перед стартом)

- **Номинал батареи** — из настроек (`SettingsRepository.getBatteryCapacityKwh()`, default 72.9), НЕ hardcoded.
- **adblib** — включаем в Phase 2 как C0 (до C1). Pairing — minimal dialog, не wizard (spec § 6: «pairing wizard не делать», но кнопка + диалог 6-значного кода — допустимо).
- **Charges fallback (2)** — Idea 3: только period summary, без lifetime карточки + footnote «Lifetime метрики и SoH тренды доступны после включения 'Системные данные'».
- **Charges фильтры периода** — расширить до 5 чипов как в Trips: `TODAY/WEEK/MONTH/YEAR/ALL` (ранее только WEEK/MONTH).
- **Charges layout** — копия Trips: 65% слева иерархия Месяц▶/День▶/Сессия + 35% справа правая панель.
- **Switch design** — `bydSwitchColors`: track зелёный/серый, thumb всегда `NavyMid` (тёмный). `Components.kt:82` уже есть.
- **Все taskи через `subagent-driven-development` skill** — implementer + spec reviewer + code-quality reviewer на каждую task. Без surgical-edit shortcut.

## Что уже есть из Phase 1 (НЕ переделывать)

- `SettingsRepository`: `KEY_AUTOSERVICE_ENABLED`, `KEY_CHARGING_PROMPT_ENABLED`, `KEY_LAST_SEEN_SOC`, `KEY_AUTOSERVICE_BASELINE_KWH/TS` + accessors `isAutoserviceEnabled()/setAutoserviceEnabled()/isChargingPromptEnabled()/setChargingPromptEnabled()` — все 5 ключей готовы.
- `BatteryStateRepository.refresh(): BatteryState` (suspend, не StateFlow). Поля: `socNow, voltage12v, sohPercent, lifetimeKm, lifetimeKwh, autoserviceAvailable`.
- `BatteryHealthRepository`: `getAll()/getRecent()/getLast()/getCount()/calculateCapacity()/calculateSoh()` + 72.9 default.
- `ChargeRepository`: `getAllCharges()/getChargesByDateRange()/getPeriodSummary()/getMaxLifetimeKwhAtFinish()`.
- `AutoserviceClient` interface + `AutoserviceClientImpl`.
- `AdbOnDeviceClient` interface + `AdbOnDeviceClientImpl` **stub** (`ensureConnected()` throws «not yet wired»). **Phase 2 C0 закроет.**
- DI providers в `AppModule`: `provideAdbOnDeviceClient`, `provideAutoserviceClient`.
- `AutoserviceChargingDetector` (catch-up state machine, 9 тестов) — wired в `TrackingService`.

## Tasks

### C0 — adblib integration + minimal pairing UX

**Files:**
- `app/build.gradle.kts` (deps + JitPack repo)
- `app/src/main/kotlin/com/bydmate/app/data/autoservice/AdbOnDeviceClient.kt` (impl)
- `app/src/main/kotlin/com/bydmate/app/data/autoservice/AdbKeyStore.kt` (NEW — RSA keypair persistence)
- `app/src/main/kotlin/com/bydmate/app/ui/settings/AdbPairingDialog.kt` (NEW — Compose dialog)
- `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt` (pairing state)
- `app/src/test/kotlin/com/bydmate/app/data/autoservice/AdbOnDeviceClientTest.kt` (NEW)
- `app/src/test/kotlin/com/bydmate/app/data/autoservice/AdbKeyStoreTest.kt` (NEW)

**Implementation:**
1. `app/build.gradle.kts`:
   - Add `maven { url = uri("https://jitpack.io") }` в `repositories` (settings.gradle.kts → dependencyResolutionManagement).
   - Add `implementation("com.github.cgutman:AdbLib:adblib:1.0.0")` (или последняя стабильная — проверить).
   - Если adblib не собирается с targetSdk=29 — fallback: hand-rolled port из `.research/competitor/` (см. `reference_competitor_byd_ev_pro.md`).
2. `AdbKeyStore` (Singleton):
   - Persistent RSA keypair в `EncryptedSharedPreferences` (или Keystore-backed file `adb_keys/private.pem` + `public.pem` в `context.filesDir`).
   - `loadOrGenerate(): KeyPair` — атомарно: если файлов нет, генерим 2048-bit RSA, сохраняем; иначе читаем.
   - `getFingerprint(): String` — для логов.
3. `AdbOnDeviceClientImpl`:
   - `ensureConnected()` — `AdbConnection.create(host=127.0.0.1, port=5555, keyPair=keystore.loadOrGenerate())`. Storing `connection: AdbConnection?`.
   - `doExec(cmd)` — `connection.openShell(cmd).readText()`.
   - `tryPing()` — `doExec("echo 1") == "1"` или per-adblib эквивалент.
   - **Pairing flow** (новый метод `suspend fun pair(code: String): Result<Unit>`):
     - `AdbConnection.pair(host=127.0.0.1, port=PAIRING_PORT, code)` — adblib API.
     - PAIRING_PORT — auto-discovery через mDNS или фиксированный (DiLink Wireless ADB обычно использует случайный порт, надо смотреть ADB Wireless Debugging Settings).
     - Возвращает Result.success() при ОК, Result.failure(exception) при ошибке.
   - WRITE_BARRIER_REGEX уже есть, оставляем.
4. `AdbPairingDialog`:
   - `@Composable fun AdbPairingDialog(onDismiss, onPair: suspend (code: String) -> Result<Unit>)`.
   - 6-значный TextField input, кнопки `[Отмена] [Подключить]`.
   - Loading state + error message.
   - Дизайн: использовать существующие токены (CardSurface, AccentGreen).
5. `SettingsViewModel.tryPair(code: String)` — вызывает client.pair(), обновляет status в UiState.

**Tests:**
- `AdbKeyStoreTest`:
  - `loadOrGenerate_firstCall_generatesNewKeypair`
  - `loadOrGenerate_secondCall_returnsSameKeypair`
- `AdbOnDeviceClientTest` (с моком adblib или через interface seam):
  - `exec_writeCommand_throws` (WRITE_BARRIER_REGEX)
  - `exec_disconnected_returnsNull`
  - `pair_validCode_returnsSuccess`
  - `pair_invalidCode_returnsFailure`

**Acceptance:**
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL.
- Все новые тесты зелёные.
- ❗ **Manual smoke на Leopard 3 (Andy на машине, обязательно)**:
  - Включить Wireless ADB Debugging на DiLink (Settings → Developer Options).
  - Запустить APK, открыть Настройки, включить toggle «Системные данные».
  - В B-state нажать [Подключить], ввести 6-значный код с DiLink.
  - Status должен переключиться на C («✓ подключено · SoH 100% · lifetime ...»).
- Если manual smoke падает — логировать ошибку, fallback на hand-rolled implementation.

**Dependencies:** None (стартовый task).

---

### C1 — SettingsViewModel autoservice state

**Files:**
- `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt`
- `app/src/test/kotlin/com/bydmate/app/ui/settings/SettingsViewModelTest.kt` (новые тесты)

**Implementation:**
1. `SettingsUiState`: добавить:
   - `autoserviceEnabled: Boolean = false`
   - `chargingPromptEnabled: Boolean = true`
   - `autoserviceStatus: AutoserviceStatus = AutoserviceStatus.NotEnabled`
2. `sealed class AutoserviceStatus` (новый, в этом файле):
   - `object NotEnabled` (toggle OFF — рендерим пустой блок)
   - `object Disconnected` (toggle ON, `BatteryState.autoserviceAvailable = false`)
   - `data class Connected(socNow: Int, lifetimeKm: Float, lifetimeKwh: Float, sohPercent: Float?)` (toggle ON, есть данные)
   - `object AllSentinel` (toggle ON, все запрошенные fid'ы = 0xFFFF)
3. Маппинг логика в `loadAutoserviceState()` (suspend, в init или в onResume-equivalent):
   ```kotlin
   if (!settings.isAutoserviceEnabled()) → NotEnabled
   else {
     val state = batteryStateRepository.refresh()
     when {
       !state.autoserviceAvailable → Disconnected
       state.socNow == null && state.lifetimeKm == null && state.lifetimeKwh == null → AllSentinel
       else → Connected(socNow, lifetimeKm, lifetimeKwh, sohPercent)
     }
   }
   ```
4. `setAutoserviceEnabled(enabled: Boolean)`: settings.set + reload state.
5. `setChargingPromptEnabled(enabled: Boolean)`: settings.set.
6. `tryPair(code: String): Result<Unit>` — делегат к `adbOnDeviceClient.pair(code)`, после успеха reload state.

**Tests:**
- `loadAutoserviceState_toggleOff_returnsNotEnabled`
- `loadAutoserviceState_toggleOnDisconnected_returnsDisconnected`
- `loadAutoserviceState_toggleOnAllNull_returnsAllSentinel`
- `loadAutoserviceState_toggleOnWithData_returnsConnected`
- `setAutoserviceEnabled_persistsAndReloads`

**Acceptance:**
- Все тесты зелёные.
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL.

**Dependencies:** C0.

---

### C2 — Settings UI «Системные данные» секция

**Files:**
- `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsScreen.kt`

**Implementation:**
1. Найти точку вставки — между секциями «Источник данных поездок» и «Данные» (примерно после строки `303` per spec § 5.4 — найти grep'ом по `SectionHeader(text = "Источник данных поездок")` потому что строки могут сдвинуться от Phase 1).
2. Вставить:
   ```kotlin
   SectionHeader(text = "Системные данные (экспериментально)")
   Card(
       shape = RoundedCornerShape(12.dp),
       colors = CardDefaults.cardColors(containerColor = CardSurface),
       modifier = Modifier.fillMaxWidth()
   ) {
       Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
           Text(
               text = "Расширенные данные с машины: SoH батареи, истинный пробег от BMS, статистика зарядок. Только чтение.",
               color = TextSecondary, fontSize = 12.sp
           )
           Row(...) {
               Text("Включить", ...)
               Switch(checked = state.autoserviceEnabled, onCheckedChange = { vm.setAutoserviceEnabled(it) }, colors = bydSwitchColors())
           }
           AutoserviceStatusBlock(state.autoserviceStatus, onPair = { showPairDialog = true })
           if (state.autoserviceEnabled) {
               Row(...) {
                   Text("Спрашивать после каждой зарядки", ...)
                   Switch(checked = state.chargingPromptEnabled, ..., colors = bydSwitchColors())
               }
           }
       }
   }
   if (showPairDialog) {
       AdbPairingDialog(onDismiss = { showPairDialog = false }, onPair = vm::tryPair)
   }
   ```
3. `AutoserviceStatusBlock(status: AutoserviceStatus, onPair: () -> Unit)` — render по 4 substate'ам (см. mock `<div class="status-block">`):
   - `NotEnabled` → пусто.
   - `Disconnected` → `Text("✗ не подключено · проверь Wi-Fi на DiLink", color = TextMuted)` + Button `[Подключить]` → `onPair()`.
   - `Connected(...)` → `Text("✓ подключено\n   SoH ${sohPercent}% · lifetime ${lifetimeKm} км / ${lifetimeKwh} кВт·ч", color = AccentGreen)`.
   - `AllSentinel` → `Text("⚠ подключено, но данные не читаются\n   возможно функция работает только на Leopard 3", color = SocYellow)`.

**Tests:**
- Compose preview-теста не пишем (UI — вручную через mock/manual smoke).

**Acceptance:**
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL.
- Manual visual review — секция выглядит как в моке `2026-04-26-phase2-ui.html` § 1.

**Dependencies:** C1.

---

### C3 — Dashboard TopBar warning + кликабельная BatteryCard → BatteryHealthScreen

**Files:**
- `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModel.kt`
- `app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardScreen.kt`
- `app/src/main/kotlin/com/bydmate/app/ui/navigation/AppNavigation.kt`
- `app/src/test/kotlin/com/bydmate/app/ui/dashboard/DashboardViewModelTest.kt` (если уже есть — добавить кейсы)

**Implementation:**
1. `DashboardUiState` — добавить `adbConnected: Boolean? = null` (null = autoservice toggle OFF, не показывать badge).
2. `DashboardViewModel.loadAutoserviceFlag()` — suspend, берётся из BatteryStateRepository:
   ```kotlin
   val enabled = settings.isAutoserviceEnabled()
   if (!enabled) return null
   batteryStateRepository.refresh().autoserviceAvailable
   ```
   Вызывать в init и в onPullToRefresh-эквиваленте.
3. `DashboardScreen.TopBar` — расширить сигнатуру:
   ```kotlin
   @Composable
   private fun TopBar(isServiceRunning: Boolean, diPlusConnected: Boolean, adbConnected: Boolean? = null)
   ```
   После блока DiPlus warning (line ~412 — найти grep'ом `"DiPlus не отвечает"`):
   ```kotlin
   if (isServiceRunning && adbConnected == false) {
       Text("ADB не отвечает", color = SocYellow, fontSize = 12.sp)
       Spacer(modifier = Modifier.width(8.dp))
   }
   ```
4. Передать `adbConnected = state.adbConnected` в TopBar (line ~72, найти grep'ом `TopBar(isServiceRunning`).
5. `DashboardScreen` подпись расширить:
   ```kotlin
   @Composable
   fun DashboardScreen(onNavigateBatteryHealth: () -> Unit = {}, ...)
   ```
6. **BatteryCard** — найти grep'ом `CompactCard(.*battery|leftLabel = "батарея"`. Заменить `onClick = { viewModel.toggleBatteryHealthExpanded() }` на `onClick = { onNavigateBatteryHealth() }`. **Удалить** `state.batteryHealthExpanded` block (line ~299, popup-диалог).
7. `AppNavigation.kt` — добавить:
   ```kotlin
   composable(Screen.Dashboard.route) {
       DashboardScreen(onNavigateBatteryHealth = { navController.navigate("battery_health") })
   }
   composable("battery_health") { BatteryHealthScreen() }
   ```

**Tests:**
- `DashboardViewModel: adbConnected = null when autoservice disabled`
- `DashboardViewModel: adbConnected = true when autoservice connected`
- `DashboardViewModel: adbConnected = false when autoservice enabled but unavailable`

**Acceptance:**
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL.
- Manual: тап на BatteryCard ведёт в BatteryHealthScreen, не открывает popup.

**Dependencies:** C1 (для adbConnected).

---

### C4 — Charges tab return + BatteryHealth empty state copy

**Files:**
- `app/src/main/kotlin/com/bydmate/app/ui/navigation/AppNavigation.kt`
- `app/src/main/kotlin/com/bydmate/app/ui/battery/BatteryHealthScreen.kt`

**Implementation:**
1. `AppNavigation.kt`:
   - Add import `androidx.compose.material.icons.outlined.BatteryChargingFull`.
   - Add import `com.bydmate.app.ui.charges.ChargesScreen`.
   - В `enum class Screen` добавить **между Trips и Automation**:
     ```kotlin
     Charges("charges", "Зарядки", Icons.Outlined.BatteryChargingFull)
     ```
   - В NavHost:
     ```kotlin
     composable(Screen.Charges.route) {
         ChargesScreen(onNavigateSettings = { navController.navigate(Screen.Settings.route) })
     }
     ```
2. `BatteryHealthScreen.kt:54` (или найти grep'ом `"Информация появится"`):
   ```kotlin
   Text(
       "Нет данных. Первый замер появится после полной зарядки (при включённых системных данных в Настройках).",
       color = TextSecondary, fontSize = 14.sp
   )
   ```

**Tests:** None (тривиальные правки).

**Acceptance:**
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL.
- 5 табов в bottom nav: Главная / Поездки / **Зарядки** / Автоматизация / Настройки.

**Dependencies:** C5 (ChargesScreen должен существовать с `onNavigateSettings` параметром).

---

### C5 — Charges UI overhaul (Trips-style 65/35 + 4 fallback states + new ViewModel)

**Files:**
- `app/src/main/kotlin/com/bydmate/app/ui/charges/ChargesViewModel.kt`
- `app/src/main/kotlin/com/bydmate/app/ui/charges/ChargesScreen.kt`
- `app/src/main/kotlin/com/bydmate/app/data/repository/ChargeRepository.kt` (новый метод)
- `app/src/main/kotlin/com/bydmate/app/data/local/dao/ChargeDao.kt` (новый query если нужно)
- `app/src/test/kotlin/com/bydmate/app/ui/charges/ChargesViewModelTest.kt` (большое расширение)

**Implementation:**

**5.1 ChargeRepository.kt:**
```kotlin
data class LifetimeChargingStats(
    val totalKwhAdded: Double,
    val acKwh: Double,
    val dcKwh: Double,
    val sessionCount: Int
)
suspend fun getLifetimeStats(): LifetimeChargingStats {
    val all = chargeDao.getAllAutoserviceCharges()  // detection_source LIKE 'autoservice_%'
    val ac = all.filter { it.gunState == 2 }.sumOf { it.kwhCharged ?: 0.0 }
    val dc = all.filter { it.gunState in setOf(3, 4) }.sumOf { it.kwhCharged ?: 0.0 }
    val total = ac + dc
    return LifetimeChargingStats(total, ac, dc, all.size)
}
```

`ChargeDao.getAllAutoserviceCharges()`:
```kotlin
@Query("SELECT * FROM charge WHERE detection_source LIKE 'autoservice_%' ORDER BY start_ts ASC")
suspend fun getAllAutoserviceCharges(): List<ChargeEntity>
```

**5.2 ChargesViewModel.kt — переписать:**

```kotlin
enum class ChargesPeriod { TODAY, WEEK, MONTH, YEAR, ALL }
enum class ChargeTypeFilter { ALL, AC, DC }

data class MonthGroup(
    val yearMonth: String,  // "2026-04"
    val label: String,      // "Апрель 2026"
    val totalKwh: Double,
    val sessionCount: Int,
    val totalCost: Double,
    val days: List<DayGroup>
)
data class DayGroup(
    val date: String,       // "2026-04-25"
    val label: String,      // "25 апр (пт)" / "сегодня (сб)" / "вчера"
    val dayOfWeek: String,
    val totalKwh: Double,
    val sessionCount: Int,
    val totalCost: Double,
    val charges: List<ChargeEntity>
)
data class ChargesUiState(
    val period: ChargesPeriod = ChargesPeriod.MONTH,
    val typeFilter: ChargeTypeFilter = ChargeTypeFilter.ALL,
    val months: List<MonthGroup> = emptyList(),
    val expandedMonths: Set<String> = emptySet(),
    val expandedDays: Set<String> = emptySet(),
    val periodSummary: ChargeSummary = ChargeSummary(0, 0.0, 0.0),
    val currencySymbol: String = "BYN",
    // Fallback state
    val autoserviceEnabled: Boolean = false,
    val autoserviceConnected: Boolean = false,
    val autoserviceAllSentinel: Boolean = false,
    val hasLegacyCharges: Boolean = false,  // any non-autoservice charges in DB
    // Right panel — only when autoserviceEnabled = true
    val lifetimeAcKwh: Double = 0.0,
    val lifetimeDcKwh: Double = 0.0,
    val lifetimeTotalKwh: Double = 0.0,
    val equivCycles: Double = 0.0,
    val nominalCapacityKwh: Double = 72.9,
    val sohSeries: List<Float> = emptyList(),  // y-axis values
    val capacitySeries: List<Float> = emptyList()
)

@HiltViewModel
class ChargesViewModel @Inject constructor(
    private val chargeRepository: ChargeRepository,
    private val batterySnapshotDao: BatterySnapshotDao,
    private val settingsRepository: SettingsRepository,
    private val batteryStateRepository: BatteryStateRepository
) : ViewModel() {
    fun setPeriod(period: ChargesPeriod) { ... }
    fun setTypeFilter(filter: ChargeTypeFilter) { ... }
    fun toggleMonth(yearMonth: String) { ... }
    fun toggleDay(date: String) { ... }
    private fun loadAll() { ... }  // suspend, called on init + setPeriod/setTypeFilter
    private fun groupChargesByMonthDay(charges: List<ChargeEntity>): List<MonthGroup> { ... }
    private fun loadAutoserviceState() { ... }  // refresh() + decide enabled/connected/sentinel
    private fun loadLifetimeStats() { ... }  // chargeRepository.getLifetimeStats()
    private fun loadHealthSeries() { ... }  // batterySnapshotDao.getAll() → sohSeries/capacitySeries
}
```

**5.3 ChargesScreen.kt — полная переписка:**

Структура — копия `TripsScreen.kt`:

```kotlin
@Composable
fun ChargesScreen(
    onNavigateSettings: () -> Unit = {},
    viewModel: ChargesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 4 fallback resolver
        when {
            !state.autoserviceEnabled && !state.hasLegacyCharges -> {
                OnboardingEmptyState(onNavigateSettings)
                return@Column
            }
            state.autoserviceEnabled && state.autoserviceAllSentinel -> {
                SentinelEmptyState()
                return@Column
            }
        }
        // Banner if (2)
        if (!state.autoserviceEnabled && state.hasLegacyCharges) {
            NotTrackingBanner(onClick = onNavigateSettings)
        }
        // Chips row
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChargesChip("День", state.period == ChargesPeriod.TODAY) { vm.setPeriod(...) }
            ... (5 period chips)
            Spacer(width = 12.dp)
            ChargesChip("Все", state.typeFilter == ChargeTypeFilter.ALL) { ... }
            ChargesChip("AC", ...) ; ChargesChip("DC", ...)
        }
        Spacer(height = 8.dp)
        Row(modifier = Modifier.fillMaxSize()) {
            // LEFT 65% — hierarchical list
            LazyColumn(modifier = Modifier.weight(0.65f).fillMaxHeight()) {
                for (month in state.months) {
                    item { MonthHeader(month, expanded = month.yearMonth in state.expandedMonths, onClick = { vm.toggleMonth(...) }) }
                    if (month.yearMonth in state.expandedMonths) {
                        for (day in month.days) {
                            item { DayHeader(day, expanded = day.date in state.expandedDays, onClick = { vm.toggleDay(...) }) }
                            if (day.date in state.expandedDays) {
                                item { ColumnHeaders() }
                                for (charge in day.charges) {
                                    item { ChargeRow(charge, currencySymbol) }
                                }
                            }
                        }
                    }
                }
            }
            // Vertical divider
            Box(...)
            // RIGHT 35% — stats panel
            ChargesStatsPanel(
                periodSummary = state.periodSummary,
                lifetimeAcKwh = state.lifetimeAcKwh,
                lifetimeDcKwh = state.lifetimeDcKwh,
                equivCycles = state.equivCycles,
                sohSeries = state.sohSeries,
                capacitySeries = state.capacitySeries,
                showLifetime = state.autoserviceEnabled && state.autoserviceConnected,
                modifier = Modifier.weight(0.35f).fillMaxHeight()
            )
        }
    }
}
```

`ChargesStatsPanel`:
- Period summary card (Сессий/кВт·ч/₽).
- If `showLifetime`: Lifetime card (AC/DC %, Эквив. циклов = lifetimeTotalKwh / nominalCapacityKwh).
- If `showLifetime` && sohSeries.size >= 2: Mini SoH chart (height ~60dp).
- If `showLifetime` && capacitySeries.size >= 2: Mini Capacity chart (height ~60dp).
- If `!showLifetime`: footer text «Lifetime метрики и SoH тренды доступны после включения "Системные данные"».

`MonthHeader` / `DayHeader` / `ColumnHeaders` / `ChargeRow` — копировать структуру из `TripsScreen.kt:168-330` с заменой полей:
- ChargeRow: `время старта · тип badge AC/DC · SOC start → end · кВт·ч · ₽`.
- Day/Month headers: `▼/▶ · label · (sessions, kWh, cost)` monospace.

`OnboardingEmptyState`:
```kotlin
Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Статистика зарядок недоступна. Чтобы видеть кВт·ч и стоимость каждой зарядки — включи «Системные данные» в Настройках.", ...)
        Spacer(height = 14.dp)
        Button(onClick = onNavigateSettings, colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark)) {
            Text("Перейти в Настройки")
        }
    }
}
```

`SentinelEmptyState` → `Text("На вашей модели машины статистика зарядок недоступна — диагностические данные не читаются. SoH тоже не показывается.", ...)`.

`NotTrackingBanner` → yellow border card с link «Включить →» → `onNavigateSettings()`.

**5.4 Tests (`ChargesViewModelTest.kt`):**

- `setPeriod_today_filtersChargesToToday`
- `setPeriod_year_filtersToLast365Days`
- `setTypeFilter_AC_filtersOnlyAcCharges`
- `groupChargesByMonthDay_groupsCorrectly`
- `groupChargesByMonthDay_emptyList_returnsEmpty`
- `toggleMonth_addsToExpanded`
- `toggleMonth_removesFromExpanded`
- `loadAutoserviceState_toggleOff_returnsEnabledFalse`
- `loadAutoserviceState_toggleOnConnected_returnsEnabledTrueConnectedTrue`
- `loadAutoserviceState_toggleOnAllNull_returnsAllSentinelTrue`
- `loadLifetimeStats_zeroKwh_equivCyclesIsZero`
- `loadLifetimeStats_kwhEqualsNominal_equivCyclesIsOne`
- `loadLifetimeStats_acDcSplit_correct`
- `hasLegacyCharges_onlyAutoservice_returnsFalse`
- `hasLegacyCharges_someDiPlus_returnsTrue`

15 тестов минимум.

**5.5 Implementation notes / known gotchas:**

- День «сегодня (сб)»: использовать `Calendar.getInstance().apply { time = Date() }` для определения `сегодня`/`вчера`/`(date)`.
- Месяц label: `SimpleDateFormat("LLLL yyyy", Locale("ru"))` → «Апрель 2026».
- При `period = ALL` могут быть тысячи зарядок (нет, на DiLink ~600/год макс) — LazyColumn ОК.
- `expandedMonths` / `expandedDays` — set'ы строк yearMonth/date. По умолчанию первый месяц раскрыт автоматически (UX gut feeling).
- `ChargeEntity.gunState` mapping: 1=NONE, 2=AC, 3=DC, 4=GB_DC. AC = 2, DC = 3 || 4.
- `sohSeries` / `capacitySeries` берутся из `BatterySnapshotDao.getAll()` (последние N снапшотов, max ~50). Reversed для chronological.

**Acceptance:**
- 15+ unit-тестов зелёные.
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL.
- `./gradlew :app:testDebugUnitTest` весь suite зелёный (Phase 1 тесты не сломались).
- Manual visual review против mock `2026-04-26-phase2-ui.html` § 4.

**Dependencies:** C0, C1 (autoservice state), Phase 1 (BatterySnapshotDao, ChargeEntity.gunState/detectionSource).

---

## Cross-cutting tests

После каждого C-task:
- `./gradlew :app:testDebugUnitTest` (все тесты приложения).
- `./gradlew :app:assembleDebug` (компиляция).

Финал Phase 2:
- `./gradlew :app:assembleRelease` (release build).
- Manual smoke на Leopard 3 (Andy):
  - 1. App startup → Настройки → toggle ON → AdbPairingDialog → ввести код → status переключается на Connected → видны SoH/lifetime.
  - 2. На Главной — TopBar показывает 3 статуса (сервис/DiPlus/ADB).
  - 3. Tap на BatteryCard → переход в BatteryHealthScreen.
  - 4. Bottom nav — 5 табов, Зарядки между Поездки и Автоматизация.
  - 5. Зарядки: пустое состояние (1) с кнопкой «Перейти в Настройки» → работает; после первой зарядки появляется иерархия.
  - 6. Toggle OFF → банер «Новые зарядки не отслеживаются».
  - 7. ADB соединение упало (выключи Wi-Fi) → TopBar показывает «ADB не отвечает».

## Phase 2 acceptance gate

- Все C0-C5 commits в `feature/autoservice-readonly`.
- Все unit-тесты зелёные (~80+ всего после Phase 1+2).
- assembleDebug + assembleRelease BUILD SUCCESSFUL.
- Manual smoke на Leopard 3 пройден.
- Codex audit (по `feedback_codex_audit_before_release.md`) — ДО `gh release create`.

После gate → Phase 3 plan (live tick + ChargingFinalizePromptDialog) или сразу v2.5.0 release с feature-flag toggle.

## Open questions для следующей сессии

1. **adblib JitPack совместимость с targetSdk=29** — проверить первым делом в C0. Если не собирается — fallback hand-rolled (артефакты `.research/competitor/byd-ev-pro/AdbClient.java`).
2. **Pairing port discovery** — DiLink Wireless ADB использует случайный порт для pairing. Способы:
   - mDNS lookup (`_adb-tls-pairing._tcp`).
   - Или попросить юзера ввести `port:code` строкой (5-значный порт + 6-значный код).
3. **EncryptedSharedPreferences на DiLink Android 12** — должен работать, но min API check.
4. **«Раскрыть первый месяц по умолчанию»** — UX gut feeling, при `period = ALL` может быть много месяцев. Возможно лучше: текущий месяц всегда раскрыт.
5. **Mini-charts при < 2 данных** — рендерить «недостаточно данных» текст или скрывать?
