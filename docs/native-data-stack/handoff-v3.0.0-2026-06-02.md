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

Новая фича. При включённом тумблере короткое нажатие правой звезды руля переносит Яндекс.Навигатор на приборку и обратно на экран (полноэкранный режим приборки пользователь включает сам в штатной навигации). Переключатель: **Настройки → Приборка → «Навигатор на приборку правой звездой»**.

- Механизм: overlay + VirtualDisplay + launchAndForce под shell-uid демоном.
- **Финальный подход — управление правой звездой (откат auto-mirror, решение Andy 2026-06-02).** Тумблер гейтит `SteeringWheelKeyService` (AccessibilityService, FLAG_REQUEST_FILTER_KEY_EVENTS). Короткое нажатие правой звезды (keycode 351) → `ClusterProjectionManager.toggle()` OFF↔FULLSCREEN (2 состояния); нажатие consume'ится. Удержание (352), левая звезда, карусель и выключенный тумблер → pass-through, штатное действие/меню цело. Приложение НЕ читает штатный ИПЦ-тумблер (fid 1086337074) — FULL контролирует пользователь; если FULL не включён, toggle — визуальный no-op (state остаётся OFF, следующее нажатие повторит).
- a11y **включается самим приложением, без ADB** (commit `b4f1959`): тумблер ON → `enableStarControl` → демон `enableAccessibilityService` (`TX_ENABLE_ACCESSIBILITY`=16) делает force re-bind через `settings put secure` под shell-uid (убрать → пауза 200мс → добавить → `accessibility_enabled=1`), как OpenBYD `AccessibilitySetupHelper`. Простой append не биндит сервис, который уже в списке но не запущен (после переустановки APK), поэтому нужен re-bind. App-scoped, обратимо; машины не касается.
- Демон грантит себе `PROJECT_MEDIA` + `SYSTEM_ALERT_WINDOW` appops (иначе `getDisplay(clusterId)`=null у стороннего процесса).
- План: `docs/superpowers/plans/2026-06-02-cluster-star-control.md`; коммиты `b30eddf..46a6f90`; 615 тестов зелёные, `assembleDebug` OK.
- Codex pre-build audit пройден 2026-06-02 (SHIP).
- **Проверено на машине 2026-06-02 (PASS):** Andy подтвердил работу; в системе наш a11y-сервис включён сам (`accessibility_enabled=1`, есть в `enabled_accessibility_services`, запущен в dumpsys), демон `bydmate_helper` жив под uid shell. ADB-шаг не понадобился. (Дубль форм записи нашего компонента, замеченный тогда как «косметика», оказался причиной поломки после ребута — починено в Блоке 5.)

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

### Блок 5. Размер окна на приборке + устойчивость после ребута (эта сессия, 2026-06-02)

Доводка cluster projection после первой проверки на машине.

- **Ползунки размера окна (фича).** Настройки → новый раздел **«Дисплей»** → «Размер окна на приборке»: два независимых ползунка Ширина/Высота (50-100%, шаг 2%). `geometryFor` принимает `widthPct`/`heightPct`, центрирует уменьшенное окно, штатная приборка просвечивает вокруг. Размер хранится в prefs (`KEY_WIDTH_PCT`/`KEY_HEIGHT_PCT`).
- **Плавный ресайз (make-before-break).** `reproject` → `swapToNewSize`: поднимает новый overlay+VirtualDisplay нужного размера, переносит на него Navi через `launchAndForce`, и ТОЛЬКО потом отпускает старый. Navi не прыгает на главный экран. Fallback на полный rebuild только при неоднозначном состоянии.
- **Самовосстановление a11y после ребута (фикс).** Корень бага «после ребута звезда умерла и тумблер не помогал»: на раннем старте загрузки бинд `SteeringWheelKeyService` падает → AMS держит сервис в `Crashed` и сам не пере-биндит. Перетык тумблера не помогал из-за **дубля форм** в `enabled_accessibility_services` (короткая `/.cluster.X` + полная) — демон снимал только полную, набор компонентов не менялся, перехода нет → re-bind не триггерился. Починка: `HelperDaemon.enableAccessibilityService` теперь сравнивает по **каноническому** компоненту (`canonicalComponent`, разворот ведущей точки) и снимает ОБЕ формы. Плюс `TrackingService.onCreate` на старте, если тумблер включён и сервис не привязан (`AccessibilityManager.getEnabledAccessibilityServiceList`), один раз форсит re-bind — каждый ребут оживает сам, без действий пользователя. Подтверждено вживую на машине 2026-06-02 (полное снятие обеих форм → `SteeringWheelKeySvc: connected`).
- **Утечка VirtualDisplay (фикс).** Демон переживает рестарт приложения и держит старый VirtualDisplay, а приложение теряет handle (`remoteDisplayId=-1`) → на следующей проекции плодит новый, не освободив осиротевший (на тестовой машине накопилось 13 дисплеев `BYDMate_Cluster_VD`, живых 5). Починка app-side: id созданного дисплея пишется в prefs (`KEY_LAST_VD_ID`), на холодном старте проекции `releaseOrphanedDisplay` освобождает осиротевший id прошлой сессии. Демон убивает только дисплеи из своей карты, чужой не заденет. Make-before-break не задет (демон старый дисплей по имени НЕ убивает — иначе вернулся бы прыжок). Текущие хвосты на машине очистятся на следующем ребуте.
- **Выбор приложения для трансляции (фича, 2026-06-03, коммит `dcb8e18`).** Раньше цель проекции была захардкожена (`NAVI_PACKAGE`). Теперь под тумблером включения — строка-пикер (переиспользует `AppLaunchPickerDialog` из автоматизации): можно выбрать любое установленное приложение, не только Я.Навигатор. Пакет хранится в prefs (`KEY_TARGET_PACKAGE` + `KEY_TARGET_LABEL`), `ClusterProjectionManager.targetPackage(context)` читает его во всех 4 точках. Смена применяется на следующем нажатии звезды, не live. Строка `settings_display_app_title` в ru+en.
- **Локализация.** Строки нового раздела «Дисплей» вынесены в `values/strings.xml` + `values-en/strings.xml` (`settings_display_*`), UI на `stringResource`.
- Тесты: `CanonicalComponentTest` (5 кейсов на дедуп форм), `ClusterProjectionStateTest` (+6 кейсов на размеры) — зелёные. `assembleRelease` BUILD SUCCESSFUL.
- Файлы: `HelperDaemon.kt`, `TrackingService.kt`, `ClusterProjectionManager.kt`, `ClusterProjectionState.kt`, `ui/settings/SettingsScreen.kt`, `values/strings.xml`, `values-en/strings.xml`, `ClusterProjectionStateTest.kt`, `CanonicalComponentTest.kt`.
- Тестовый APK (release-подпись, 2.9.1/305): `BYDMate-cluster-fix-test.apk` в корне репо. На машину НЕ закидывался (Andy выключил машину).

Детали: memory `[[reference_native_cluster_nav_mechanism]]`.

## Состояние веток (всё закоммичено)

На `feature/cluster-projection`, рабочее дерево чистое. Релевантные коммиты этой сессии:

- `443d145` charging fix (sleep-charge reconstruction).
- `760faa2` cluster cleanup: убран старый diagnostic/a11y/steering-wheel путь (тогда — в пользу auto-mirror; charging-правки TrackingService приехали этим же коммитом).
- `b30eddf..46a6f90` — **откат auto-mirror → управление правой звездой** (решение Andy 2026-06-02): возвращены `SteeringWheelKeyService` + `ClusterEntryPoint` + a11y-config, добавлен чистый gate `SteeringWheelKeyDecision`, `ClusterProjectionManager.toggle()`/`nextMode`, снят guard auto-launch, выпилен мёртвый lever-mapper, обновлён тумблер в Настройках.
- `b4f1959` — **самовключение a11y по тумблеру (без ADB)**: возвращён демонский путь `TX_ENABLE_ACCESSIBILITY` + `enableAccessibilityService` (force re-bind как OpenBYD), `enableStarControl` дёргается из тумблера. Любой пользователь ставит APK → включает тумблер → работает.
- **(Блок 5, последний коммит)** ползунки размера окна + плавный ресайз + самовосстановление a11y после ребута (канонический дедуп форм) + фикс утечки VirtualDisplay + локализация раздела «Дисплей».

Весь набор компилируется и проходит `./gradlew :app:testDebugUnitTest` + `:app:assembleRelease` (BUILD SUCCESSFUL, 2026-06-02).

## Release checklist (после личной проверки Andy на машине)

1. Рабочее дерево уже закоммичено (см. «Состояние веток»). a11y включается самим приложением по тумблеру — ADB-шаг больше не нужен.
2. Bump `versionName 2.9.1 → 3.0.0`, `versionCode 305 → 306` в `app/build.gradle.kts`.
3. `./gradlew assembleRelease` + подпись keystore (см. CLAUDE.md / `[[reference_keystore_path]]`).
4. Проверить, что release-сборка НЕ debuggable (`run-as: package not debuggable`).
5. Smoke на машине (раздел ниже).
6. `gh release create v3.0.0 ...` (release notes ниже) + merge `feature/cluster-projection` → main.
7. `feature/settings-master-detail` (ahead 0, behind 30, уже в main) можно удалить.

## On-car smoke — что проверить

- **Зарядка во сне:** приехать на низком SOC, заглушить, зарядить пока спит, выдернуть, завести → ровно ОДНА зарядка с верными SOC и типом (AC/DC).
- **Я.Навигатор на приборку (правой звездой):** установить APK, открыть Настройки → Приборка, включить тумблер «Навигатор на приборку правой звездой» (приложение само включит a11y через демон, ADB не нужен). В штатной навигации включить полноэкранный режим приборки. Короткое нажатие правой звезды → Навигатор на приборке; повторное → обратно на экран. Удержание правой звезды → штатное меню (цело). Тумблер OFF → звезда работает штатно. Проверить, не просит ли система разрешение overlay/projection (по идее грантится демоном без UI). Если после включения тумблера звезда не перехватывается сразу — выключить/включить тумблер ещё раз (re-bind) или проверить logcat `SteeringWheelKeySvc`/`ClusterProjection`.
- **Comfort:** свет (салон/амбиент/ДХО), обогрев зеркал, окна, климат, багажник — реальное действие.
- **D+ удаляем:** после PASS убедиться, что приложение работает с удалённым `com.van.diplus`.
- **Апгрейд:** установка поверх старой версии бесшовна (миграции БД аддитивны 12/13 → 15).

## Release notes v3.0.0 — DRAFT (русский, plain text, утвердить перед gh release)

```
v3.0.0 — свой стек данных и Яндекс.Навигатор на приборке

Новое
- Приложение работает напрямую с системой, без приложения D+ (迪加). Все данные читаются и управление идёт без посредника. D+ можно удалить с машины.
- Яндекс.Навигатор на приборке по правой звезде руля. Короткое нажатие переносит Навигатор на приборку и обратно на экран. Включается в Настройки, Приборка. Нужен полноэкранный режим приборки в штатной навигации.
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
