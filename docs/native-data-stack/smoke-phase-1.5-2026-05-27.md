# Phase 1.5 Smoke — Leopard 3 only

Build: vX.Y.Z (debug или release по решению Andy)
Тестер: Andy (реальный DiLink, Leopard 3)
Branch: `feature/native-data-stack` (HEAD `a85af4c` после Task 21)

## Pre-flight
- [ ] APK скопирован в `/sdcard/Download` и установлен вручную через файловый менеджер.
- [ ] Settings → Diagnostics показывает живые данные (SOC, мощность, температуры).
- [ ] Wireless ADB включён (для logcat при необходимости).

## Drive cycle (1 поездка)
- [ ] Завести машину, проехать ~5 мин, припарковаться.
- [ ] В BYDMate → Поездки появилась новая строка.
- [ ] `row.source = "energydata"` (на Leopard 3 этот путь не меняется).
- [ ] **Нет дублирующей строки с `source = "native_polling"`** (TripRecorder должен быть passive — `EnergyDataReader.isAvailable() == true`).

## Service restart посреди поездки
- [ ] Начать поездку, затем force-stop сервиса через Settings → Apps.
- [ ] Перезапустить сервис (тап по BootReceiver / перезагрузка телефона).
- [ ] Продолжить поездку через 1-2 мин.
- [ ] В Поездки одна строка, без дубликатов.

## Cold-boot recovery
- [ ] Перезагрузить DiLink посреди поездки (или выключить зажигание при активном сервисе).
- [ ] Включить обратно, проехать ещё немного.
- [ ] Stale trip закрыт на cold-start; новая поездка начинается чисто.
- [ ] **Проверка миграции v13→v14:** после установки на 2.7 → 2.8 база мигрирует без потерь, существующие поездки на месте.

## Charging
- [ ] Подключить зарядник.
- [ ] FSM входит в состояние CHARGE (cadence ~5 s — проверить через `adb logcat | grep SharedAdaptiveLoop`).
- [ ] В Поездки строка НЕ добавляется во время зарядки.
- [ ] При отключении зарядника создаётся `ChargeEntity` (gun-disconnect edge → `runCatchUp`).

## Dashboard refresh cadence
- [ ] Driving: живые значения обновляются каждые ~1 s.
- [ ] Parked с зажиганием: ~5 s.
- [ ] Зажигание off: ~30 s.
- [ ] Поле "Подключение" (`vehicleDataConnected`) корректно отражает состояние shared loop (true пока есть тики, false после ~3 пустых).

## Alice (private build only)
- [ ] VPS POST каждые ~25 s содержит свежий state (проверить pm2 logs на vlad-brain).
- [ ] AlicePollingManager продолжает poll'ить VPS на команды каждые 2.5 s (не сломано Task 18).
- [ ] Команды из Yandex Alice исполняются (например, "открой окно").

## ABRP / Iternio
- [ ] Driving: телеметрия постится каждые ~1 s (driven by sharedAdaptiveLoop drive cadence).
- [ ] Parked: каждые ~30 s.

## Logcat sanity
- [ ] `adb logcat | grep -E "SharedAdaptiveLoop|TripRecorder|AutoserviceDetector"` показывает:
  - FSM переходы (DRIVE/PARKED/IDLE/CHARGE) при ожидаемых событиях.
  - `TripRecorder.reconcileColdStart` выполняется один раз на старте сервиса.
  - `AutoserviceDetector.runCatchUp` срабатывает только на gun-edge и cold-start.

## Что флагать
- Любой дублирующий trip row, отсутствие kwh, неправильный source → процитировать строку дословно.
- Любой stale "open trip" что никогда не закрывается → отметить timestamps.
- Любой WARN из `TripRecorder`/`SharedAdaptiveLoop`/`AutoserviceDetector` → процитировать строку из logcat.
- Миграция v13→v14: если после обновления с 2.7 теряются поездки или приложение крашится при первом старте.

## Verify before release
Если smoke ОК — Andy даёт `выпускай`, тогда (но не раньше):
- `versionCode` и `versionName` бампятся.
- Release APK собирается из `~/.android/keystores/bydmate-release.jks`.
- `gh release create vX.Y.Z` с RELEASE_NOTES (русский, terse, формат как v2.5.12).
