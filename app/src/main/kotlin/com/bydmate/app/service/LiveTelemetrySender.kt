package com.bydmate.app.service

import android.location.Location
import android.util.Log
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.IternioIntervalPolicy
import com.bydmate.app.data.remote.IternioRateLimitException
import com.bydmate.app.data.remote.IternioServerErrorException
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.remote.IternioTelemetryClient
import com.bydmate.app.data.remote.WebhookTelemetryClient
import com.bydmate.app.service.TrackingService.Companion.locationForTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LiveTelemetrySender(
    private val settingsRepository: SettingsRepository,
    private val serviceScope: CoroutineScope,
    private val autoserviceClient: com.bydmate.app.data.autoservice.AutoserviceClient,
    private val iternioTelemetryClient: IternioTelemetryClient,
    private val webhookTelemetryClient: WebhookTelemetryClient,
) {
    // Shared by both telemetry sinks (Iternio + custom webhook): they ride the
    // same snapshot and the same cadence, so one timestamp gates both.
    private val telemetryLock = Any()
    @Volatile private var lastTelemetryMs: Long = 0L
    // Prevents two telemetry sends from overlapping: a slow ADB read can take
    // hundreds of ms, and stacking sends would burn the same in-flight ENG_POW
    // read across two parallel coroutines.
    private val iternioInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    // Upstream cooldown: bumped when Iternio returns 429 (Retry-After) or 5xx
    // (exponential backoff). We refuse to send until `now >= iternioCooldownUntilMs`.
    @Volatile private var iternioCooldownUntilMs: Long = 0L
    @Volatile private var iternioConsecutive5xx: Int = 0
    // Sends between two summary lines, and the 10-min sample of a send's contents.
    private val iternioTally = com.bydmate.app.data.remote.SendTally()
    private val iternioLogThrottle = com.bydmate.app.data.autoservice.LogThrottle(com.bydmate.app.data.remote.SendTally.WINDOW_MS)
    // Last cadence state we logged. A trip log has to show the moment the app decided
    // «стоим» — the interval change is otherwise invisible from the outside.
    @Volatile private var lastTelemetryState: IternioIntervalPolicy.TelemetryState? = null
    // Webhook cooldown: user endpoints go down for days (VPS off, tunnel gone).
    // Flat 60 s after any failure — a dead URL then costs one request per minute
    // instead of one per second while driving.
    @Volatile private var webhookCooldownUntilMs: Long = 0L
    // The service's location bus: the same flow TrackingService.lastLocation exposes.
    private val _lastLocation: StateFlow<Location?> = TrackingService.lastLocation

    /**
     * Отправка живой телеметрии с адаптивной частотой (см.
     * [IternioIntervalPolicy]): 1 с в движении, 8 с при зарядке, 30 с на
     * парковке. Бессмысленно слать с одинаковым ритмом — ABRP калибрует
     * точность по плотности сэмплов за 10 секунд, и единственное окно где
     * нам нужен 1 Гц — это движение.
     *
     * Получателей два и они независимы: [IternioTelemetryClient] (ABRP) и
     * [WebhookTelemetryClient] (свой URL пользователя). Включены могут быть
     * оба, один или ни одного. JSON строится ОДИН раз на тик; координаты
     * подмешиваются копией на того получателя, у кого включён свой тумблер.
     *
     * Single-flight на [iternioInFlight] не даёт двум tick'ам пересекаться:
     * сетевая отправка (и, в CHARGING-окне, autoservice-снапшоты battery/charging)
     * может занять несколько сотен мс, а очередь параллельных отправок забила бы
     * канал и спутала throttle. Остывание раздельное: на 429/5xx взводим
     * [iternioCooldownUntilMs], на любую ошибку вебхука — [webhookCooldownUntilMs],
     * и тихо пропускаем тики пока не остынет.
     */
    fun maybeSendIternioTelemetry(data: DiParsData, nowMs: Long) {
        if (!iternioInFlight.compareAndSet(false, true)) return
        // Capture the snapshot timestamp BEFORE the network round-trip so the
        // `utc` field upstream matches the moment of sampling, not delivery.
        val snapshotMs = nowMs
        serviceScope.launch {
            try {
                val token = settingsRepository.getString(
                    com.bydmate.app.data.repository.SettingsRepository.KEY_ABRP_USER_TOKEN,
                    ""
                ).trim()
                val abrpOn = settingsRepository.getString(
                    com.bydmate.app.data.repository.SettingsRepository.KEY_ABRP_ENABLED,
                    "false"
                ) == "true" && token.isNotEmpty()

                val webhookUrl = settingsRepository.getString(
                    com.bydmate.app.data.repository.SettingsRepository.KEY_WEBHOOK_URL,
                    ""
                ).trim()
                // Snapshot URL and secret together: the Iternio round-trip below can take
                // seconds, and a settings edit mid-tick must not pair a stale URL with a fresh secret.
                val webhookSecret = settingsRepository.getString(
                    com.bydmate.app.data.repository.SettingsRepository.KEY_WEBHOOK_SECRET,
                    ""
                )
                val webhookOn = settingsRepository.getString(
                    com.bydmate.app.data.repository.SettingsRepository.KEY_WEBHOOK_ENABLED,
                    "false"
                ) == "true" && webhookUrl.isNotEmpty()

                if (!abrpOn && !webhookOn) return@launch

                val state = IternioIntervalPolicy.classifyFromDiPars(data)
                val intervalSec = IternioIntervalPolicy.intervalSec(state)
                val intervalMs = intervalSec * 1000L
                if (state != lastTelemetryState) {
                    Log.i(TAG, "Iternio state: ${lastTelemetryState ?: "-"} -> $state " +
                        "(gear=${data.gear} speed=${data.speed} gun=${data.chargeGunState})")
                    lastTelemetryState = state
                }
                synchronized(telemetryLock) {
                    if (snapshotMs - lastTelemetryMs < intervalMs) return@launch
                }

                // Cooldowns are per-target: a dead webhook must not silence ABRP.
                val sendToIternio = abrpOn && snapshotMs >= iternioCooldownUntilMs
                if (abrpOn && !sendToIternio) {
                    Log.d(TAG, "Iternio cooldown active, skip (until ${iternioCooldownUntilMs - snapshotMs}ms)")
                }
                val sendToWebhook = webhookOn && snapshotMs >= webhookCooldownUntilMs
                if (!sendToIternio && !sendToWebhook) return@launch

                val apiKey = settingsRepository.getString(
                    com.bydmate.app.data.repository.SettingsRepository.KEY_ABRP_API_KEY,
                    ""
                )
                val carModel = settingsRepository.getString(
                    com.bydmate.app.data.repository.SettingsRepository.KEY_ABRP_CAR_MODEL,
                    ""
                ).trim().takeIf { it.isNotEmpty() }

                val abrpSendLocation = settingsRepository.getString(
                    com.bydmate.app.data.repository.SettingsRepository.KEY_ABRP_SEND_LOCATION,
                    "false"
                ) == "true"
                val webhookSendLocation = settingsRepository.getString(
                    com.bydmate.app.data.repository.SettingsRepository.KEY_WEBHOOK_SEND_LOCATION,
                    "false"
                ) == "true"

                // Best-effort autoservice enrichment. Snapshots are heavier
                // (multiple fids) — only read them in CHARGING window where
                // is_dcfc / kwh_charged actually matter. In DRIVING we still
                // want ENG_POW every tick.
                val readSnapshots = state == IternioIntervalPolicy.TelemetryState.CHARGING
                val battery = if (readSnapshots) {
                    runCatching { autoserviceClient.readBatterySnapshot() }.getOrNull()
                } else null
                val charging = if (readSnapshots) {
                    runCatching { autoserviceClient.readChargingSnapshot() }.getOrNull()
                } else null
                // ENG_POW: reused from this tick's already-fetched snapshot instead of
                // a dedicated ADB read per send (FidMap "power" = dev 1012 / fid 339738656).
                // May be up to one poll tick stale, fine at
                // 1 Hz. Null → client falls back to DiPars power.
                val enginePowerKw: Int? = data.power?.toInt()

                // Built once per tick and shared: each target's own GPS toggle is the only
                // per-target difference, so those fields go into a copy (see [withLocation]).
                val telemetry = iternioTelemetryClient.buildTelemetry(
                    data = data,
                    nominalCapacityKwh = settingsRepository.getBatteryCapacity(),
                    battery = battery,
                    charging = charging,
                    carModel = carModel,
                    enginePowerKw = enginePowerKw,
                    sampleTimeMs = snapshotMs,
                ) ?: return@launch

                // Throttle advances only when at least one sink actually took the
                // sample, so a failed send still retries on the next tick.
                var delivered = false

                if (sendToIternio) {
                    // Position goes to ABRP only behind its own opt-in toggle, off by default.
                    // ABRP open on the head unit reads GPS itself and merges it with the telemetry
                    // position, so two sources make the car marker jump (ABRP tickets, June 2026).
                    // The toggle is for the narrow case of watching the car from ABRP on a phone
                    // while ABRP in the car is not running (@ptuchik, #302).
                    // Gate against the clock now, not snapshotMs: the settings and autoservice reads
                    // above suspend, and a fix that arrived meanwhile is newer than the snapshot.
                    val abrpLocation = locationForTelemetry(
                        abrpSendLocation, _lastLocation.value, System.currentTimeMillis(),
                    )
                    // A sample send every 10 min plus the tally below: without them a trip log shows
                    // nothing between two ABRP failures, and «данных нет» has no diagnosis. A line
                    // per send was thousands an hour.
                    if (iternioLogThrottle.shouldLog("send")) {
                        Log.i(TAG, "Iternio send: state=$state interval=${intervalSec}s " +
                            "gear=${data.gear} speed=${data.speed} soc=${data.soc} " +
                            "hv=${data.hvVoltage ?: "-"}/${data.hvCurrent ?: "-"} " +
                            "setpoint=${data.acTemp ?: "-"} gps=${abrpLocation != null}")
                    }
                    val sentAtMs = System.currentTimeMillis()
                    iternioTelemetryClient.sendTelemetry(
                        apiKey = apiKey,
                        userToken = token,
                        telemetry = withLocation(telemetry, abrpLocation),
                    ).onSuccess {
                        delivered = true
                        iternioConsecutive5xx = 0
                        val nowMs = System.currentTimeMillis()
                        iternioTally.record(true, nowMs - sentAtMs, nowMs)?.let { Log.i(TAG, "Iternio $it") }
                    }.onFailure { e ->
                        val nowMs = System.currentTimeMillis()
                        iternioTally.record(false, nowMs - sentAtMs, nowMs)?.let { Log.i(TAG, "Iternio $it") }
                        when (e) {
                            is IternioRateLimitException -> {
                                // Upstream said wait. Honor Retry-After if present;
                                // fall back to 5 min when the header was missing —
                                // long enough that we're not part of the storm,
                                // short enough that the user gets data back once
                                // the burst clears.
                                val backoffSec = e.retryAfterSec ?: 300
                                iternioCooldownUntilMs = snapshotMs + backoffSec * 1000L
                                Log.w(TAG, "Iternio 429, cooldown ${backoffSec}s")
                            }
                            is IternioServerErrorException -> {
                                // 5xx exponential backoff: 8 → 16 → 32 → 64 → 128 → 256 s
                                // (capped at 300 s). We don't bump throttle on success
                                // failures the user can't influence — wait for the
                                // CDN to recover.
                                iternioConsecutive5xx = (iternioConsecutive5xx + 1).coerceAtMost(6)
                                val backoffSec = (8 shl (iternioConsecutive5xx - 1)).coerceAtMost(300)
                                iternioCooldownUntilMs = snapshotMs + backoffSec * 1000L
                                Log.w(TAG, "Iternio ${e.httpStatus}, cooldown ${backoffSec}s (n=$iternioConsecutive5xx)")
                            }
                            else -> Log.w(TAG, "Телеметрия Iternio: ${e.message}")
                        }
                    }
                }

                if (sendToWebhook) {
                    val location = locationForTelemetry(
                        webhookSendLocation, _lastLocation.value, System.currentTimeMillis(),
                    )
                    webhookTelemetryClient.send(
                        url = webhookUrl,
                        secret = webhookSecret,
                        telemetry = withLocation(telemetry, location),
                    ).onSuccess {
                        delivered = true
                        webhookCooldownUntilMs = 0L
                    }.onFailure { e ->
                        // Flat 60 s: a user endpoint is either up or down, and
                        // growing backoff would just hide it coming back.
                        webhookCooldownUntilMs = System.currentTimeMillis() + 60_000L
                        Log.w(TAG, "Вебхук: ${e.message}, пауза 60 с")
                    }
                }

                if (delivered) {
                    synchronized(telemetryLock) {
                        lastTelemetryMs = snapshotMs
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Телеметрия Iternio: ${e.message}")
            } finally {
                iternioInFlight.set(false)
            }
        }
    }

    companion object {
        private const val TAG = "TrackingService"
    }
}
