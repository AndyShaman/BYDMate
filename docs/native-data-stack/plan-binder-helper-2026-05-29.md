# План: helper daemon → binder-сервис (apk-classpath + setsid)

Дата: 2026-05-29. Ветка `feature/native-data-stack`. Версия в работе: v2.9.1 (versionCode 305).

## Зачем

Нативная запись (Алиса, автоматизации, любое действие) не работает на машине: helper
daemon не поднимался. Два бага в доставке (`AdbOnDeviceClient.bootstrapHelper`):

1. **Push dex не лезет в ADB-протокол.** helper.dex 2.27 МБ → base64 ≈ 3 МБ уходит одним
   OPEN-пакетом, потолок `MAX_PAYLOAD = 262144` (256 КБ). Push молча проваливается.
2. **`nohup … &` не выживает.** `AdbProtocolClient.exec` сразу шлёт CLSE, adbd сносит
   процесс-группу, `app_process` убивается до отделения. `nohup` спасает только от SIGHUP.

Сам daemon-код исправен (при ручном `adb shell` биндит порт и слушает). Чиним только
доставку и транспорт. Андй выбрал: сразу binder-сервис как у конкурента (а не латать TCP).

**Решение по fallback (2026-05-29, Andy подтвердил):** runtime-fallback на сокет НЕ строим —
идём binder-only. Старый TCP-путь как fallback не годится: сломана была доставка (push dex
>256 КБ), а не сокет; откат к нему через git вернёт нерабочее состояние, не рабочий сокет.
Поэтому TCP/helper-dex вычищаем целиком в этой итерации, git хранит историю на случай отката.
Если binder упрётся на машине (риск низкий — конкурент гоняет ровно это на том же DiLink) —
чиним вперёд (сокет в новый демон), не назад.

## Что меняем (суть)

- **Доставка**: `CLASSPATH=<base.apk>` (наш подписанный APK через `context.packageCodePath`),
  `HelperDaemon` компилируется в основной dex приложения. Push dex убираем целиком
  (`:helper-dex` модуль, `buildHelperDex` task, assets `helper.dex`+`.sha256` — на удаление).
  Целостность теперь от подписи APK (OS проверяет при install), отдельный sha не нужен.
- **Запуск**: `setsid app_process … </dev/null >/data/local/tmp/bydmate_helper.log 2>&1 &` — новая сессия, переживает
  закрытие ADB-shell. Чинит баг #2.
- **IPC**: daemon = binder-сервис (`ServiceManager.addService` + onTransact + `Looper.loop`),
  app ходит через `ServiceManager.getService` + `IBinder.transact`. TCP 8765 убираем целиком
  (`ServerSocket`, JSON-протокол `HelperProtocol`, порт-константы). Hidden API на стороне app
  разблокируем через `org.lsposed.hiddenapibypass` (только `android.os.ServiceManager`).
- **Безопасность канала**: в onTransact проверяем `Binder.getCallingUid() == <app uid>`
  (uid передаём daemon'у аргументом при спавне). Чужой процесс писать не сможет.
- **Single-owner**: файловый lock `/data/local/tmp/bydmate_helper.lock` (`FileChannel.tryLock`),
  второй экземпляр выходит. Это lock, НЕ сетевой порт — autoservice не экспонирует.

## НЕ трогаем (контур безопасности записи — остаётся как есть)

`WriteAllowlist` (110 entries, BANNED_DEVS, per-fid carve-outs для света), `VehicleWriteError`
fail-soft (6 состояний, `doWrite` never throws), readback-gate, аудит `vehicle_write_log`,
read-path `service call autoservice 5/7/9` через ADB. Меняется ТОЛЬКО транспорт writes,
не политика. Никаких новых fid'ов, никаких записей в BMS/MCU/CAN/прошивку.

## Clean-room (требование Andy)

Код конкурента (BYD EV Pro) НЕ копируем и не упоминаем. Всё пишем с нуля по нашему протоколу
и нашим именам. Запрещены идентификаторы/имена/комментарии: `kramskyi`, `byd_ev_pro`, `evpro`,
`vehicled`, `IVehicleState`, `AutoserviceBridge`, `ProcessExemptionController`. Имя сервиса =
`bydmate_helper`, interface token = `com.bydmate.app.helper.IHelper`. Финальный grep-gate = 0 hits.

## Контракт binder (фиксированный, общий для daemon и client)

Файл `app/src/main/kotlin/com/bydmate/app/helper/HelperBinderProtocol.kt`:

```
const val SERVICE_NAME   = "bydmate_helper"
const val PROCESS_NAME    = "bydmate_helper"          // --nice-name + ps lookup
const val DESCRIPTOR      = "com.bydmate.app.helper.IHelper"
const val TX_PING  = IBinder.FIRST_CALL_TRANSACTION + 0   // = 1
const val TX_READ  = IBinder.FIRST_CALL_TRANSACTION + 1   // = 2
const val TX_WRITE = IBinder.FIRST_CALL_TRANSACTION + 2   // = 3
```

Wire-формат (всё через `Parcel`):

| tx | request (после writeInterfaceToken(DESCRIPTOR)) | reply |
|----|--------------------------------------------------|-------|
| TX_PING  | (ничего)                       | writeInt(status=0) |
| TX_READ  | writeInt(tx), writeInt(dev), writeInt(fid) | writeInt(status), writeInt(value) |
| TX_WRITE | writeInt(dev), writeInt(fid), writeInt(value) | writeInt(status), writeInt(value) |

`status`/`value` — это raw результат autoservice transact (как сейчас в HelperDaemon.dispatch:
`status = reply.readInt()` если avail>=4 иначе -999; `value = reply.readInt()` если avail>=8).
Семантика приёма НЕ меняется: write принят при `status >= 0`, read принят при `status == 0`
(`HelperClientImpl.writeAccepted/readAccepted` остаются, `HelperClientStatusTest` остаётся).

Daemon onTransact: сперва `getCallingUid() != expectedUid → return false` (reply пуст),
затем `data.enforceInterface(DESCRIPTOR)`, switch по code, для READ/WRITE — существующий
parcel-танец к autoservice (`writeInterfaceToken(autoIface) + dev + fid + [value], transact, readInt`).
App при `transact()==false` или пустом reply → null/false (fail-soft).

## Спавн-команда (hardcoded, неинъектируемая)

```
CLASSPATH=<packageCodePath> setsid app_process /system/bin \
  --nice-name=bydmate_helper com.bydmate.app.helper.HelperDaemon <appUid> \
  </dev/null >/data/local/tmp/bydmate_helper.log 2>&1 &
```

`packageCodePath` из нашего context, `appUid` из `android.os.Process.myUid()` — оба наши,
инъекции нет. Идёт через `p.exec` (raw protocol), минуя публичный write-barrier (как сейчас).

## Задачи (subagent-driven-development, implementers = sonnet)

### Task 1 — Binder foundation
- `app/build.gradle.kts`: добавить `implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")`.
- `BYDMateApp.onCreate`: `HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;")`
  (только при SDK_INT>=28; surgical, НЕ blanket "L").
- Создать `HelperBinderProtocol.kt` с константами контракта выше.
- Компиляция чистая. (Foundation, поведение не меняется.)

### Task 2 — Binder daemon (новый файл в app-модуле)
- Создать `app/src/main/kotlin/com/bydmate/app/helper/HelperDaemon.kt`:
  `main(args)`: `expectedUid = args[0].toInt()`; single-owner через файловый lock (см. ниже);
  resolve autoservice IBinder (как сейчас, reflection ServiceManager.getService); создать
  `Binder()` subclass с onTransact (uid-check + enforceInterface + PING/READ/WRITE → autoservice
  parcel-танец); `ServiceManager.addService(SERVICE_NAME, binder)` reflection; `Looper.prepareMainLooper()`
  + `Looper.loop()`.
- **Single-owner (finding 3)**: lock `/data/local/tmp/bydmate_helper.lock` через `FileChannel.tryLock`.
  Хелпер `acquireSingleOwnerLock(path): FileLock?` → lock или null если занят (cross-process tryLock
  возвращает null; same-JVM `OverlappingFileLockException` ловим и тоже → null). `FileChannel`+`FileLock`
  держим в ЖИВЫХ переменных `main` до выхода процесса (Looper.loop блокирует → стек жив → не GC).
  Если null → демон уже поднят → `return` (exit 0) БЕЗ addService. TDD: два channel'а к одному файлу,
  первый tryLock != null, второй == null.
- **Диагностика (finding 2)**: демон печатает `READY` / `ERR: <причина>` в stdout (спавн редиректит
  в лог-файл, не /dev/null — см. Task 5), чтобы bootstrap прочитал причину при неудачном poll.
- **Комментарий (finding 5)**: входящие transact обслуживает binder-threadpool, который `app_process`
  поднимает сам (`AppRuntime::onStarted` → `ProcessState::startThreadPool`); `Looper.loop()` тут ТОЛЬКО
  keepalive, чтобы main не завершился. Зафиксировать это в комментарии к коду.
- `proguard-rules.pro`: `-keep public class com.bydmate.app.helper.HelperDaemon { public static void main(java.lang.String[]); }`
  (defensive — reflective entry point; minify сейчас off, но это foot-gun на будущее).
- Порт parcel-логики к autoservice — из СТАРОГО helper-dex/HelperDaemon (это наш код). Clean-room ок.
- Компиляция в составе app чистая.

### Task 3 — Удалить старый dex-модуль + wiring
- Удалить каталог `helper-dex/` (модуль).
- `settings.gradle.kts`: убрать `include(":helper-dex")`.
- `app/build.gradle.kts`: убрать `evaluationDependsOn(":helper-dex")`, `helperDexJarProvider`,
  `helperDexRuntimeClasspath`, `buildHelperDex` task, `tasks.named("preBuild"){dependsOn(buildHelperDex)}`,
  все ссылки на helperDexFile/helperDexShaFile.
- Удалить `app/src/main/assets/helper.dex` + `app/src/main/assets/helper.dex.sha256`.
- Gradle sync / compile чистый.

### Task 4 — App-side binder client
- Переписать `HelperClientImpl` (TCP → binder): lazy `ServiceManager.getService(SERVICE_NAME)`
  reflection (exempted), кэш IBinder, на `DeadObjectException` сбросить кэш; read/write/isAlive
  через `IBinder.transact` + HelperBinderProtocol; маппинг через writeAccepted/readAccepted.
  Убрать Socket/InetSocketAddress/PORT/CONNECT/SO/REQ-таймауты. Интерфейс `HelperClient` и
  companion writeAccepted/readAccepted — без изменений. Сохранить withTimeoutOrNull + mutex.
- Удалить `HelperProtocol.kt` (JSON DTO) и `HelperClientProtocolTest.kt` (тест DTO).
- TDD (Robolectric): фейковый локальный `Binder` с onTransact, проверить read возвращает value
  при status==0 и null иначе; write true при status>=0; isAlive по ping. **DeadObjectException (finding 4)**:
  первый закэшированный binder бросает `DeadObjectException` на transact → клиент сбрасывает кэш,
  повторный getService отдаёт живой binder → вызов успешен. `HelperClientStatusTest` остаётся.

### Task 5 — Спавн-рецепт (AdbOnDeviceClient + HelperBootstrap)
- `AdbOnDeviceClient`: заменить `bootstrapHelper(context, expectedSha256)` на `spawnHelper(): Boolean` —
  строит спавн-команду выше (`ctx.packageCodePath` + `Process.myUid()`, редирект в
  `/data/local/tmp/bydmate_helper.log`), шлёт `p.exec`, true если не бросило. Убрать чтение
  dex/sha/base64/push и inline TCP-poll. Обновить интерфейс.
- **Диагностика (finding 2)**: добавить `readHelperLog(): String?` (`p.exec("cat /data/local/tmp/bydmate_helper.log")`).
- `HelperBootstrap.ensureRunning`: ping (`helper.isAlive`) → `adb.spawnHelper()` → poll
  `helper.isAlive()` до 3 с (15×200мс) → вернуть результат. При неудачном poll — прочитать
  `adb.readHelperLog()` и залогировать причину (READY/ERR демона). Убрать `expectedDexSha256`/SHA_ASSET.
- `TrackingService` строки 265-269: обновить комментарий (не «127.0.0.1:8765 socket», а «native
  binder service»).
- TDD: `AdbOnDeviceClientTest` — через FakeProtocol.execCalls проверить, что spawnHelper строит
  команду с `setsid`, `CLASSPATH=`, `app_process`, `--nice-name=bydmate_helper`,
  `com.bydmate.app.helper.HelperDaemon`, uid, редирект в `bydmate_helper.log`; и НЕ содержит
  `base64`/`echo`/`helper.dex` (нет push).

### Task 6 — Верификация + clean-room gate
- Полный `./gradlew :app:testDebugUnitTest` зелёный (JAVA_HOME 17, ANDROID_HOME).
- `./gradlew :app:assembleDebug` собирается (подтверждает удаление helper-dex wiring + что
  HelperDaemon попал в dex приложения).
- Clean-room grep по `app/`: 0 hits `kramskyi|byd_ev_pro|evpro|vehicled|IVehicleState|AutoserviceBridge|ProcessExemptionController`.
- Removal grep: 0 hits `8765|ServerSocket|HelperProtocol|HelperRequest|HelperResponse` в app/src;
  0 ссылок `:helper-dex|buildHelperDex|helper\.dex` в settings/build.
- Lint чистый.

## On-device checkpoints (проверить на машине при тесте — НЕ верифицируемо локально)

1. **`ServiceManager.addService` под shell uid** не блокируется SELinux на DiLink (конкурент это
   делает — ожидаем PASS). Если SecurityException — НЕ откат на сокет (рабочей версии нет),
   а форвард-фикс: добавить сокет-листенер в новый демон (доставка setsid+apk-classpath уже общая),
   второй визит на машину.
2. **base.apk читается shell uid** как CLASSPATH (конкурент грузит свой apk так же — ожидаем PASS).
3. Действие (свет/окно/Алиса) реально срабатывает; `vehicle_write_log` пишет status=0.
4. При сбое старта — прочитать `/data/local/tmp/bydmate_helper.log` (READY/ERR демона) для диагностики.

## Релиз

Сборку/бамп/`gh release`/merge в main — НЕ делать до явной команды Andy после его теста на машине.
После Task 6 — codex-rescue аудит, затем спросить про сборку APK для теста.
