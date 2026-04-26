# autoservice readonly + charging stats — design spec

**Branch:** `feature/autoservice-readonly` (off `main@850d8d8`, v2.4.12)
**Created:** 2026-04-26
**Status:** design согласован, готов к Phase 1 implementation
**Source handoff:** `docs/superpowers/handoff-2026-04-26.md` (секции 1-3 + late-session уточнения)

---

## 1. Goal в одном абзаце

Дать BYDMate доступ к системному autoservice Binder через ADB-on-device pattern (read-only). На основе lifetime-данных BMS (LIFETIME_KWH, SOC, GUN_CONNECT_STATE, cell voltages, 12V) реализовать **новую методику детекции зарядок** (catch-up + опциональный live), считать SoH через дельту kWh/SOC, показать всё в возвращённом табе «Зарядки» и кликабельной батарейке Dashboard. Ничего не сломать для юзеров без autoservice toggle (поведение v2.4.12 один-в-один).

---

## 2. Что уже есть в коде (база реюза)

| Компонент | Файл | Что даёт |
|---|---|---|
| `ChargeEntity` (БД v11) | `data/local/entity/ChargeEntity.kt:8` | таблица `charges` со всеми полями (start_ts/end_ts/soc_start/soc_end/kwh_charged/kwh_charged_soc/max_power_kw/type/cost/lat/lon/bat_temp_avg/max/min/avg_power_kw/status/cell_voltage_min/max/voltage_12v/exterior_temp/merged_count) |
| `ChargePointEntity` | `data/local/entity/ChargePointEntity.kt` | таблица `charge_points` с FK CASCADE на charges |
| `BatterySnapshotEntity` | `data/local/entity/BatterySnapshotEntity.kt` | таблица `battery_snapshots` (timestamp/odometer_km/soc_start/end/kwh_charged/calculated_capacity_kwh/soh_percent/cell_delta_v/bat_temp_avg/charge_id) |
| DAO + `@Provides` | `data/local/dao/*.kt` + `di/AppModule.kt:225-229` | готовые insert/query/flow |
| `ChargeRepository` / `BatteryHealthRepository` | `data/repository/` | + готовые `calculateCapacity(kwh, socStart, socEnd)` и `calculateSoh(cap, nominal=72.9)` |
| `ChargesScreen` + `ChargesViewModel` + `ChargeCard` | `ui/charges/` + `ui/components/Components.kt:266` | UI: фильтр AC/DC/All, период WEEK/MONTH, сводка (sessions/kWh/cost), expandable PowerCurve график |
| `BatteryHealthScreen` + `BatteryHealthViewModel` | `ui/battery/` | 4 stat-показателя + 4 графика (cell delta, 12V, capacity, SoH) с порогами warning/critical |
| AC/DC тарифы (`KEY_HOME_TARIFF`/`KEY_DC_TARIFF`) | `data/repository/SettingsRepository.kt:15-16, 38-39, 71-75` + UI `SettingsScreen.kt:172-180` | редактируемые тарифы с дефолтами 0.20/0.73 |
| `DataThinningWorker` | `data/local/DataThinningWorker.kt` | прореживает charge_points (15s @7-30 дн, 60s @>30 дн) |
| `composable("battery_health")` | `ui/navigation/AppNavigation.kt:212` | роут уже зарегистрирован |

**Вывод:** ~85% инфраструктуры стоит, мёртвый только источник данных и старый детектор.

## 3. Что выпиливаем

| Что | Где | Почему |
|---|---|---|
| `ChargeTracker` | `domain/tracker/ChargeTracker.kt` (380 строк) | реактивный, требует DiPars-poll при зарядке. На Leopard 3 DiLink спит → не работает; на других моделях не верифицирован, источник дубликатов с новым autoservice-детектором |
| `chargeTracker` injection и calls в `TrackingService` | `service/TrackingService.kt:27, 57, 200-212` (finalize stale + onCreate sync block) | следствие удаления |
| `chargeTracker.onData(...)` calls в polling loop | искать по grep — TrackingService poll | следствие удаления |
| `chargeTracker.forceEnd(...)` в shutdown | искать в onDestroy/onTaskRemoved | следствие удаления |
| `diPlusDbReader.importChargingLog()` | `service/TrackingService.kt:220` + `ui/welcome/WelcomeViewModel.kt:97` + `ui/settings/SettingsViewModel.kt:374` | подтверждено: пустая таблица на Leopard 3 и других моделях. Бесполезно |
| Метод `importChargingLog` сам | `data/remote/DiPlusDbReader.kt:121` | следствие |
| `ChargeTracker:*` упоминание в logcat tag | `ui/settings/SettingsViewModel.kt:641` | следствие |
| `IdleDrainTracker.kt` параметр `isCharging: Boolean` | `domain/tracker/IdleDrainTracker.kt:39` | следствие — будет всегда false (нет состояния «идёт зарядка»). Заменить на параметр-источник из autoservice gun_state, либо просто удалить guard если не критичен |

**Cleanup в migration 11→12:** `DELETE FROM charges WHERE status IN ('SUSPENDED', 'ACTIVE')` — одноразовая чистка незавершённых сессий от старого ChargeTracker. Завершённые `COMPLETED` остаются в истории.

## 4. Что добавляем (новые файлы)

### 4.1 Слой autoservice (read-only к системному Binder)

```
data/autoservice/
├── AutoserviceClient.kt       # interface + impl, getInt/getFloat + readBatterySnapshot/readChargingSnapshot
├── AdbOnDeviceClient.kt       # Java ADB-клиент к 127.0.0.1:5555 после RSA pairing
├── SentinelDecoder.kt         # 0xFFFF→null, 0xBF800000→null, -10011→null и т.п.
├── FidRegistry.kt             # const'ы dev/fid + tx codes (5=getInt, 7=getFloat) для всех нужных параметров
├── BatteryReading.kt          # in-memory POJO snapshot одного autoservice-чтения (НЕ путать с БД-сущностью BatterySnapshotEntity)
└── ChargingReading.kt         # in-memory POJO снапшот зарядных fid'ов (gun_state, charging_type, charge_voltage, lifetime_kwh)
```

**Почему `BatteryReading` а не `BatterySnapshot`:** уже есть БД-сущность `BatterySnapshotEntity` (Q4 в брифе). Чтобы избежать путаницы — POJO именуем `BatteryReading`/`ChargingReading`.

**Почему `data/autoservice/` а не `data/system/`:** канал = autoservice Binder, имя точное и узнаваемое. `system/` слишком общее.

**`AutoserviceClient` interface:**
```kotlin
interface AutoserviceClient {
    suspend fun isAvailable(): Boolean                    // RSA paired + ADB пингуется + autoservice отвечает
    suspend fun getInt(dev: Int, fid: Int): Int?          // null = sentinel или ошибка
    suspend fun getFloat(dev: Int, fid: Int): Float?      // tx=7, IEEE 754 float (НЕ double — feedback_autoservice_validated.md)
    suspend fun readBatterySnapshot(): BatteryReading?    // SoH + lifetime_kwh + lifetime_mileage + soc + cell delta + 12V + bat_temp_avg
    suspend fun readChargingSnapshot(): ChargingReading?  // gun_state + charging_type + charge_voltage + max_charge_power_allow + battery_type
}
```

**Regex-barrier против WRITE:** в impl `AdbOnDeviceClient.exec(cmd)` — assertion `require(cmd.matches(Regex("""^service call autoservice [579] i32 \d+ i32 -?\d+$""")))` блокирует transact 6 (setInt) структурно. Если кто-то когда-то добавит `getInt` с tx=6 — упадёт сразу, не на машине.

**ADB lib выбор:** Phase 1 — попробовать `com.cgutman:adblib` (Maven Central, MIT, ~500 LOC, RSA pairing + shell). Если не подходит (например shell-streaming не работает) — fallback своя имплементация ~600 LOC по образцу EV Pro `AdbClient.java` (`reference_competitor_byd_ev_pro.md`, артефакты в `.research/competitor/`).

### 4.2 Слой детекции зарядок (новая методика)

```
data/charging/
├── AutoserviceChargingDetector.kt  # state machine IDLE → EVALUATE → ACTIVE → FINALIZE
├── ChargingBaselineStore.kt        # абстракция «дай/обнови baseline lifetime_kwh» поверх ChargeRepository
└── ChargingTypeClassifier.kt       # gun_state → AC/DC/GB_DC; fallback heuristic kwh_per_hour > 20
```

**`AutoserviceChargingDetector` контракт:**
```kotlin
@Singleton
class AutoserviceChargingDetector @Inject constructor(
    private val client: AutoserviceClient,
    private val chargeRepo: ChargeRepository,
    private val batteryHealthRepo: BatteryHealthRepository,
    private val baselineStore: ChargingBaselineStore,
    private val classifier: ChargingTypeClassifier,
    private val settings: SettingsRepository,
    private val promptBus: ChargingPromptBus           // см. 4.3
) {
    /** Single-shot catch-up на service start. Сравнивает текущий lifetime_kwh с baseline. */
    suspend fun runCatchUp(now: Long = System.currentTimeMillis()): CatchUpResult

    /** Live tick (опционально, если ignition ON). Возвращает текущее состояние. */
    suspend fun runLiveTick(now: Long = System.currentTimeMillis()): LiveTickResult

    /** Текущее состояние для UI (TopBar warning + Charging tab). */
    val state: StateFlow<DetectorState>  // IDLE / EVALUATING / ACTIVE(chargeId) / FINALIZING / ERROR(reason)
}
```

**State machine (по handoff'у):**
```
IDLE
  ↓ runCatchUp() видит delta lifetime_kwh ≥ 0.5 ИЛИ delta SOC ≥ 1%
EVALUATE
  ↓ читает все charging fid'ы, классифицирует AC/DC, считает kwh/cost
ACTIVE (только если runLiveTick() поймал gun=2/3/4 при живом ADB)
  ↓ записывает ChargePoint каждые 30 сек, обновляет ChargeEntity status='ACTIVE'
FINALIZE
  ↓ insertOrUpdate ChargeEntity status='COMPLETED', insert BatterySnapshotEntity если delta SOC ≥ 5%, обновляет baseline
IDLE
```

**Триггер «была зарядка» (handoff подтверждён Andy):** `lifetime_kwh_now - baseline ≥ 0.5 кВт·ч` ИЛИ `soc_now - last_seen_soc ≥ 1%`. Если оба true — одна сессия. Только delta SOC без kWh — игнорируем (могла быть калибровка SOC).

**Классификация AC/DC:**
1. Если `runLiveTick()` поймал `gun_state ∈ {2, 3, 4}` — записываем raw в `ChargeEntity.gun_state`. Тип: 2→"AC", 3→"DC", 4→"DC" (GB_DC объединяем для UI).
2. Если catch-up и `gun_state == 1` (NONE) на момент чтения — heuristic `kwh_charged / hours > 20 → "DC"`, иначе "AC". `gun_state` в БД остаётся null. `detection_source = "autoservice_catchup"`.
3. Юзер всегда может изменить вручную через prompt (см. 4.3) → `detection_source = "user_override"`.

**Baseline storage (Q1=a — без новой таблицы):**
- `ChargingBaselineStore.getBaseline()` → `MAX(lifetime_kwh_at_finish) FROM charges WHERE detection_source LIKE 'autoservice%'`
- Если ни одной autoservice-сессии нет → возвращает `null` → первый catch-up тихо устанавливает baseline без создания сессии (записывает «фантом» baseline в k/v `KEY_AUTOSERVICE_BASELINE_KWH` / `KEY_AUTOSERVICE_BASELINE_TS`)
- При каждом FINALIZE баseline обновляется через `lifetime_kwh_at_finish` нового ChargeEntity → durable, source-of-truth в БД

### 4.3 Optional finalize prompt (E решение)

```
ui/charging/
├── ChargingFinalizePromptDialog.kt       # после FINALIZE, показывает kWh / SOC start→end / type AC|DC | cost. Поля редактируемы. Save / Discard
└── ChargingPromptBus.kt                  # Channel<ChargingPromptRequest> между Detector и dashboard/composition
```

`ChargingPromptBus` — singleton Channel/SharedFlow для cross-screen триггера. Detector эмитит `ChargingPromptRequest(chargeId)`, MainActivity собирает в Composition и показывает Dialog поверх любого экрана.

Toggle: `KEY_CHARGING_PROMPT_ENABLED` (default `true`) в SettingsRepository.

### 4.4 Battery state aggregator (combine D+ + autoservice)

```
domain/battery/
└── BatteryStateRepository.kt   # Flow<BatteryState>: combine DiParsClient + AutoserviceClient + BatterySnapshotDao
```

Используется DashboardViewModel для BatteryCard. Поля: `socNow, voltage12vNow, cellDeltaNow, sohLatest, lifetimeKm, lifetimeKwh, autoserviceAvailable: Boolean`. Если autoservice недоступен — все autoservice-only поля null, UI рендерит как v2.4.12.

## 5. Что меняем (existing files)

### 5.1 БД и DI

| Файл | Что | Why |
|---|---|---|
| `data/local/database/AppDatabase.kt:42` | `version = 11` → `12`. `entities = [...]` без изменений (реюз ChargeEntity/etc) | Q1=a, Q3=да |
| `di/AppModule.kt:194-209` | Добавить `MIGRATION_11_12` после `MIGRATION_10_11` | Миграция |
| `di/AppModule.kt:219` | Добавить `MIGRATION_11_12` в `addMigrations(...)` | Регистрация |
| `di/AppModule.kt` (новые `@Provides`) | `provideAutoserviceClient`, `provideAdbOnDeviceClient`, `provideAutoserviceChargingDetector`, `provideBatteryStateRepository`, `provideChargingPromptBus` | DI |

**Миграция 11→12 текстом:**
```kotlin
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // New fields on existing 'charges' table for autoservice detector trace + baseline source
        db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_start REAL")
        db.execSQL("ALTER TABLE charges ADD COLUMN lifetime_kwh_at_finish REAL")
        db.execSQL("ALTER TABLE charges ADD COLUMN gun_state INTEGER")
        db.execSQL("ALTER TABLE charges ADD COLUMN detection_source TEXT")

        // One-shot cleanup: drop unfinished sessions from removed ChargeTracker
        db.execSQL("DELETE FROM charges WHERE status IN ('SUSPENDED', 'ACTIVE')")
    }
}
```

**Зачем 4 поля и не меньше:**
- `lifetime_kwh_at_finish` — **критично** для baseline следующего catch-up (без него либо k/v singleton с риском потери, либо невозможно)
- `lifetime_kwh_at_start` — для consistency check (`lifetime_kwh_at_finish - lifetime_kwh_at_start ≈ kwh_charged`); при отладке покажет если BMS lifetime_kwh «прыгал» во время сессии
- `gun_state` — raw 1-4 если поймали live, чтобы в будущем мочь пересчитать классификацию или показать в UI «GB_DC» отдельно от «DC»
- `detection_source` — «diplus» (legacy данные останутся), «autoservice_live», «autoservice_catchup», «user_override» — нужен для (a) дедупликации в будущем если обе ветки оживут, (b) UI badge «catch-up», (c) фильтрации baseline в `ChargingBaselineStore`

### 5.2 Сервис

| Файл | Что | Why |
|---|---|---|
| `service/TrackingService.kt:27` | Удалить `import com.bydmate.app.domain.tracker.ChargeTracker` | B решение |
| `service/TrackingService.kt:57` | Удалить `@Inject lateinit var chargeTracker: ChargeTracker` | B |
| `service/TrackingService.kt:200-212` | Удалить блок `serviceScope.launch { ... finalizeSuspended ... }` | B (миграция уже почистит SUSPENDED) |
| `service/TrackingService.kt:220` | Удалить `diPlusDbReader.importChargingLog()` | подтверждено |
| `service/TrackingService.kt:215-226` | Заменить блок `serviceScope.launch { historyImporter.runSync(); ...; insightsManager.refreshIfNeeded() }` — добавить вызов `autoserviceChargingDetector.runCatchUp()` после historyImporter, обёрнутый в try/catch (autoservice может быть недоступен, не должен ломать sync) | catch-up trigger |
| `service/TrackingService.kt` (DiPars polling loop, найти grep'ом по `chargeTracker.onData`) | Удалить вызов | B |
| `service/TrackingService.kt` (onDestroy/onTaskRemoved) | Удалить `chargeTracker.forceEnd(...)` | B |
| `service/TrackingService.kt` (новый поле + tick) | Опционально (Phase 2): запустить `autoserviceChargingDetector.runLiveTick()` каждые 10 сек если `settings.isAutoserviceEnabled() && ignition.value == ON && client.isAvailable()` | live ветка |

### 5.3 Welcome / Settings cleanup

| Файл | Что | Why |
|---|---|---|
| `ui/welcome/WelcomeViewModel.kt:97` | Удалить `diPlusDbReader.importChargingLog()` | подтверждено |
| `ui/settings/SettingsViewModel.kt:374` | Удалить `val result = diPlusDbReader.importChargingLog()` (и связанный UI button если есть) | подтверждено |
| `ui/settings/SettingsViewModel.kt:641` | Удалить `"ChargeTracker:*"` из logcat tag списка | B |
| `data/remote/DiPlusDbReader.kt:121` | Удалить метод `importChargingLog(): ImportResult` целиком | B |

### 5.4 Settings UI — секция «Системные данные (экспериментально)»

`ui/settings/SettingsScreen.kt` — вставка **между строкой 303 и 304** (между секцией «Источник данных поездок» и секцией «Данные»). Это тематически «откуда берём данные» (Andy-уточнение в handoff'е).

```kotlin
SectionHeader(text = "Системные данные (экспериментально)")
Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = CardSurface),
    modifier = Modifier.fillMaxWidth()
) {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Расширенные данные с машины: SoH батареи, истинный пробег от BMS, статистика зарядок. Только чтение.",
            color = TextSecondary,
            fontSize = 12.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Включить", color = TextPrimary, fontSize = 14.sp)
            Switch(
                checked = state.autoserviceEnabled,
                onCheckedChange = { viewModel.setAutoserviceEnabled(it) }
            )
        }
        // 4 status states (см. ниже)
        AutoserviceStatusText(state.autoserviceStatus)

        if (state.autoserviceEnabled) {
            Row(...) {
                Text("Спрашивать после каждой зарядки", ...)
                Switch(checked = state.chargingPromptEnabled, ...)
            }
        }
    }
}
```

`AutoserviceStatusText` рендерит один из 4 текстов (handoff секция Visualization):

| State | Условие | Текст | Цвет |
|---|---|---|---|
| A | `!enabled` | (только описание + toggle, без статуса) | — |
| B | `enabled && !connected` | `✗ не подключено • проверь что Wi-Fi на DiLink включён` | TextMuted |
| C | `enabled && connected && hasReadings` | `✓ подключено\n   SoH 100% • lifetime 2091 км / 611 кВт·ч` | AccentGreen |
| D | `enabled && connected && allSentinel` | `⚠ подключено, но данные не читаются\n   возможно функция работает только на Leopard 3` | SocYellow |

Source-of-truth для `connected` / `hasReadings` / `allSentinel` — `BatteryStateRepository.state.collectAsState()`.

**SettingsViewModel** (новые поля в `SettingsUiState`):
- `autoserviceEnabled: Boolean = false` (из `KEY_AUTOSERVICE_ENABLED`)
- `chargingPromptEnabled: Boolean = true` (из `KEY_CHARGING_PROMPT_ENABLED`, default true)
- `autoserviceStatus: AutoserviceStatus` (sealed class A/B/C/D)

**SettingsRepository новые keys:**
- `KEY_AUTOSERVICE_ENABLED` (default `"false"`)
- `KEY_CHARGING_PROMPT_ENABLED` (default `"true"`)
- `KEY_AUTOSERVICE_BASELINE_KWH` / `KEY_AUTOSERVICE_BASELINE_TS` (для cold-start baseline до первой autoservice-сессии в БД)
- `KEY_LAST_SEEN_SOC` (для delta SOC триггера, обновляется на каждом catch-up)

### 5.5 Dashboard — TopBar warning + кликабельная BatteryCard

| Файл | Где | Что |
|---|---|---|
| `ui/dashboard/DashboardScreen.kt:389-427` (TopBar) | Сигнатуру `private fun TopBar(isServiceRunning: Boolean, diPlusConnected: Boolean)` расширить третьим параметром `adbConnected: Boolean? = null` (null = autoservice toggle OFF, не показывать warning). После строки 412 — зеркальный блок: `if (isServiceRunning && adbConnected == false) { Text("ADB не отвечает", color = SocYellow, fontSize = 12.sp); Spacer(...) }` | TopBar warning (handoff раздел «TopBar warning») |
| `ui/dashboard/DashboardScreen.kt:72` | Передать третий параметр `adbConnected = state.adbConnected` | следствие |
| `ui/dashboard/DashboardViewModel.kt` | Новое поле `adbConnected: Boolean? = null` в DashboardUiState. Источник — `BatteryStateRepository.autoserviceAvailable` (или null если toggle OFF) | следствие |
| `ui/dashboard/DashboardScreen.kt` (BatteryCard, найти grep'ом) | Обернуть в `Modifier.clickable { onNavigateBatteryHealth() }` | D решение |
| `ui/dashboard/DashboardScreen.kt:56` | Подпись `fun DashboardScreen()` → `fun DashboardScreen(onNavigateBatteryHealth: () -> Unit = {})` | следствие |
| `ui/navigation/AppNavigation.kt:208` | `composable(Screen.Dashboard.route) { DashboardScreen(onNavigateBatteryHealth = { navController.navigate("battery_health") }) }` | следствие |

### 5.6 Battery Health Screen — поправить empty state

| Файл | Где | Что |
|---|---|---|
| `ui/battery/BatteryHealthScreen.kt:54` | `"Нет данных. Информация появится после зарядок с подключённым DiPlus."` → `"Нет данных. Первый замер появится после полной зарядки (при включённых системных данных в Настройках)."` | D решение, актуализация |

### 5.7 Возврат таба «Зарядки»

| Файл | Где | Что |
|---|---|---|
| `ui/navigation/AppNavigation.kt:65-70` | В `enum class Screen` добавить **между Trips и Automation**: `Charges("charges", "Зарядки", Icons.Outlined.BatteryChargingFull)` | C решение, 5 табов |
| `ui/navigation/AppNavigation.kt:24-27` | Добавить `import androidx.compose.material.icons.outlined.BatteryChargingFull` | следствие |
| `ui/navigation/AppNavigation.kt:198-214` (NavHost) | Добавить `composable(Screen.Charges.route) { ChargesScreen() }` | следствие |
| `ui/navigation/AppNavigation.kt:62` | Добавить `import com.bydmate.app.ui.charges.ChargesScreen` | следствие |

### 5.8 ChargesScreen — 4 fallback состояния (handoff late-session уточнение)

`ui/charges/ChargesScreen.kt` — переписать early-return блок «Charges list» на 4 состояния. **Сам ViewModel + summary + фильтры остаются без изменений.**

Новый стейт в `ChargesViewModel.uiState`:
- `autoserviceEnabled: Boolean` (из SettingsRepository)
- `autoserviceConnected: Boolean` (из BatteryStateRepository)
- `autoserviceAllSentinel: Boolean` (из BatteryStateRepository)

Логика рендера:
```kotlin
when {
    !autoserviceEnabled && charges.isEmpty()     → EmptyState_OnboardingPrompt  // (1)
    !autoserviceEnabled && charges.isNotEmpty()  → BannerNotTracking + List     // (2)
    autoserviceEnabled && allSentinel            → EmptyState_NotSupported      // (4)
    autoserviceEnabled                           → List (current behavior)      // (3)
}
```

(1) `"Статистика зарядок недоступна. Чтобы видеть кВт·ч и стоимость каждой зарядки — включи "Системные данные" в Настройках."` + `[ Перейти в Настройки ]` button (callback `onNavigateSettings: () -> Unit`).

(2) баннер сверху списка: `"Новые зарядки сейчас не отслеживаются. [Включить →]"`.

(4) `"На вашей модели машины статистика зарядок недоступна — диагностические данные не читаются. SoH тоже не показывается."`.

`ChargesScreen` подпись расширяется: `fun ChargesScreen(onNavigateSettings: () -> Unit = {}, viewModel: ChargesViewModel = hiltViewModel())`.

В `AppNavigation.kt`:
```kotlin
composable(Screen.Charges.route) { ChargesScreen(onNavigateSettings = { navController.navigate(Screen.Settings.route) }) }
```

### 5.9 build.gradle.kts (deps)

| Файл | Где | Что |
|---|---|---|
| `app/build.gradle.kts:117` (после suncalc) | `implementation("com.cgutman:adblib:1.0.0")` (или последняя версия с MavenCentral) — Phase 1 пробуем; если не подходит — fallback свой код в `data/autoservice/` без новой dep | ADB-on-device |

## 6. Что НЕ делаем в этой ветке (фиксируем)

(Скопировано из handoff'а + добавлено по ходу design'а)

- WRITE на autoservice (любой `setInt`) — никогда без отдельного кейс-обсуждения
- Buffer reads (transact 9) — VIN, Version-getter'ы, raw radar — нужен in-process Binder, отложено
- Keep-alive watchdog для live charging — никогда (правило `feedback_system_sdk_safety.md`)
- Live charging power/capacity мониторинг при спящем DiLink — невозможно без keep-alive
- Multi-vehicle support
- Геопривязка тарифов — Andy: AC/DC классификации достаточно
- Включение Wi-Fi через `svc wifi enable` — write на system services
- Журнал ADB команд в UI — Andy: не перегружать пользователя
- Pairing wizard — Andy: всё просто и эффективно, статус-строка достаточно
- Status badge цветной в TopBar — выбран yellow warning текст как у DiPlus (вариант I)
- Avg для Period="Всё" из BMS — выбран наш расчёт (точнее)
- Дедупликация старых DiPlus charges с новыми autoservice — старый ChargeTracker выпиливается, дубликатов не будет

## 7. Phasing

### Phase 1 — каркас autoservice + детектор (без UI)
- `data/autoservice/` все 6 файлов
- `data/charging/` все 3 файла
- `domain/battery/BatteryStateRepository.kt`
- DI providers
- Migration 11→12 + cleanup
- Удаление ChargeTracker + importChargingLog
- TrackingService catch-up wiring (без live tick)
- Unit-тесты:
  - `SentinelDecoderTest` (карта sentinel → null, edge cases)
  - `ChargingTypeClassifierTest` (gun_state mapping, kwh/h heuristic)
  - `AutoserviceChargingDetectorTest` (catch-up state machine с моками)
  - `ChargingBaselineStoreTest` (MAX query + cold-start fallback)
  - `MigrationTest11to12` (Room schema test: ALTER columns + DELETE SUSPENDED)

**Acceptance:** на Mac прогон `./gradlew :app:testDebugUnitTest` зелёный + ручной smoke на машине: toggle ON → catch-up при service start создаёт COMPLETED ChargeEntity с реальной delta lifetime_kwh, новый baseline записан.

### Phase 2 — UI: Settings, Dashboard, Charges tab fallback
- Settings секция «Системные данные» + 4 status текста
- DashboardViewModel + TopBar warning
- BatteryCard кликабельность
- BatteryHealthScreen empty-state поправка
- Возврат таба «Зарядки» в навигацию (5 табов)
- ChargesScreen 4 fallback состояния

**Acceptance:** ручной прогон на машине через все 4 fallback состояния (toggle OFF без сессий → OFF с сессиями → ON working → ON sentinel-эмуляция).

### Phase 3 — Optional finalize prompt + live tick (опционально)
- `ChargingFinalizePromptDialog` + ChargingPromptBus + MainActivity wiring
- `runLiveTick` с интервалом 10 сек (если ignition ON и client.isAvailable())
- ChargePoint каждые 30 сек на live ветке

**Acceptance:** реальная зарядка от пистолета AC + ignition ON → ACTIVE сессия в БД с ChargePoint'ами + после finalize — диалог с правкой → save.

### Phase 4 — Codex audit + release v2.5.0
- Codex CLI rescue review всей ветки (по `feedback_codex_audit_before_release.md` — ДО релиза)
- Squash-merge в main
- Bump version 2.4.12 → 2.5.0 (минор — новая фича)
- APK подпись + GH release с release notes plain text (по `feedback_release_notes_no_markdown.md`, по-русски, без техники)

## 8. Risk register

| Риск | Митигация |
|---|---|
| `com.cgutman:adblib` не работает (старый, заброшен, или не поддерживает наш сценарий) | Phase 1 пробуем сразу, при провале — порт `EV Pro AdbClient.java` (декомпил готов в `.research/competitor/`). +1-2 дня worst case |
| RSA pairing не пройдёт автоматически (DiLink требует подтверждения «Allow ADB?») | Andy подтверждает один раз через DiLink dialog, ключ persistent в Android Keystore — больше не спросят. Если нет dialog'а вообще → fallback на «включи в Developer settings вручную» с инструкцией |
| `LIFETIME_KWH` нестабилен (как было с `LIFETIME_MILEAGE` в одной из read-сессий) | SentinelDecoder вернёт null → детектор `runCatchUp` залогирует и пропустит. Baseline не обновляется, ждём следующего service start |
| Catch-up детектит «фантомную» зарядку при первом запуске (до записи baseline) | Cold-start path: если baseline нет в БД и нет в k/v — записываем текущий lifetime_kwh как baseline без создания сессии. Логируем как «baseline init» |
| Дублирование сессии (юзер отключил toggle, потом включил → catch-up видит большой delta) | Baseline всё равно сохраняется через k/v `KEY_AUTOSERVICE_BASELINE_TS` — при reactivate, если timestamp baseline < N часов, доверяем; иначе — cold-start init |
| Race между `runCatchUp` и `runLiveTick` | Mutex на `AutoserviceChargingDetector.state`, оба пути идут через одну state-машину |
| Юзер выключает toggle во время ACTIVE live сессии | onChange → `runLiveTick` job cancel + `finalizeIfActive()` → сессия записывается как обычно |
| ChargeTracker удаление сломает что-то у юзеров с suspended сессиями в БД | Миграция `DELETE WHERE status IN ('SUSPENDED','ACTIVE')` чистит. Завершённые сохраняются. Андроид Room migration test проверит на dummy данных |
| Тестовая прошивка обновится и поломает fid'ы | Доминирующий read-only режим + sentinel decoder graceful fallback. `BatteryStateRepository.autoserviceAvailable` станет false → UI вернётся в v2.4.12-mode автоматически |

## 9. Что нужно от Andy для Phase 1

Ничего — все open questions из секций 1-3 закрыты:
- Q1=a (реюз ChargeEntity) ✅
- Q2 (читать legacy charges) ✅ — прочитано
- Q3=да (миграция 11→12) ✅ — содержание расписано в 5.1
- Q4=ok (BatteryReading вместо BatterySnapshot) ✅
- A=миграция нужна с 4 полями ✅
- B=выпилить ChargeTracker полностью + миграция cleanup ✅
- C=5 табов ✅
- D=кликабельная BatteryCard → BatteryHealthScreen ✅
- E=toggle prompt в Settings, default ON ✅
- importChargingLog=удалить ✅

## 10. Self-review checklist (перед началом Phase 1)

- [x] Все file:line якоря верифицированы grep/Read
- [x] Все имена классов уникальны и не конфликтуют (BatteryReading vs BatterySnapshotEntity)
- [x] WRITE-protection (regex barrier) описан и обоснован
- [x] Sentinel-карта вынесена в SentinelDecoder (не размазана по детектору)
- [x] State machine catch-up + live на одной mutex-protected state
- [x] Baseline storage избегает singleton k/v как primary (используем БД через MAX query)
- [x] Все 4 fallback состояния Charges tab продуманы
- [x] Все 4 status states Settings продуманы
- [x] Удаление старого кода имеет cleanup в БД миграции
- [x] Phasing разбит на 4 независимых проверяемых блока
- [x] Risk register покрывает все известные failure modes
