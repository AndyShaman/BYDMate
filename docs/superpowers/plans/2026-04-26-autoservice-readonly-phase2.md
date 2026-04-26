# Phase 2 — autoservice-readonly: UI surface + adblib integration

**Branch:** `feature/autoservice-readonly` (Phase 1 HEAD `63ec8bc`, off `main@850d8d8`)
**Spec:** `docs/superpowers/specs/2026-04-26-autoservice-readonly-design.md` § 5.4–5.9
**UI mock (approved 2026-04-26):** `docs/superpowers/mocks/2026-04-26-phase2-ui.html`
**Phase 1 plan (closed, 17/17):** `docs/superpowers/plans/2026-04-26-autoservice-readonly-phase1.md`

## Goal

Дать юзеру **end-to-end рабочий** autoservice flow: включил toggle в Настройках → app pair'ится с DiLink ADB через minimal-pairing dialog → BatteryStateRepository отдаёт реальные данные → catch-up детектор пишет SoH-замеры → новый таб «Зарядки» в Trips-style показывает иерархию + Lifetime AC/DC + Эквив. циклов + mini-графики SoH/Capacity. Battery card на Главной кликабельна, ведёт в BatteryHealthScreen. TopBar показывает «ADB не отвечает» если соединение упало.

## Decisions (фиксируем перед стартом)

- **Номинал батареи** — из настроек (`SettingsRepository.getBatteryCapacityKwh()`, default 72.9), НЕ hardcoded.
- **ADB transport** (REVISED 2026-04-26) — hand-rolled Kotlin port competitor's `AdbClient.java` (`.research/competitor/decompiled/`). NO `adblib` JitPack dep (она не существует на MavenCentral как `cgutman/adblib`). NO 6-значного pairing'а — competitor использует старый ADB pubkey auth на 127.0.0.1:5555 (proven на Leopard 3); DiLink сам показывает нативный «Allow USB debugging» dialog. См. C0 для деталей.
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

### C0 — hand-rolled ADB protocol client + RSA pubkey auth (no 6-digit pairing)

**Decision (2026-04-26):** Use ADB pubkey auth on `127.0.0.1:5555` (proven working by competitor BYD EV Pro on Leopard 3). NO TLS pairing, NO 6-digit code, NO mDNS port discovery, NO `adblib` dependency. DiLink's `adbd` already listens on 5555 once user enables Wireless ADB in Developer Options. Auth flow: app sends RSA-signed AUTH token; if key unknown, DiLink shows native «Allow USB debugging from this computer? RSA fingerprint: XX. ☑ Always allow from this computer» dialog; user accepts on DiLink itself. No in-app pairing UI needed.

**Reference:** `.research/competitor/decompiled/AdbClient.java` (~420 lines, JADX-decompiled). Implements full ADB CONNECT/AUTH/OPEN handshake, RSA pubkey serialization for ADB, and shell stream exec. **Proven on Leopard 3.** One method (`g(AdbClient, String)`) failed to decompile — IGNORE it, port the synchronized `d(String)` instead (lines 194-261 — same purpose, better implementation).

**Files:**
- `app/src/main/kotlin/com/bydmate/app/data/autoservice/AdbProtocolClient.kt` (NEW — Kotlin port of competitor's AdbClient.java; pure JDK, no external deps)
- `app/src/main/kotlin/com/bydmate/app/data/autoservice/AdbKeyStore.kt` (NEW — RSA keypair persisted as PKCS8/X.509 binary in `context.filesDir/adb_keys/`)
- `app/src/main/kotlin/com/bydmate/app/data/autoservice/AdbOnDeviceClient.kt` (replace stub `ensureConnected()`/`doExec()`/`tryPing()`; add `connect()` to interface)
- `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt` (add `suspend fun tryConnect(): Result<Unit>`)
- `app/src/test/kotlin/com/bydmate/app/data/autoservice/AdbKeyStoreTest.kt` (NEW)
- `app/src/test/kotlin/com/bydmate/app/data/autoservice/AdbProtocolClientTest.kt` (NEW — packet construction unit tests, no real socket)
- `app/src/test/kotlin/com/bydmate/app/data/autoservice/AdbOnDeviceClientTest.kt` (NEW — write barrier + connect lifecycle, with fake AdbProtocolClient seam)
- `app/src/test/kotlin/com/bydmate/app/data/autoservice/AutoserviceClientImplTest.kt` (UPDATE existing fake to implement new `connect()` method)

**Explicit non-changes:** NO `app/build.gradle.kts` change (no JitPack, no adblib). NO `AdbPairingDialog.kt`. NO `tryPair(code)` method.

**Implementation:**

1. **`AdbKeyStore` (@Singleton, @Inject):**
   - Persistent RSA keypair in `context.filesDir/adb_keys/{adb_key.priv, adb_key.pub}` as raw bytes (PKCS8 private, X.509 public — same format competitor uses, lines 271-272).
   - `fun loadOrGenerate(): KeyPair` — atomic: if both files exist & parse → load; else generate 2048-bit RSA, save both, return. On parse failure → regenerate (mirror competitor lines 269-288).
   - `fun getFingerprint(): String` — SHA-1 hex of public key encoded form, for logs.

2. **`AdbProtocolClient` (Kotlin port of `.research/competitor/decompiled/AdbClient.java`):**
   - Constructor: `class AdbProtocolClient(private val keyStore: AdbKeyStore, private val host: String = "127.0.0.1", private val port: Int = 5555)`.
   - **Magic constants** (verified from competitor source):
     ```
     A_CNXN = 0x4E584E43 (1314410051)
     A_AUTH = 0x48545541 (1213486401)
     A_OPEN = 0x4E45504F (1313165391)
     A_OKAY = 0x59414B4F (1497451343)  // bytes 'O','K','A','Y' little-endian — DECIMAL is the source of truth, hex was byte-swapped in earlier draft
     A_CLSE = 0x45534C43 (1163086915)  // bytes 'C','L','S','E' little-endian
     A_WRTE = 0x45545257 (1163154007)  // bytes 'W','R','T','E' little-endian
     A_VERSION_AUTH = 0x01000001 (16777217)
     MAX_PAYLOAD = 262144
     AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3
     ```
   - **ADB_AUTH_PADDING**: `byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)` (15 bytes — competitor `f778g` line 43).
   - **`fun connect(): Boolean`** — full handshake (port from competitor `b()` lines 66-132 + `e()` lines 263-367):
     - Open socket to `127.0.0.1:5555`, soTimeout 5000 ms, tcpNoDelay true.
     - Send `A_CNXN(version, MAX_PAYLOAD, "host::\0".bytes)`.
     - Read response. If `A_CNXN` → connected (no auth required). If `A_AUTH(arg0=1, payload=token)` → auth flow:
       - Sign `ADB_AUTH_PADDING ‖ token` with `Signature.getInstance("NONEwithRSA")` using RSA private key.
       - Send `A_AUTH(arg0=2, signature)`.
       - If response is `A_CNXN` → success (key cached).
       - If response is `A_AUTH(arg0=1)` again → key unknown to device. Send `A_AUTH(arg0=3, adbFormatPubkey)` — DiLink shows native dialog. Set socket soTimeout to 60000 ms while waiting for user. After user taps «Always allow» → `A_CNXN` → success. Restore soTimeout to 5000 ms.
     - On any unexpected response or socket error → close, return false.
     - Thread-safe via `synchronized(this)` block (matches competitor `ReentrantLock` pattern; class-level lock, not the static one — that's competitor-specific).
   - **`fun exec(cmd: String): String?`** (port competitor `d()` lines 194-261):
     - `synchronized` block.
     - If `!isConnected()` → null.
     - Increment local stream id (`localId`), send `A_OPEN(localId, 0, "shell:<cmd>\0".bytes)`.
     - Loop reading packets up to 20 times waiting for `A_OKAY(arg0=remoteId, arg1=localId)` from device.
     - Then loop reading WRTE packets (up to 500), accumulating payload as String. ACK each WRTE with `A_OKAY(localId, remoteId, empty)`. Stop on `A_CLSE` — reply with `A_CLSE` and break.
     - Return accumulated string trimmed.
     - On exception → close socket, return null.
   - **`fun isConnected(): Boolean`** — socket non-null, isConnected, !isClosed (port competitor `f()` lines 369-373).
   - **`fun disconnect()`** — close socket, null out streams (port competitor `c()` lines 180-192).
   - **Packet I/O** (port competitor `i()` reader and `j()` writer, lines 390-420):
     - Header: 24 bytes little-endian — `[command(4)][arg0(4)][arg1(4)][payloadLen(4)][checksum(4)][magic(4)]`.
     - `magic = command xor 0xFFFFFFFF` (i.e. `~command`).
     - `checksum = sum(payload[i] & 0xFF for i in payload)` (low-byte unsigned sum).
   - **Public key serialization** (port competitor `e()` lines 302-323):
     - 524-byte ByteBuffer LITTLE_ENDIAN: `[modSizeWords=64 (i32)][n0inv (i32)][modulus (64 i32 little-endian)][rr (64 i32 little-endian)][exponent (i32)]` where `n0inv = -modulus.and(2^32-1).modInverse(2^32) mod 2^32` and `rr = (1 << 4096) mod modulus`.
     - Base64 encode (no wrap), append `" bydmate@dilink "` and convert to UTF-8 bytes.

3. **`AdbOnDeviceClientImpl` rewrite:**
   - Inject `AdbKeyStore`. (Drop `Context` if unused — but keep for future-proofing; AdbKeyStore needs Context anyway.)
   - Hold `private var protocol: AdbProtocolClient? = null` (under `@Volatile`).
   - **Add to interface:** `suspend fun connect(): Result<Unit>` — lazy-create `AdbProtocolClient(keyStore)`, call `protocol.connect()` on Dispatchers.IO. Return `Result.success(Unit)` on `true`, `Result.failure(IOException("ADB connect refused"))` on `false`. Catch exceptions → `Result.failure(e)`.
   - `isConnected()` — delegate to `protocol?.isConnected() ?: false`.
   - `exec(cmd)` — keep `WRITE_BARRIER_REGEX` guard. If `protocol == null` → return null. Else `protocol.exec(cmd)`.
   - `shutdown()` — `protocol?.disconnect(); protocol = null`.
   - **Remove:** stub inner class `AdbConnectionHandle`, `tryPing()`, `doExec()`, `ensureConnected()` throwing stub.
   - **Keep:** `WRITE_BARRIER_REGEX` regex unchanged (Phase 1 spec invariant).

4. **`SettingsViewModel`:**
   - Add `suspend fun tryConnect(): Result<Unit>` — delegates to `adbOnDeviceClient.connect()`. Update UI state via `loadAutoserviceState()` after success/failure (loadAutoserviceState comes in C1; for C0 just expose the function).
   - **Note for C0:** there is no UI button yet (that's C2). For manual smoke during C0, the implementer should add a temporary debug log in `init { viewModelScope.launch { tryConnect()... } }` OR provide a debug menu hook — but it MUST be reverted before completion. Acceptable C0 stub: just expose `tryConnect()` as public method, smoke-test via Android Studio Logcat + breakpoint, OR via direct DI-injected call from a debug `MainActivity.onCreate` trigger that's reverted before commit.

**Tests:**

- **`AdbKeyStoreTest`** (Robolectric for filesDir):
  - `loadOrGenerate_firstCall_generatesAndPersistsKeypair` (assert files exist after, public key parseable as RSA 2048).
  - `loadOrGenerate_secondCall_returnsSameKeypair` (compare encoded bytes equal across two calls).
  - `loadOrGenerate_corruptedPrivateFile_regenerates` (delete priv, leave pub → should regenerate both, no exception).
  - `getFingerprint_isStable` (same fingerprint across two `loadOrGenerate()` calls).

- **`AdbProtocolClientTest`** (no real socket — test pure helpers via internal-visible methods or via package-private):
  - `packet_checksum_isUnsignedByteSumOfPayload` (give known payload, assert checksum field).
  - `packet_magic_isBitwiseInverseOfCommand` (`A_CNXN xor 0xFFFFFFFF.toInt()` matches header).
  - `packet_header_isLittleEndian24Bytes` (encode A_OPEN packet, decode header back, fields match).
  - `publicKey_serialization_isExactly524Bytes_plusBase64TailIncludesUsername` (encode keypair, assert size + Base64 trailing string contains "bydmate@dilink ").
  - `signaturePayload_prependsAdbAuthPadding` (give 20-byte token, assert signed input is 35 bytes starting with the 15 padding bytes).

- **`AdbOnDeviceClientTest`** (no real socket; use a fake AdbProtocolClient via constructor seam — make `AdbOnDeviceClientImpl` accept an optional `protocolFactory: (AdbKeyStore) -> AdbProtocolClient` for tests):
  - `exec_writeCommand_throws_writeBarrier` (Phase 1 invariant: `setInt`-style commands rejected).
  - `exec_disconnected_returnsNull`.
  - `connect_protocolReturnsTrue_returnsSuccess`.
  - `connect_protocolReturnsFalse_returnsFailure`.
  - `connect_protocolThrows_returnsFailure_doesNotCrash`.
  - `shutdown_idempotent_doesNotCrashWhenNeverConnected`.
  - `shutdown_afterConnect_disconnectsProtocol`.

- **`AutoserviceClientImplTest`** (existing) — UPDATE its anonymous `AdbOnDeviceClient` fake to implement new `connect(): Result<Unit>` method (return `Result.success(Unit)` by default — keeps existing tests green).

**Acceptance:**

- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL.
- All new tests green; all Phase 1 tests still green (`./gradlew :app:testDebugUnitTest`).
- ❗ **Manual smoke on Leopard 3 (Andy on the car, mandatory gate before C1-C5):**
  - Step 1 — On DiLink: `Settings → System → Developer options → Wireless debugging → ON`. (One-time. If toggle missing, enable Developer mode first via 7 taps on Build number.)
  - Step 2 — Install C0 APK on DiLink.
  - Step 3 — Trigger `viewModel.tryConnect()` (per «Note for C0» above — temporary debug hook reverted before final commit).
  - Step 4 — On first attempt, DiLink shows native «Allow USB debugging from this computer? RSA fingerprint: XX:XX:...» dialog. Tap «Always allow from this computer» → OK. Note the fingerprint matches `keyStore.getFingerprint()` in Logcat.
  - Step 5 — `tryConnect()` returns `Result.success(Unit)`. `adb.exec("service call autoservice 5 i32 1014 i32 1246777400")` returns SOC parcel string (per `feedback_autoservice_validated.md`). Reading SoH/lifetime via `BatteryStateRepository.refresh()` returns real values (not nulls).
  - Step 6 — Restart app. `tryConnect()` succeeds silently (key cached on DiLink, no dialog).
- If manual smoke fails — implementer logs **specific** auth step that died (which packet command code arrived as response, e.g. «expected A_CNXN, got 0xXXXXXXXX»). Escalate.

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
           AutoserviceStatusBlock(state.autoserviceStatus, onConnect = { vm.tryConnect() })
           if (state.autoserviceEnabled) {
               Row(...) {
                   Text("Спрашивать после каждой зарядки", ...)
                   Switch(checked = state.chargingPromptEnabled, ..., colors = bydSwitchColors())
               }
           }
       }
   }
   ```
   No pairing dialog — DiLink itself shows the native «Allow USB debugging from this computer» prompt the first time the app calls `tryConnect()`. See C0 decision note.
3. `AutoserviceStatusBlock(status: AutoserviceStatus, onConnect: () -> Unit)` — render по 4 substate'ам (см. mock `<div class="status-block">`):
   - `NotEnabled` → пусто.
   - `Disconnected` → `Text("✗ не подключено · включите Wireless ADB в Developer options DiLink, затем подтвердите запрос на машине", color = TextMuted)` + Button `[Подключить к ADB]` → `onConnect()`.
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
  - 1. На DiLink: Developer options → Wireless debugging → ON. App startup → Настройки → toggle «Системные данные» ON → кнопка «Подключить к ADB» → DiLink показывает нативный «Allow USB debugging from this computer» с RSA fingerprint → «Always allow» → status переключается на Connected → видны SoH/lifetime.
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

1. ~~adblib JitPack совместимость~~ / ~~Pairing port discovery~~ / ~~EncryptedSharedPreferences~~ — закрыты решением C0 (ADB pubkey auth по 127.0.0.1:5555, hand-rolled, без 6-значного кода, без EncryptedSharedPreferences).
2. **«Раскрыть первый месяц по умолчанию»** — UX gut feeling, при `period = ALL` может быть много месяцев. Возможно лучше: текущий месяц всегда раскрыт.
3. **Mini-charts при < 2 данных** — рендерить «недостаточно данных» текст или скрывать?
