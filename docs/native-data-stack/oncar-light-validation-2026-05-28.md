# On-Car Light Validation — недостающие/candidate фиды (2026-05-28)

> **Зачем:** закрыть последний блокер паритета с D+ — салонный/ambient свет и ДХО.
> Все фиды извлечены из штатных классов BYD (`BYDAutoSettingDevice`, `BYDAutoLightDevice`,
> `BYDAutoFeatureIds`) в `.research/leopard3-pulled/byddiagnosetool-decompiled/`.
> Значения сверены с самими сеттерами BYD, не угаданы.
> **Когда:** совместная сессия в машине (Leopard 3, IGN ON, режим P, заряд ≥30%).
> **Канал теста:** существующий `probe.dex` (`WriteProbe.java`) через `app_process` под
> shell uid — тот же Binder transact (token + dev + fid + value, tx=6), что и shipping
> helper daemon в v2.9.0. PASS на probe.dex ⇒ candidate-проводка приложения сработает.

---

## Почему probe.dex, а не daemon

`HelperDaemon` и `WriteProbe` делают **идентичный** transact 6 (`setInt`) к autoservice.
Разрешение autoservice проверяется по uid вызывающего (shell) + per-fid whitelist, не по
процессу. Поэтому probe.dex даёт тот же результат, что и демон, но **без зависимости от
запущенного приложения** — чище для изоляции теста одного фида.

**Важно про round1 (2026-05-28 14:48):** там все фиды дали `read_value=-10011`. Это НЕ
«канал закрыт» — round1 читал (tx=5) **write-namespace** фиды, которые нечитаемы by design.
Валидация записи делается **только tx=6 + визуальное наблюдение**, без read-first.

---

## Семантика статуса (raw transact)

`status = reply.readInt()` (первый int ответа):

| status | значение |
|--------|----------|
| `1`    | реальное действие выполнено (состояние изменилось) |
| `0`    | принято, но no-op (уже в этом состоянии) |
| `< 0`  | отказ. `-10011`/`0xffffd8e5` = permission sentinel; `-2147482645` = неверное значение (guard) |

**PASS критерий каждого фида:** `status >= 0` **И** свет физически изменился.
**FAIL:** `status < 0` или `EXC SecurityException`, либо нет физического эффекта.

Если ВСЕ dev=1023 пишут `status < 0` / SecurityException → shell uid не пускают в Setting
namespace (signature permission `BYDAUTO_SETTING_SET`), и нативный канал света недостижим
под нашим uid. Тогда честно фиксируем «нет нативного канала» и свет остаётся вне паритета.

---

## Матрица тестов

Значения ниже — **raw** (то, что уходит в transact), уже с учётом конвертаций BYD-сеттеров.

| # | Функция | dev | fid | ON | OFF | Источник / семантика |
|---|---------|-----|-----|----|-----|----------------------|
| 1 | Свет салона (плафон) | 1023 | 1330643002 | **2** | **1** | `turnOffInsideLight()`→1; валидные {1,2} ⇒ 2=ON. **Wired в app (candidate).** |
| 2 | Ambient — яркость | 1023 | 1069547536 | **5** | **0** | `setIALBrightness(i)`→`set(fid, i+1)`. raw 0=выкл, raw 5=уровень 4. **Wired (candidate).** |
| 3 | Ambient — зона | 1023 | 1069547540 | 1→2→3 | — | `setIALArea(i)`. Зоны: перед/зад/все (точные индексы наблюдаем). Не wired. |
| 4 | Ambient — цвет | 1023 | 1069547528 | 5→26→14 | — | `setIALColor(i)`. colorID из IALApiImpl {5,29,30,25,26,2,3,21,14,27}. Не wired. |
| 5 | Длит. салонного света | 1023 | 1043333154 | 1→4 | — | `setILDuration(i)`, валидные {1,2,3,4}. Не wired. |
| 6 | Welcome light | 1023 | 1330643013 | **1** | **2** | `setSmartWelcomeLightState(i)`, guard 0..2. on=1/off=2 (по аналогии с DRL, уточняем). Не wired. |
| 7 | ДХО (DRL) | 1004 | 1125122118 | **1** | **2** | `BYDAutoLightDevice`: `DAY_RUNNING_LIGHT_ON=1`, `OFF=2`, `INVALID=0`. **dev=1004 BANNED** — снап перед разбаном. Не wired. |

**Приоритет:** #1, #2, #7 — закрывают паритет (свет салона + ambient + ДХО). #3–#6 — «приятно
иметь», тестируем если #1/#2 прошли (значит Setting namespace открыт под shell uid).

---

## Процедура (probe.dex)

### Подготовка (один раз)
```bash
export ADB=$HOME/Library/Android/sdk/platform-tools/adb
export SERIAL=192.168.2.68:5555
$ADB -s $SERIAL push scripts/native-stack/write-probe/probe.dex /data/local/tmp/probe.dex
```
(если `probe.dex` отсутствует — `scripts/native-stack/write-probe/build.sh`).

### Одиночный write (шаблон)
```bash
$ADB -s $SERIAL shell \
  "CLASSPATH=/data/local/tmp/probe.dex app_process /system/bin \
   --nice-name=bydmate_probe com.bydmate.probe.WriteProbe 6 <dev> <fid> <value>"
```
Вывод: `RESULT tx=6 ... status=<N> value=<N>`.

### Готовый интерактивный прогон
```bash
scripts/native-stack/write-probe/phase2c/round2-light-probe.sh
```
Скрипт по каждому пункту: пишет ON → ждёт твоего наблюдения (Enter) → пишет OFF →
печатает обе `RESULT`-строки. dev=1004 (DRL) идёт последним с отдельным подтверждением.

---

## Per-fid: что наблюдать

**#1 Свет салона** — `WriteProbe 6 1023 1330643002 2` → плафон/салонные лампы загораются.
`... 1` → гаснут. PASS: визуально + `status>=0`.

**#2 Ambient яркость** — `WriteProbe 6 1023 1069547536 5` → подсветка салона ярче/включается.
`... 0` → гаснет. (Если 5 без эффекта — поднять до 6/попробовать зону #3 первой.)

**#3 Ambient зона** — `WriteProbe 6 1023 1069547540 1` затем `2`, `3`. Наблюдаем, какая зона
(перед/зад/всё) при каком индексе. Записать соответствие.

**#4 Ambient цвет** — `WriteProbe 6 1023 1069547528 5` (冷蓝), `26` (红), `14` (黄). Смена цвета.

**#5 Длительность** — `WriteProbe 6 1023 1043333154 1` … `4`. Эффект отложенный (таймаут гашения
courtesy-света) — проверяем только `status>=0`, физику опционально.

**#6 Welcome** — `WriteProbe 6 1023 1330643013 1` (вкл приветственную подсветку при подходе),
`2` (выкл). Эффект виден при разблокировке/подходе — может не быть мгновенным в P.
Если on=1 без эффекта — попробовать значения 0 и 2.

**#7 ДХО (BANNED dev=1004)** — отдельный снап перед разбаном namespace:
`WriteProbe 6 1004 1125122118 1` → ДХО включаются; `... 2` → выключаются.
PASS ⇒ заносим `(1004, 1125122118)` в `BANNED_DEV_FID_EXCEPTIONS` + carve-out, как со светом.
FAIL/SecurityException ⇒ dev=1004 остаётся полностью забанен, ДХО вне паритета.

---

## Зеркала (后视镜加热) — logcat-трейс, не write-проба

Нативного сеттера обогрева зеркал нет ни в одном `BYDAuto*Device` (D+ управлял через
voice-framework wrapper). Фид неизвестен — ищем трейсом, пока штатный UI/D+ ещё доступен:

```bash
$ADB -s $SERIAL shell logcat -c
$ADB -s $SERIAL shell logcat | grep -iE "BYDAutoSettingDevice|BYDAutoLightDevice|transact|mirror|后视镜|HeatMirror|MirrorHeat"
```
Затем включить обогрев зеркал штатной кнопкой (или из D+, если ещё стоит). Поймать в логах
`set: ... fid=<N> value=<M>` или имя метода. Записать dev/fid/value. Если D+ удалён и штатной
кнопки в зоне досягаемости нет — отложить (зеркала единственный незакрытый пункт).

---

## После теста

1. PASS-фиды #1/#2: candidate `validated=false` → `LIVE_VALIDATED` (`validated=true`) в
   `WriteAllowlist.kt`, source `live-leopard3-2026-05-28`.
2. PASS DRL: `BANNED_DEV_FID_EXCEPTIONS += (1004, 1125122118)` + WriteEntry + CommandTranslator
   `打开日行灯/关闭日行灯` (snap dev=1004 пройден — safety review разбана namespace).
3. Зеркала: найден fid → WriteEntry + CommandTranslator `后视镜加热`.
4. Обновить `reference_validated_write_fids_leopard3.md` фактическими status/value.
5. FAIL-фиды: оставить candidate (fail-soft, не крашат) + пометка в доке «native канал закрыт».

**Без явного «ок» пользователя ничего на машину не пишем, версию не бампаем, релиз не выпускаем.**
