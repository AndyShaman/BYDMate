# Adaptive Range & Consumption — Design Spec

**Версия:** v2.5.0
**Дата:** 2026-04-24 (rev. 2 — закрыты open questions, заменён Δtime-фильтр на sessionId boundary)
**Статус:** approved, готов к имплементации

---

## 1. Почему это нужно

Сейчас на виджете и Dashboard три числа — SOC%, остаточный пробег (range), расход + стрелка тренда. Каждое считается на своём входе, из-за чего они расходятся между собой и с итогом закрытой поездки.

### 1.1 Симптомы

1. **Range «застревает».** Проехал 3–4 км, а цифра 203 км не меняется, затем скачок сразу на ~4 км вниз.

   Причина: `SOC` в DiPars — `int` с шагом 1%. Для Leopard 3 (72.9 кВт·ч) 1% SOC ≈ 0.73 кВт·ч ≈ 4 км пробега при расходе 18 кВт·ч/100км. Пока SOC не щёлкнет — формула `SOC × cap / ema × 100` возвращает константу.

2. **Скачок range на ignition-off/on.** Владелец заглушил машину с цифрой 203, сразу завёл — 191.

   Причина: в `TrackingService.onCreate` → `historyImporter.runSync()` подтягивается свежая поездка из energydata, `insertTrip` инвалидирует `cachedEmaConsumption` в `TripRepository`. Если поездка была тяжелее среднего (холодный старт, прогрев, короткая дистанция), EMA сдвигается вверх на 3–7%, range падает на 10–15 км.

3. **Расход на виджете не совпадает с итогом поездки.** Ехал 6 км, виджет показывал 22 кВт·ч/100км (rolling-5-km зелёным), список Поездок записал 33 кВт·ч/100км.

   Причина: `ConsumptionAggregator.displayValue` использует rolling 5-км по пробегу. На короткой поездке с 10-минутным прогревом (HVAC без движения) rolling «не видит» прогрев — он вне `Δmileage`. Cumulative trip видит всё → 33. Физически оба числа корректны, но отвечают на разные вопросы, а виджет подаёт rolling как главное число.

### 1.2 Что подтвердил ресерч

- **Tesla** разделяет два числа: «рядом с SOC» — EPA-rated прокси (не адаптивный, не прыгает) + «Energy app projected» — rolling 5/15/30 mile. Instant считается бесполезным.
- **BMW i3, Hyundai Ioniq 5** — GOM = SOC × average за ~20 миль **по одометру** (не по поездкам, не по сессиям). Адаптация медленная (~300 миль до полного переобучения).
- **Общий вывод:** правильный горизонт — **rolling по одометру** длиной 25–50 км. Короткий window (rolling 5 км) — слишком дёрганый и искажает короткие поездки с прогревами. Длинный (EMA 20 поездок) — дискретный и скачет на каждой новой записи.

---

## 2. Цели и не-цели

### Цели

- **Range плавно уменьшается** по мере пробега — как у штатки, но честно (не врёт вверх).
- **Range не скачет на ignition-on/off** — непрерывен через циклы питания.
- **Расход на виджете близок к итогу поездки** — не расходится на 30–50% как сейчас.
- **Стрелка тренда остаётся быстрой** — показывает «сейчас лучше/хуже среднего».
- **Один источник truth** для range/расхода/стрелки — меньше ручной синхронизации.
- **Работает одинаково на Leopard 3 и Song** — без костылей.

### Не-цели

- Не делаем навигационный прогноз «хватит ли доехать до точки с учётом рельефа/погоды». Это отдельная задача.
- Не считаем true SoH через OBD. Это всё ещё вне доступа.
- Не меняем UI виджета (7 полей остаются, layout v3 как есть).
- Не меняем ментальную модель пользователя «цифра + стрелка» — просто делаем её честнее.

---

## 3. Архитектура

### 3.1 Новый класс `OdometerConsumptionBuffer`

Central rolling-window calculator. **Один на всё приложение** (`@Singleton`).

**Ответственность:** собирать пары `(mileage_км, totalElec_кВт·ч)` через DiPars live-поток, держать их на последние **25 км пробега одометра**, выдавать `recent_avg_consumption` (кВт·ч/100км).

**Persistent:** буфер сохраняется в Room (новая таблица `odometer_samples`), чтобы переживать ignition-off, sys-kill и рестарт приложения. Непрерывный одометр = непрерывный буфер.

**API:**

```kotlin
@Singleton
class OdometerConsumptionBuffer @Inject constructor(
    private val dao: OdometerSampleDao,
    private val fallbackEmaProvider: suspend () -> Double  // TripRepository.getEmaConsumption
) {
    /**
     * Update on every DiPars tick. Idempotent on duplicate mileage/elec.
     * @param sessionId монотонный идентификатор ignition cycle
     *   (= TrackingService.sessionStartedAt). Используется как boundary —
     *   пары снимков из разных сессий пропускаются при расчёте среднего.
     */
    suspend fun onSample(
        mileage: Double?,
        totalElec: Double?,
        socPercent: Int?,
        sessionId: Long?,
        isCharging: Boolean
    )

    /** kWh/100km. Returns fallback EMA if usable distance < MIN_BUFFER_KM. */
    suspend fun recentAvgConsumption(): Double

    /** Short-horizon (2 km) for trend arrow. Null if usable distance < SHORT_WINDOW_KM. */
    suspend fun shortAvgConsumption(): Double?

    /** For debugging / summary logs. */
    suspend fun status(): BufferStatus
}
```

**Константы:**

- `WINDOW_KM = 25.0` — длина rolling-окна для recent avg (закрыто 2026-04-24).
- `SHORT_WINDOW_KM = 2.0` — окно для стрелки тренда (закрыто 2026-04-24).
- `MIN_BUFFER_KM = 5.0` — ниже этого `recentAvg` возвращает fallback EMA.
- `MIN_MILEAGE_DELTA = 0.05` — пишем новый snapshot только если одометр сдвинулся на ≥ 50 м от предыдущего snapshot'а ИЛИ изменился sessionId. Защищает от тысяч снапшотов с одинаковым mileage в простое.
- `MAX_BUFFER_ROWS = 500` — hard cap, trim самых старых при превышении (страховка).

**Алгоритм `recentAvgConsumption()`:**

```
samples = dao.windowFrom(newest.mileage - WINDOW_KM)  // ASC по mileage
if samples.size < 2: return fallbackEmaProvider()

totalKm = 0; totalKwh = 0
for i from 1 to samples.lastIndex:
    prev = samples[i-1]; cur = samples[i]
    if prev.sessionId != cur.sessionId: continue   // ignition cycle boundary — skip pair
    if cur.totalElecKwh == null || prev.totalElecKwh == null: continue   // SOC_BASED взаимоисключения покрываются ниже
    dKm  = cur.mileageKm - prev.mileageKm
    dKwh = cur.totalElecKwh - prev.totalElecKwh
    if dKm <= 0 || dKwh < 0: continue              // glitch / regen — skip
    totalKm += dKm; totalKwh += dKwh

if totalKm < MIN_BUFFER_KM: return fallbackEmaProvider()
return totalKwh / totalKm * 100
```

`shortAvgConsumption()` — то же самое, но окно `SHORT_WINDOW_KM`. Возвращает `null`, если суммарный валидный `totalKm < SHORT_WINDOW_KM`.

**Почему sessionId, а не Δtime:**

- Внутри одной сессии (ignition on → off) DiPars живой, любые простои с HVAC, прогревы, заторы — это **реальный расход батареи**, должен учитываться. Фильтрация по Δtime выкинула бы их и заниженно показала real-world consumption.
- Между сессиями (машина выключена 12 часов: ночной BMS drain или зарядка на Wall Box) — переход через эту «дыру» не имеет физического смысла как «расход на езду». Скрипт пропускает только эту cross-session пару, остальные snapshots обеих сессий продолжают участвовать в расчёте.
- Зарядка всегда происходит при ignition off → автоматически попадает в boundary, отдельный SOC-rise detection не нужен.

### 3.2 Новый класс `RangeCalculator`

**Ответственность:** считать `estimatedRangeKm` по одной формуле для Dashboard и виджета.

**API:**

```kotlin
@Singleton
class RangeCalculator @Inject constructor(
    private val buffer: OdometerConsumptionBuffer,
    private val settings: SettingsRepository,
    private val socInterpolator: SocInterpolator
) {
    /** Plain estimate — for Dashboard recomputes on every DiPars tick. */
    suspend fun estimate(soc: Int?, totalElec: Double?): Double?
}
```

**Формула:**

```
remaining_kwh = SOC × cap / 100 - socInterpolator.carryOver(totalElec, soc)
range_km      = remaining_kwh / recentAvgConsumption × 100
```

### 3.3 `SocInterpolator` — сглаживание между SOC-ступенями

**Ответственность:** убирать 4-км ступеньки SOC, считая «сколько уже съели после последнего SOC-шага».

**Логика:**

- При каждом tick'е получает `(soc, totalElec)`.
- Если `soc` изменился (сравнивая с предыдущим tick'ом того же сеанса приложения) → запоминает `totalElecAtSocChange = totalElec`.
- Возвращает `carryOver = totalElec - totalElecAtSocChange` (сколько кВт·ч потрачено после последнего щелчка SOC).
- При рестарте приложения помнит `totalElecAtSocChange` через SharedPreferences (достаточно одного значения).

**Почему это работает:** между SOC-шагами `carryOver` монотонно растёт от 0 до ~0.73 кВт·ч (на Leopard 3 с его 1% = 0.73 кВт·ч). В момент SOC-щелчка `SOC × cap/100` падает на 0.73, `carryOver` сбрасывается в 0 — `remaining_kwh` плавно, без скачка. Цифра range уменьшается непрерывно.

**Sanity-check:** если `carryOver` превысил `2 × (cap/100)` — значит пропустили SOC-change (например, после длительного сна приложения). Сбрасываем в 0.

### 3.4 Интеграция с `ConsumptionAggregator`

`ConsumptionAggregator` **остаётся** — но упрощается. Его новая роль: считать **только** `shortAvgConsumption` и `trend` на основе `OdometerConsumptionBuffer`. Старая session-based логика (ignition-on якорь, cumulative до 5 км, rolling после) — убирается. Session-концепция остаётся только для UI «время поездки» на виджете (`sessionStartedAt` в `TrackingService`), она не участвует в расчёте расхода.

**Новая цифра расхода на виджете** = `recentAvgConsumption` из буфера (а не session cumulative и не 5-км rolling).

**Стрелка** сравнивает `shortAvgConsumption` (последние 2 км) с `recentAvgConsumption` (25-км среднее):

- `ratio = short / recent`
- гистерезис: DOWN ≤ 0.90, UP ≥ 1.10 (шире, чем сейчас 0.95/1.05 — потому что short 2 км и так волатилен)
- debounce 30 сек (меньше, чем 60 — short уже сам по себе реагирует быстрее)

### 3.5 Data source: Leopard vs Song

**Ключевое:** rolling buffer живёт на **DiPars live-потоке** (`mileage` = param 3 `里程`, `totalElec` = param 32 `总电耗`). Он **не зависит** от `SettingsRepository.DataSource` toggle (energydata vs DiPlus TripInfo). Этот toggle влияет только на `HistoryImporter` и `TripRepository.getEmaConsumption()`.

**Fallback EMA** — читается через `TripRepository.getEmaConsumption()`, который уважает тогл. То есть на Song fallback считается по DiPlus TripInfo SOC-delta (менее точно, ~1 кВт·ч grain), но это только fallback — буфер быстро накопится за первые 25 км живого пробега и дальше работает одинаково.

**Feature detection для `totalElec` на Song:** в `DiParsClient.parse()` уже возвращается null если параметр отсутствует. Если первые ~3 минуты работы приложения `totalElec` всегда null или постоянно 0 — включаем **SOC-based режим** буфера: пара становится `(mileage, soc × cap / 100)`. Разрешение ухудшается (шаги 0.73 кВт·ч), но работает.

```kotlin
enum class BufferMode { TOTAL_ELEC, SOC_BASED }
```

Режим выбирается автоматически при первом заполнении буфера, сохраняется в SharedPreferences, пересматривается раз в сутки.

**Sanity-check согласованности.** Логируем раз в 5 минут: `Δ(SOC × cap/100) vs Δ(totalElec)` на одном и том же интервале пробега. Если систематическое отклонение > 20% — это сигнал, что `totalElec` у BYD считает не то, что мы думаем (например, только traction). В этом случае в дизайн-доке **зафиксировано**: переключаемся на SOC-based, эмитим warning в logcat.

### 3.6 Persistence-модель

**Новая Room-таблица `odometer_samples`:**

```kotlin
@Entity(
    tableName = "odometer_samples",
    indices = [Index("mileageKm"), Index("sessionId")]
)
data class OdometerSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mileageKm: Double,
    val totalElecKwh: Double?,   // null в SOC_BASED режиме
    val socPercent: Int?,         // снимок SOC для калибровки
    val sessionId: Long?,         // = TrackingService.sessionStartedAt; null при unknown
    val timestamp: Long
)
```

**DAO:**

```kotlin
@Dao
interface OdometerSampleDao {
    @Insert suspend fun insert(sample: OdometerSampleEntity): Long

    /** Last sample by id (id monotonic since autoGenerate). */
    @Query("SELECT * FROM odometer_samples ORDER BY id DESC LIMIT 1")
    suspend fun last(): OdometerSampleEntity?

    /** All samples where mileage >= (newest mileage - WINDOW_KM). ASC by mileage. */
    @Query("""
        SELECT * FROM odometer_samples
        WHERE mileageKm >= :minMileage
        ORDER BY mileageKm ASC
    """)
    suspend fun windowFrom(minMileage: Double): List<OdometerSampleEntity>

    @Query("DELETE FROM odometer_samples WHERE mileageKm < :cutoff")
    suspend fun trimBelow(cutoff: Double)

    /** Hard cap fallback — удаляем самые старые при превышении MAX_BUFFER_ROWS. */
    @Query("""
        DELETE FROM odometer_samples WHERE id IN (
            SELECT id FROM odometer_samples ORDER BY id ASC LIMIT :howMany
        )
    """)
    suspend fun deleteOldest(howMany: Int)

    @Query("SELECT COUNT(*) FROM odometer_samples") suspend fun count(): Int
}
```

**Миграция БД: 10 → 11.** Добавляется таблица `odometer_samples`. Старые данные не ломаются.

**Дополнительные SharedPreferences ключи** (`widget_prefs` или новая `range_prefs`):

- `soc_interpolator_last_soc` — последний виденный SOC
- `soc_interpolator_total_elec_at_change` — `totalElec` на момент последнего SOC-шага
- `buffer_mode` — `TOTAL_ELEC` или `SOC_BASED`
- `buffer_mode_locked_at` — timestamp для ежесуточного пересмотра

### 3.7 Граф зависимостей

```
DiParsClient.fetch()  ──►  TrackingService.pollingLoop
                               │
                               ├──► OdometerConsumptionBuffer.onSample
                               │         │
                               │         └──► OdometerSampleDao (Room)
                               │
                               ├──► SocInterpolator.onSample
                               │
                               ├──► RangeCalculator.estimate
                               │         ├──► OdometerConsumptionBuffer.recentAvgConsumption
                               │         └──► SocInterpolator.carryOver
                               │
                               └──► ConsumptionAggregator.onSample  (упрощён)
                                         └──► буфер.recentAvgConsumption + shortAvgConsumption

DashboardViewModel ────────►  RangeCalculator.estimate (на каждый tick DiPars)
WidgetController ─────────►   RangeCalculator.estimate (через TrackingService flow)
```

---

## 4. Edge cases

### 4.1 Зарядка

Во время активной зарядки `totalElec` может расти (регенерация, внутренние нужды) или падать (BYD считает заряд как negative consumption — непроверено). В любом случае семплы, снятые во время зарядки, **загрязняют** буфер.

**Решение:** при `chargeGunState > 0` или `chargingStatus == 2 (Started)` — **пропускаем** `onSample`. Буфер ставится на паузу.

### 4.2 Odometer regression

Если `mileage_now < mileage_previous` (glitch DiPars или одометр откатился после сервиса) — пропускаем tick.

Если разница > 100 км (нереальный скачок вперёд) — пропускаем и логируем warning.

### 4.3 Смена `batteryCapacity` в Settings

Пользователь поменял ёмкость (например, установил 82.56 по ошибке, потом исправил на 72.9). Буфер **не сбрасывается** — он хранит `(mileage, totalElec)` без привязки к cap. Меняется только `RangeCalculator.estimate` → пересчитывает `remaining_kwh` с новой cap на следующем tick'е.

### 4.4 Первая установка / после reset

Буфер пуст. `recentAvgConsumption` возвращает `fallbackEmaProvider()` (`TripRepository.getEmaConsumption`). При достижении `MIN_BUFFER_KM = 5` переключается на свой расчёт. Плавно, без скачка.

### 4.5 Долгий простой (сутки+) с HVAC выключенным

DiLink при ignition off не работает — DiPars молчит, никаких tick'ов мы не получаем. Утром при ignition on `TrackingService` создаёт **новый sessionStartedAt** (поскольку powerState 0→1). Первый snapshot новой сессии получит свежий `sessionId`.

В буфере получится:
- `(mileage=10000.0, totalElec=8500.0, sessionId=1730000000)` — последний вечерний snapshot
- `(mileage=10000.05, totalElec=8501.2, sessionId=1730043200)` — первый утренний snapshot

Алгоритм `recentAvgConsumption()` встретит `prev.sessionId != cur.sessionId` → **пропустит эту пару**. Ночной BMS drain (1.2 кВт·ч) и пройденные за ночь 50 м не попадут в средний расход. Снимки обеих сессий продолжат участвовать каждый по своему — пары внутри вчерашней сессии и пары внутри сегодняшней.

Никаких Δtime-фильтров не нужно — sessionId сам по себе закрывает кейс.

### 4.6 Sys-kill процесса

Буфер persist в Room → переживает. `SocInterpolator.totalElecAtSocChange` persist в SharedPreferences → переживает. На первом tick'е после рестарта — продолжаем с того же места.

### 4.7 SOC «прыгает» вверх (регенерация, baseline drift)

Внутри живой сессии `soc_now > soc_prev` бывает только при регенеративном торможении (мелкий импульс, ≤ 1%). Это реальное возвращение энергии в батарею, отражается естественно: `totalElec` тоже может на чуть-чуть просесть, либо остаться. В формуле `recentAvg` пара с `dKwh < 0` пропускается (см. алгоритм в 3.1) — отдельной обработки не нужно.

Зарядка (соответственно SOC up на десятки процентов) всегда происходит при ignition off → следующий snapshot будет уже в новой сессии → sessionId boundary автоматически выкидывает пару из расчёта. Отдельный SOC-rise detection не требуется.

Для `SocInterpolator`: внутри сессии при `soc_now > soc_prev` обновляем `totalElecAtSocChange = totalElec_now` (сбрасываем carryOver в 0, потому что физическая референс-точка сместилась). При смене сессии `SocInterpolator` тоже сбрасывается на первом tick'е новой сессии.

### 4.8 Отсутствующие поля DiPars

- `soc == null`: `RangeCalculator.estimate` возвращает null (виджет/Dashboard показывают прочерк).
- `mileage == null`: буфер пропускает tick.
- `totalElec == null` в режиме `TOTAL_ELEC`: если 3+ tick'а подряд null — переключаем режим в `SOC_BASED`.

---

## 5. UI-изменения

### 5.1 Виджет (`FloatingWidgetView`)

**Что меняется:** цифра расхода (центральная ячейка, row 2) теперь `recentAvgConsumption` (25-км среднее) вместо rolling 5 км session.

**Что остаётся:** layout v3 (260×108), SOC% + km + расход с иконкой тренда, 3 строки, status colour для border + SOC + расход.

**Визуально** цифра расхода станет более стабильной, в районе 18–30 в обычной езде, без резких скачков. Range уменьшается плавно, не застревает.

### 5.2 Dashboard

**Что меняется:** `DashboardViewModel.calculateRange` заменяется вызовом `RangeCalculator.estimate`. Cache `recentAvgConsumption` в ViewModel убирается — теперь это ответственность `OdometerConsumptionBuffer` (и он сам оптимизирован по IO через in-memory cache + периодический flush в Room).

**UI не меняется.** Только цифра становится более плавной.

### 5.3 Notifications (foreground service)

`TrackingService.updateNotification` уже использует `estimateRangeKm` — меняется на `RangeCalculator.estimate`.

### 5.4 Settings

Ничего не добавляем. Buffer mode (TOTAL_ELEC/SOC_BASED) и прочее — hidden, авто-detectится.

---

## 6. Миграция старого кода

### 6.1 Что удаляется

- `DashboardViewModel.calculateRange` → заменяется на `RangeCalculator.estimate`.
- `DashboardViewModel.recentAvgConsumption` + `loadRecentAvgConsumption` — больше не нужно.
- `TrackingService.estimateRangeKm`, `cachedEmaConsumption`, `cachedBaselineEma`, `cachedBatteryCapacity`, `refreshConsumptionCache`, `doRefreshConsumptionCache`, `maybeRefreshConsumptionCache` — уезжают в `RangeCalculator` + `OdometerConsumptionBuffer`.
- `ConsumptionAggregator.SessionBaseline`, `pendingBaseline`, persist-логика session в `SessionPersistence` — session-концепция для расхода больше не нужна. **Но `sessionStartedAt` остаётся** (для времени поездки на виджете + `fireOncePerTrip` в automation — это другая роль, не связанная с расходом).
- Метод `TripRepository.getWeeklyEmaConsumption` — остаётся для AI Insights, но из widget-path убирается.
- `TripRepository.getRecentTripsEmaConsumption` — остаётся как source для `fallbackEmaProvider`.

### 6.2 Что модифицируется

- `ConsumptionAggregator.onSample` — теперь тонкая обёртка над `OdometerConsumptionBuffer.shortAvgConsumption` для стрелки. `displayValue` больше не вычисляет cumulative/rolling — просто берёт `recentAvgConsumption` из буфера.
- `SessionPersistence` — остаётся для sessionStartedAt-якоря automation, но `SessionBaseline` с mileage/elec убирается.
- `AppModule` (Hilt) — добавляются провайдеры `OdometerConsumptionBuffer`, `RangeCalculator`, `SocInterpolator`.

### 6.3 Что НЕ трогаем

- `HistoryImporter` — работает как раньше, включая `runSync()` на старте. Он не знает про буфер.
- `TripRepository.getEmaConsumption` — остаётся для fallback и для AI Insights.
- `SettingsRepository.DataSource` toggle — работает как раньше, влияет только на HistoryImporter.
- Layout виджета, Dashboard UI, Settings UI — без изменений.

---

## 7. Тесты

### 7.1 Unit-тесты

**`OdometerConsumptionBufferTest`:**

- empty buffer returns fallback EMA
- < MIN_BUFFER_KM (валидный) returns fallback
- ≥ MIN_BUFFER_KM computes from buffer
- trim старых samples при росте mileage
- charging samples игнорируются (`isCharging=true` → no insert)
- odometer regression tick пропускается
- huge forward jump (>100 км) пропускается и warning'ится
- SOC_BASED fallback когда totalElec null 3+ tick'а
- short window (2 км) и recent window (25 км) считаются независимо
- persistent state выживает «рестарт» (DAO in-memory stub)
- **sessionId boundary**: пара снимков с разными sessionId пропускается, остальные суммируются — на синтетическом окне «10 км в session A → 5 км gap → 10 км в session B» recentAvg = средневзвешенное по 20 валидным км
- **HVAC-простой внутри сессии**: 5 км → snapshot с тем же mileage и +1 кВт·ч totalElec через 30 мин (одна сессия) → пара пропускается из-за `dKm <= 0`, расход не искажается
- **MIN_MILEAGE_DELTA**: tick'и с движением < 50 м не пишутся в буфер (защита от спама в простое с DiPars живой)

**`SocInterpolatorTest`:**

- carryOver монотонно растёт между SOC-шагами
- carryOver сбрасывается на SOC-щелчке (down)
- SOC up (регенерация малой) → carry может стать отрицательным, но ограничен
- SOC up (>2%) → баланс сбрасывается (charging missed)
- sanity cap: carryOver > 2× (cap/100) → сбрасывается

**`RangeCalculatorTest`:**

- null SOC → null
- SOC = 0 → 0 или null
- normal case: SOC=50, cap=72.9, avg=18 → ~202 км
- carryOver уменьшает range плавно
- смена cap в Settings сразу влияет на следующий вызов

**`ConsumptionAggregatorTest` (обновлённые 15 кейсов):**

- переписаны под новую роль: displayValue = buffer.recentAvg, shortAvg используется только для стрелки
- тренд debounce 30 сек, гистерезис 0.90/1.10
- старые кейсы про session baseline, prewarmup, rolling 5 km — удаляются (они больше не актуальны)

### 7.2 Integration-тесты (ручные)

1. **Ignition-on после холодной ночи:** range не отличается больше чем на 2 км от того, что было при ignition-off. Фиксируем в release-notes.
2. **Короткая поездка 6 км с прогревом:** расход в конце поездки на виджете ≈ итог поездки в списке (разница < 15%, было 30–50%).
3. **Длинная поездка 50+ км с разным стилем:** range плавно адаптируется, стрелка тренда корректно показывает «лучше/хуже среднего последних 25 км».
4. **Проезд 3–4 км в ровном режиме:** range уменьшается на ~3–4 км (не застревает, не прыгает).
5. **Смена batteryCapacity в Settings:** range мгновенно пересчитывается, буфер не теряется.
6. **Зарядка AC/DC:** буфер не заполняется во время зарядки, после завершения продолжает с предыдущей точки.
7. **Тест на Song (если есть тестовый доступ):** feature detection переключает в SOC_BASED, всё работает с худшим разрешением, но без ошибок.

### 7.3 Codex audit перед релизом

Как всегда — обязательный шаг до `gh release create`. Особое внимание:

- persistence race conditions (буфер + polling + onDestroy)
- precision loss в Float (используем Double везде, как в v2.4.4 fix)
- потенциальный leak Room коннекшенов
- буфер не читается синхронно на UI-потоке

---

## 8. План релиза

**Одним шагом в v2.5.0** (не разбиваем на v2.4.5 + v2.5.0 — пользователь предпочёл большой фикс сразу):

1. Room migration 10 → 11
2. `OdometerSampleDao` + entity
3. `OdometerConsumptionBuffer` + tests
4. `SocInterpolator` + tests
5. `RangeCalculator` + tests
6. `ConsumptionAggregator` refactor + updated tests
7. `TrackingService` integration (замена `refreshConsumptionCache` / `estimateRangeKm` / session baseline)
8. `DashboardViewModel` integration
9. `AppModule` Hilt bindings
10. Ручное тестирование (6 integration-кейсов выше)
11. Codex audit
12. Release notes на русском
13. Build, sign, release v2.5.0

**Ожидаемый объём:** ~15–20 файлов изменений, ~1500 строк кода + тестов. Это больше 3-файлового лимита из CLAUDE.md — именно поэтому идёт через spec → plan → execution с чекпоинтами.

---

## 9. Закрытые решения (2026-04-24)

Все open questions из предыдущей версии спеца обсуждены и закрыты:

1. **Длина recent window — 25 км.** Середина между Tesla 8/24/48 и BMW i3 ~32. После выпуска возможна корректировка по полевым данным, но в v2.5.0 идёт как константа.

2. **Short window для стрелки — 2 км.** Целевой сценарий пользователя — короткая поездка 6 км до работы с холодным стартом и прогревом. При 3 км стрелка активировалась бы только во второй половине, прогрев уже прошёл — нечего показывать. При 2 км стрелка появляется к началу «нормального» движения после прогрева. Дёрганье на светофорах гасится hysteresis (0.90/1.10) и debounce 30 сек.

3. **Никакого Δtime-фильтра простоев.** Заменено на **sessionId boundary** (см. 3.1 и 4.5). Внутри одной сессии все простои с HVAC учитываются как реальный расход. Между сессиями (ignition cycle, ночная стоянка, зарядка) пара снимков пропускается. Это:
   - Не выкидывает реальный HVAC-расход из живой сессии (как сделал бы Δtime-фильтр).
   - Автоматически покрывает зарядку без отдельного SOC-rise detection.
   - Не зависит от непредсказуемых параметров типа «считать ли 30 минут простой пропастью».

4. **Активация стрелки** — когда оба окна заполнены: `short ≥ 2 km` валидного движения и `recent ≥ 5 km` валидного движения (после фильтрации cross-session pair). Цифра расхода появляется при `recent ≥ 2 km`, иначе показываем fallback EMA.

5. **Дефолт `batteryCapacity` в `DashboardViewModel` → 72.9.** Текущее значение 38.0 — рудимент. Меняем как часть v2.5.0 (`SettingsRepository.DEFAULT_BATTERY_CAPACITY` уже 72.9, остаётся синхронизировать).

**Побочка (вне основной фичи, закоммичено в main как `chore`):** дефолты тарифов AC 0.30→0.20 и DC 0.50→0.73.

---

## 10. Что пользователь увидит в v2.5.0 relase notes

> **v2.5.0 — честный остаточный пробег и расход**
>
> Переделан расчёт остаточного пробега и текущего расхода. Теперь цифры на виджете и Dashboard считаются из единого 25-км rolling-окна по одометру — как у Tesla и Hyundai.
>
> **Что изменится на виджете:**
> - Цифра остаточного пробега плавно уменьшается по мере поездки (раньше застревала до щелчка SOC и прыгала на ~4 км).
> - При выключении и повторном включении машины цифра не скачет (раньше теряла 10–15 км).
> - Расход в кВт·ч/100км отражает реальный расход последних 25 км пробега, а не только движения — прогревы, простои с кондиционером, короткие отрезки учитываются честно. Цифра будет ближе к тому, что ты увидишь в итоге поездки в списке Поездок.
> - Стрелка тренда активируется, когда в окне накопилось достаточно данных, сравнивает последние 2 км со средним 25 км.
>
> Данные буфера живут в БД и переживают перезапуски, выключения, принудительные остановки.

---

## 11. Что пользователь **не увидит**, но стоит знать

- Новая Room-таблица `odometer_samples`, пара сотен строк на старте, trim по достижению WINDOW_KM.
- Feature detection для `totalElec` на Song: в первые 3 минуты работы приложение определяет режим (TOTAL_ELEC или SOC_BASED) и запоминает.
- Раз в 5 минут в logcat пишется `BufferStatus` — размер окна, среднее, режим, последний SOC-шаг. Это для диагностики, UI не показывает.
- Session-концепция для расхода **убрана**. Для времени поездки и automation `fireOncePerTrip` она остаётся.
