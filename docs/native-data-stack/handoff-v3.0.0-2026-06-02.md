# Handoff v3.0.0 (2026-06-02) — единый релиз: свой стек + Я.Навигатор на приборку + зарядка

## TL;DR

- **Решение Andy 2026-06-02:** объединить весь накопленный стек в **одну версию v3.0.0**. Перед релизом Andy сам ставит сборку и тщательно проверяет на машине.
- **Ветка релиза:** `feature/cluster-projection` (HEAD `23a585e`) — она надстроена прямо поверх `feature/native-data-stack`, то есть **содержит всё** (native stack + cluster). Сливать ветки между собой НЕ нужно.
- **Дивергенция от main:** `cluster-projection` ahead by 156, behind by 0. `native-data-stack` полностью содержится в ней (0 коммитов отсутствует).
- **Рабочее дерево (НЕ закоммичено)** на `cluster-projection` — две логические группы: charging-fix (эта сессия) + cluster cleanup (прошлая сессия). См. раздел «Незакоммиченное».
- **Версия в build.gradle.kts:** сейчас `2.9.1` / versionCode 305. Для релиза → bump до `3.0.0` / 306. **НЕ бампать и не собирать до явного «собирай».**
- **Последний ПУБЛИЧНЫЙ релиз:** v2.8.1 (gh release). v2.9.0 и v2.9.1 собирались локально, в gh release НЕ выпускались. Поэтому release notes v3.0.0 пишутся от базы v2.8.1.

## Что входит в v3.0.0 (от публичной базы v2.8.1)

### Блок 1. Свой стек данных вместо D+ (迪加) — Phase 1b / 1.5 / 2

Приложение больше не зависит от стороннего D+. Чтение и запись идут напрямую через системный `autoservice` Binder под shell-uid демоном `bydmate_helper`.

- **Read path (Phase 1b):** `NativeParsReader` → autoservice transact 5/7/9. SOC, мощность, температуры, ёмкость, V/I, пистолет, SoH, пробег.
- **Native trip recording (Phase 1.5):** `TripRecorder` пишет поездки нативно (source=NATIVE_POLLING) на машинах без energydata; на Leopard 3 пассивен. Shared adaptive loop.
- **Write channel (Phase 2):** `VehicleApi` → `CommandTranslator` → `WriteAllowlist` → `HelperClient` → `HelperDaemon` → autoservice transact 6 (setInt). `DiParsControlClient` + HTTP localhost:8988 удалены, grep gate 0 hits.
- **Comfort-запись (v2.9.x):** свет (салон/амбиент/ДХО), обогрев зеркал/заднего стекла, окна, климат (AC + динамическая температура 16-30), багажник. Свет валидирован на машине 2026-05-29.
- **Always-on:** тумблер «Системные данные (экспериментально)» удалён, путь всегда активен; fail-soft через `isAvailable()`/sentinel на чужих прошивках.
- **Итог для пользователя:** после установки и проверки можно удалить `com.van.diplus` с DiLink.

Детали: `handoff-2026-05-29.md`, `handoff-2026-05-28.md`, `oncar-light-validation-2026-05-28.md`, memory `[[reference_native_stack_dual_channel]]`.

### Блок 2. Яндекс.Навигатор на приборку (cluster projection)

Новая фича. Когда штатный режим приборки = «Полный», на неё выводится Яндекс.Навигатор. Переключатель: **Настройки → Приборка → «Выводить Яндекс.Навигатор на приборку»**.

- Механизм: overlay + VirtualDisplay + launchAndForce под shell-uid демоном; авто-mirror по чтению штатного ИПЦ-тумблера (fid 1086337074, dev 1007, read tx5: OFF=1/MINI=2/FULL=4).
- **Финальный подход — auto-mirror**, без кнопки руля и без AccessibilityService. Старый diagnostic-экран / SteeringWheelKeyService / a11y-config выпилены в cleanup (см. «Незакоммиченное»).
- Демон грантит себе `PROJECT_MEDIA` + `SYSTEM_ALERT_WINDOW` appops (иначе `getDisplay(clusterId)`=null у стороннего процесса).
- Codex pre-build audit пройден 2026-06-02 (SHIP).

Детали: memory `[[reference_native_cluster_nav_mechanism]]`, `[[reference_cluster_projection_phase0]]`, `[[reference_openbyd_cluster_projection]]`.

### Блок 3. Зарядка во сне — фикс (эта сессия, 2026-06-02)

Реконструкция зарядной сессии по двум точкам, когда машина спала всю зарядку (приложение полностью выключено).

- `recordParkedAnchor` катит старт-якорь SOC вниз во время движения (последний снимок перед глушением). При росте SOC якорь не перезаписывается, поэтому пробуждение race-free без флага.
- В `runCatchUp`: порог `MIN_SOC_DELTA_FOR_CHARGE=3` + odometer-guard (если в gap ехали, сессия пропускается, чтобы не размазывать поездку в «зарядку»). AC/DC по реальному времени gap сохранён.
- Стартовый catch-up получил retry (4×3 с) на cold-start SENTINEL/UNAVAILABLE.
- Убран мёртвый D+ fallback в Step 3 (parsReader читал тот же autoservice).
- Тесты: `AutoserviceChargingDetectorTest` — 37 кейсов зелёные, включая repro сценария пользователя (10→80 во сне → одна COMPLETED-строка, AC по времени).

Файлы: `AutoserviceChargingDetector.kt`, `service/TrackingService.kt`, `AutoserviceChargingDetectorTest.kt`.

### Блок 4. Виджет и автоматизация (issue-фиксы v2.9.1)

- Виджет (#31): км на 1% заряда в правый верхний слот, температуры салона/батареи/12В в нижний ряд.
- Автоматизация (#21): триггер по точному времени / промежутку + дни недели (kind `time_range`).
- Кнопка «Выполнить сейчас» в редакторе автоматизации (мимо edge/cooldown).
- Закрытие окна обновления отменяет загрузку (#23).
- README quick-link anchors + releases link (#20).

## Незакоммиченное рабочее дерево (закоммитить перед сборкой)

На `feature/cluster-projection`, две логические группы — рекомендуется два отдельных коммита:

**Группа A — charging fix (эта сессия):**
- `M data/charging/AutoserviceChargingDetector.kt`
- `M service/TrackingService.kt` (recordParkedAnchor call + catch-up retry)
- `M test/.../AutoserviceChargingDetectorTest.kt`

**Группа B — cluster cleanup (прошлая сессия, auto-mirror рефактор):**
- `D` ClusterEntryPoint.kt, SteeringWheelKeyService.kt, SteeringWheelKeyState.kt, ui/cluster/ClusterDiagnosticScreen.kt, ui/cluster/ClusterDiagnosticViewModel.kt, res/xml/accessibility_service_config.xml, test HelperClientDisplaysTest.kt
- `M` AndroidManifest.xml, cluster/ClusterProjectionManager.kt, cluster/ClusterProjectionState.kt, vehicle/HelperClient.kt, helper/HelperBinderProtocol.kt, helper/HelperDaemon.kt, ui/navigation/AppNavigation.kt, ui/settings/SettingsScreen.kt (+ секция «Приборка»), test ClusterProjectionStateTest.kt, test HelperClientProjectionTest.kt

Примечание: `service/TrackingService.kt` несёт правки ОБЕИХ групп (cluster `startClusterMirror` + charging `recordParkedAnchor`). При раздельных коммитах развести `git add -p`.

Весь набор компилируется и проходит `./gradlew :app:testDebugUnitTest` (BUILD SUCCESSFUL, 2026-06-02).

## Release checklist (после личной проверки Andy на машине)

1. Закоммитить рабочее дерево (группы A и B).
2. Bump `versionName 2.9.1 → 3.0.0`, `versionCode 305 → 306` в `app/build.gradle.kts`.
3. `./gradlew assembleRelease` + подпись keystore (см. CLAUDE.md / `[[reference_keystore_path]]`).
4. Проверить, что release-сборка НЕ debuggable (`run-as: package not debuggable`).
5. Smoke на машине (раздел ниже).
6. `gh release create v3.0.0 ...` (release notes ниже) + merge `feature/cluster-projection` → main.
7. `feature/settings-master-detail` (ahead 0, behind 30, уже в main) можно удалить.

## On-car smoke — что проверить

- **Зарядка во сне:** приехать на низком SOC, заглушить, зарядить пока спит, выдернуть, завести → ровно ОДНА зарядка с верными SOC и типом (AC/DC).
- **Я.Навигатор на приборку:** включить тумблер в Настройки → Приборка; штатный режим приборки «Полный» → Навигатор появляется; OFF/Simple → убирается. Проверить, не просит ли система разрешение overlay/projection (по идее грантится демоном без UI).
- **Comfort:** свет (салон/амбиент/ДХО), обогрев зеркал, окна, климат, багажник — реальное действие.
- **D+ удаляем:** после PASS убедиться, что приложение работает с удалённым `com.van.diplus`.
- **Апгрейд:** установка поверх старой версии бесшовна (миграции БД аддитивны 12/13 → 15).

## Release notes v3.0.0 — DRAFT (русский, plain text, утвердить перед gh release)

```
v3.0.0 — свой стек данных и Яндекс.Навигатор на приборке

Новое
- Приложение работает напрямую с системой, без приложения D+ (迪加). Все данные читаются и управление идёт без посредника. D+ можно удалить с машины.
- Яндекс.Навигатор на приборке. Когда штатный режим приборки «Полный», на неё выводится Навигатор. Включается в Настройки, Приборка.
- Управление светом и комфортом напрямую: подсветка салона, амбиент, дневные ходовые огни, обогрев зеркал и заднего стекла, окна, климат, багажник.
- Автоматизация по расписанию: срабатывание по времени и промежутку с выбором дней недели.
- Кнопка «Выполнить сейчас» в редакторе автоматизаций.
- Виджет: километры на 1% заряда и температуры салона и батареи.

Исправления
- Зарядка, прошедшая пока машина спала, теперь записывается корректно. Раньше такая сессия могла не сохраниться.
- Закрытие окна обновления отменяет загрузку.

Установка
Скачать BYDMate-v3.0.0.apk, открыть через файловый менеджер на DiLink, разрешить установку.
```

> Перед публикацией: финализировать список (terse, только user-facing), проверить отсутствие длинных тире, ответить на issue #29 (рециркуляция уже реализована) и при необходимости #22.
