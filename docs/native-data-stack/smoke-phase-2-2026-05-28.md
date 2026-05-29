# Phase 2 — Operator Smoke Checklist (D+ removed)

> **When to run:** после установки APK `v2.9.0-pre` (с D+ uninstall'нутым). Гейт перед Group E (удалением `DiParsControlClient.kt` и финальным релизом).
> **Длительность:** ~30 мин в машине, IGN ON, режим P, в Wi-Fi покрытии (для VPS Alice + ABRP).
> **Pass criterion:** все пункты #0–#8 ✓. Любой ✗ блокирует E.2.

Перед стартом: машина — Leopard 3, IGN ON, P, заряд ≥30%, ADB-over-WiFi включён, `adb shell` готов.

---

## #0 — D+ uninstall + helper daemon visibility

**Pre:** D+ установлен (`com.van.diplus` в `pm list packages`). BYDMate v2.9.0-pre установлен.

**Action:**
```bash
adb shell pm uninstall com.van.diplus
adb shell am start-foreground-service -n com.bydmate.app/.service.TrackingService
sleep 5
adb shell ps -A | grep -E "bydmate_helper|aps_diplus|diplus"
```

**Expected:**
- `pm uninstall` → `Success`
- `ps -A` показывает `bydmate_helper` (shell uid). НЕ показывает `com.van.diplus` или `aps_diplus`.
- `logcat -d | grep HelperBootstrap` → строка `HelperBootstrap.ensureRunning → true` за последние 5 секунд (TrackingService.onCreate стартует daemon перед первым writem).

**Result:** [ ] PASS / [ ] FAIL

---

## #1 — App launches без ошибок после D+ uninstall

**Pre:** #0 пройден.

**Action:** запустить BYDMate на DiLink (вручную или `am start -n com.bydmate.app/.ui.MainActivity`).

**Expected:**
- Главный экран открывается. Нет диалога ошибки. Нет crash в `logcat -d | grep com.bydmate`.
- `logcat -d | grep VehicleApi` — не пусто, видны successful read'ы.

**Result:** [ ] PASS / [ ] FAIL

---

## #2 — Dashboard live data обновляется во время движения

**Pre:** машина в D, скорость > 5 км/ч. Drive 1–2 минуты по двору / прилегающей улице.

**Action:** наблюдать карточку Dashboard.

**Expected:**
- SOC меняется (хотя бы 0.1% за минуту движения).
- Скорость отображается с реальным значением (не -1, не 0 при движении).
- Состояние AC корректно (если включён — индикатор горит).
- Карточка «Энергия» показывает мощность в кВт (положительная в движении, отрицательная при рекуперации).

**Result:** [ ] PASS / [ ] FAIL

---

## #3 — Одна поездка записывается в Room с GPS-точками

**Pre:** #2 пройден.

**Action:** завершить поездку, поставить P, выключить IGN. Подождать 30s. Снова включить IGN, открыть BYDMate → вкладка «Поездки».

**Expected:**
- Новая строка в списке поездок. Длительность, км, kWh заполнены.
- Тап на строку → откроется TripDetailDialog. Карта показывает трек из GPS-точек.
- В Room таблица `trips` получила новую запись (можно проверить через `adb shell run-as com.bydmate.app sqlite3 databases/bydmate.db "SELECT id, distanceKm, kwhConsumed FROM trips ORDER BY id DESC LIMIT 1"`).

**Result:** [ ] PASS / [ ] FAIL

---

## #3b — Нативная запись поездки на машине БЕЗ energydata

**Pre:** машина со старой прошивкой или не-Leopard3 (Song / Yuan), где BYD energydata SQLite отсутствует. На Leopard 3 этот пункт пропускается (там работает HistoryImporter, TripRecorder пассивен).

**Action:** проехать одну поездку (3–5 минут движения), поставить P, выключить IGN. Подождать 30s. Открыть BYDMate → вкладка «Поездки».

**Expected:**
- Список поездок НЕ пустой. Появилась новая строка (расход из SOC delta грубее, чем BMS, это норма).
- В Room запись имеет `source = NATIVE_POLLING`:
```bash
adb shell run-as com.bydmate.app sqlite3 databases/bydmate.db \
  "SELECT id, distanceKm, source FROM trips ORDER BY id DESC LIMIT 1"
```

**Result:** [ ] PASS / [ ] FAIL / [ ] N/A (Leopard 3)

---

## #4 — Одна зарядка записывается в Room со start/end SOC

**Pre:** машина припаркована, IGN OFF. Запустить AC-зарядку (даже короткую, 2–5 минут).

**Action:** отключить зарядный пистолет. Подождать 30s. Открыть BYDMate → вкладка «Зарядки».

**Expected:**
- Новая строка в списке зарядок.
- `SoC start` < `SoC end`. kWh имеет осмысленное значение.
- В Room таблице `charges` запись присутствует.

**Result:** [ ] PASS / [ ] FAIL

---

## #5 — Одна автоматизация срабатывает (open driver window)

**Pre:** машина в P, IGN ON. В Automation tab настроена тестовая автоматизация: trigger = manual, action = `主驾打开100` (или через UI «Открыть окно водителя»).

**Action:** нажать manual trigger (или подождать срабатывания по правилу).

**Expected:**
- Окно водителя физически открывается (стекло опускается).
- `logcat -d | grep ActionDispatcher` — `Result: ... → OK` (а не FAIL).
- В Room `vehicle_write_log` появилась пара записей (`status=-2 attempt` + `status=0 success`).

```bash
adb shell run-as com.bydmate.app sqlite3 databases/bydmate.db \
  "SELECT ts, actionName, requested, status, error FROM vehicle_write_log ORDER BY ts DESC LIMIT 4"
```

**Result:** [ ] PASS / [ ] FAIL

---

## #5b — Нативные write-действия для света срабатывают

**Pre:** машина в P, IGN ON. В Automation tab настроены тестовые действия (или через UI): свет салона, ambient-подсветка, ДХО (DRL), обогрев зеркал.

**Action:** последовательно запустить четыре действия: interior light, ambient light, DRL, mirror heat.

**Expected:**
- Каждое действие даёт физический эффект (свет салона, ambient, ДХО, обогрев зеркал, он же обогрев заднего стекла).
- `logcat -d | grep ActionDispatcher` — `Result: ... → OK` для каждого.
- В Room `vehicle_write_log` появились пары записей (attempt + success).

> Подробная on-car валидация маппинга (dev/fid/значения) живёт в `oncar-light-validation-2026-05-28.md`.

**Result:** [ ] PASS / [ ] FAIL

---

## #6 — Яндекс Алиса голосовая команда работает end-to-end

**Pre:** в Settings → Smart Home поднята связь с VPS (endpoint + API key). В аккаунте Я.Алисы добавлены устройства BYDMate.

**Action:** сказать «Алиса, заблокируй машину» (или другая validated команда: окна / климат / люк).

**Expected:**
- Алиса отвечает «Готово» в течение 5–7 секунд.
- Физический эффект (замки щёлкают, окно движется, и т.д.).
- `logcat -d | grep AlicePolling` — `Executing: ...`, `Result: ... → OK`.
- VPS ack отправлен.

**Result:** [ ] PASS / [ ] FAIL

---

## #7 — ABRP получает телеметрию во время движения

**Pre:** в Settings → ABRP вставлен Live Data Token (Iternio Generic). Машина в D, движение.

**Action:** проехать 30–60s. Открыть `abetterrouteplanner.com` → гараж → авто → Live данные.

**Expected:**
- ABRP показывает последний пакет «получен <X> секунд назад» (X < 60).
- SOC и скорость в ABRP соответствуют реальным.

**Result:** [ ] PASS / [ ] FAIL

---

## #8 — Settings → Диагностика показывает live значения через VehicleApi

**Pre:** машина в P, IGN ON.

**Action:** в BYDMate → Settings → Диагностика (или эквивалентный экран live values).

**Expected:**
- Параметры (SOC, V, I, ёмкость, мощность, температуры) обновляются каждые 1–2 секунды.
- Значения совпадают с дашбордом BYD на машине.

**Result:** [ ] PASS / [ ] FAIL

---

## #9 — Тоггл «Системные данные» убран, native работает по умолчанию

**Pre:** свежая установка v2.9.0-pre.

**Action:** открыть BYDMate → Settings. Пройти первый запуск (Welcome Wizard).

**Expected:**
- В Settings НЕТ переключателя «Системные данные (экспериментально)». В Welcome Wizard нет шага про autoservice.
- SoH / lifetime, автоматическое логирование зарядок, ABRP engine power и live-детект зарядного пистолета работают сразу, без включения какого-либо тоггла.
- На машине без autoservice (старая прошивка / не-DiLink) приложение не падает (fail-soft через isAvailable / sentinel-гард).

**Result:** [ ] PASS / [ ] FAIL

---

## Sign-off

| Item | Status | Notes |
|---|---|---|
| #0 D+ uninstall + helper visible | | |
| #1 App launches | | |
| #2 Dashboard live | | |
| #3 Trip recording | | |
| #3b Native trip (no energydata) | | |
| #4 Charging recording | | |
| #5 Automation rule | | |
| #5b Lights native writes | | |
| #6 Alice voice | | |
| #7 ABRP telemetry | | |
| #8 Diagnostics | | |
| #9 Toggle removed, native default | | |

**Smoke run date:** _____________
**Operator:** Andy
**Outcome:** [ ] ALL PASS → unblock E.2 / [ ] FAIL → file gap, defer E.2

Если #5 / #6 FAIL для конкретной команды — это **не блокер**, это crowd-validation gap. Залогировать в `vehicle_write_log`, открыть GitHub issue с (actionName, dev, fid, requested, readback). Phase 2 ship'ает; mapping чинится hotfix'ом.

Если #1 / #2 / #3 / #4 / #7 / #8 FAIL — это **БЛОКЕР**. Native read/write path не работает. Откатить D+ uninstall (`adb install com.van.diplus.apk`) и debug.
