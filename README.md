<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="BYDMate icon">

# BYDMate

### Trip Logger & Energy Analytics for BYD DiLink 5.0

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![GitHub release](https://img.shields.io/github/v/release/AndyShaman/BYDMate?style=flat-square)](https://github.com/AndyShaman/BYDMate/releases)

**Реальный расход, GPS-маршруты, автоматизация, AI-аналитика — локально, без облака.**

[Возможности](#-возможности) | [Скриншоты](#-скриншоты) | [Автоматизация](#-автоматизация) | [AI Инсайты](#-ai-инсайты) | [Установка](#-установка) | [Сборка](#-сборка-из-исходников)

</div>

---

## Зачем это нужно

Штатный бортовой компьютер BYD **занижает расход на 10-30%**. BYDMate берёт данные напрямую из BMS (energydata) и показывает реальное потребление. Плюс данные, которых нет в штатной системе: расход на стоянке, баланс ячеек, стоимость поездок, AI-аналитика.

Приложение работает **полностью локально** на головном устройстве DiLink 5.0 — никакие данные не покидают автомобиль (кроме опционального AI через OpenRouter).

---

## Возможности

| | Функция | Описание |
|---|---------|----------|
| **BMS** | Реальный расход | Данные BMS (energydata), не бортовой компьютер |
| **GPS** | Трекинг поездок | GPS-маршруты, дистанция, скорость |
| **AI** | AI Инсайты | Анализ вождения через LLM (OpenRouter) |
| **Idle** | Расход на стоянке | Мониторинг idle drain из energydata |
| **Bat** | Здоровье батареи | Температура, баланс ячеек, 12V |
| **Map** | Карта маршрута | osmdroid (OpenStreetMap) в деталях поездки |
| **Rules** | Автоматизация | Правила WHEN→THEN: триггеры по параметрам → команды D+ |
| **Auto** | Автозапуск | WorkManager, запускается при включении |
| **CSV** | Экспорт данных | Экспорт поездок и зарядок в CSV |

---

## Скриншоты

### Dashboard

<img src="docs/screenshots/dashboard.jpg" alt="Dashboard" width="800">

*SOC, расчётный пробег, статистика за период, AI-инсайт, здоровье батареи, расход на стоянке, последние поездки*

### AI Инсайты (развёрнуто)

<img src="docs/screenshots/dashboard-insight-expanded.jpg" alt="AI Insight expanded" width="800">

*Анализ эффективности вождения от LLM — расход, тренды, батарея, рекомендации*

### Здоровье батареи (развёрнуто)

<img src="docs/screenshots/dashboard-battery.jpg" alt="Battery health" width="800">

*Температура, 12V аккумулятор, баланс ячеек, напряжение*

### Поездки

<img src="docs/screenshots/trips.jpg" alt="Trips accordion" width="800">

*Аккордеон Месяц > День > Поездка с фильтрами и цветовой индикацией расхода*

### Настройки

<img src="docs/screenshots/settings.jpg" alt="Settings" width="800">

*Батарея, тарифы, валюта, AI-настройки (OpenRouter API), экспорт данных*

---

## Автоматизация

Вкладка **Автоматизация** позволяет создавать правила для автоматического управления автомобилем через D+ API.

### Принцип работы

**КОГДА** условие выполняется **→ ТОГДА** выполнить команду.

Примеры:
- SOC < 20% → включить внутреннюю циркуляцию
- Скорость > 0 → закрыть шторку
- Температура за бортом < 0 → включить подогрев зеркал

### Возможности

| | Описание |
|---|----------|
| **25 триггеров** | SOC, скорость, температура, двери, окна, давление шин, режим езды и др. |
| **37 команд** | Окна, климат, свет, замки, люк, зеркала — всё через D+ API |
| **Edge trigger** | Срабатывает только при переходе false→true (не повторяется каждые 3 сек) |
| **Cooldown** | Настраиваемая пауза между срабатываниями |
| **Подтверждение** | Опциональное уведомление перед выполнением |
| **Безопасность** | Окна не открываются на скорости > 80 км/ч, CAN/SHELL команды заблокированы |
| **Журнал** | Лог всех срабатываний с результатами |
| **Шаблоны** | 6 готовых правил для быстрого старта |

### Логика

- **AND** — все условия должны выполняться
- **OR** — достаточно одного условия
- **Только на P** — правило срабатывает только когда авто на паркинге

---

## Целевое устройство

| Параметр | Значение |
|----------|----------|
| Платформа | DiLink 5.0 (Android 12, API 32) |
| Процессор | Snapdragon 780G |
| Экран | 15.6" landscape, 1920x1200 |
| GMS | Нет (AOSP без Google Play Services) |
| Протестировано | BYD Leopard 3 (Fangchengbao Bao 3) |

---

## Как работает

```
BYD energydata (BMS SQLite)  →  HistoryImporter    →  Room DB  →  Compose UI
DiPlus API (localhost:8988)  →  TrackingService     ↗     ↓
Android LocationManager     →  TripTracker (GPS)    ↗   AI (OpenRouter)
DiPlus sendCmd API           ←  AutomationEngine   ←  Rules (Room DB)
```

| Данные | Источник |
|--------|----------|
| Расход, пробег, длительность | BYD energydata (BMS) |
| SOC, скорость, температура | DiPlus API (`getDiPars`) |
| Напряжение ячеек, 12V | DiPlus API |
| GPS координаты | Android LocationManager |
| AI-аналитика | OpenRouter API (опционально) |
| Управление авто | DiPlus sendCmd API (автоматизация) |

**Без OBD-адаптера** — BYD блокирует сторонние OBD-устройства. BYDMate использует тот же API, что и встроенные приложения BYD.

---

## Установка

### 1. Подготовка DiLink

На головном устройстве должен быть установлен **[DiPlus (D+)](https://drive.google.com/file/d/1ndKgzh-HWRPrPw2eTbKh9pwhdDwYJ0Ug/view?usp=drive_link)** — приложение-мост для доступа к данным автомобиля. Установка через ADB:

```bash
adb connect <IP-адрес DiLink>:5555
adb install DiPlus.apk
```

IP-адрес DiLink можно найти в настройках Wi-Fi на головном устройстве.

### 2. Установка BYDMate

1. Скачайте BYDMate APK из [**Releases**](https://github.com/AndyShaman/BYDMate/releases)
2. Перенесите на DiLink: через USB-флешку, по сети, или через ADB (`adb install BYDMate.apk`)
3. Разрешите установку из неизвестных источников, если потребуется

### 3. Первый запуск

1. Откройте BYDMate — появится мастер настройки
2. Выдайте разрешения на **локацию** и **хранилище** (для GPS и чтения energydata)
3. Укажите **тарифы** на электроэнергию (для расчёта стоимости поездок)
4. Скопируйте команду автозапуска D+ — вставьте в терминал DiLink (один раз)

### 4. Фоновая работа

**Важно:** отключите "Disable background Apps" для BYDMate, иначе DiLink будет убивать приложение:

<img src="docs/screenshots/dilink-whitelist.jpg" alt="Disable background apps — toggle OFF for BYDMate" width="600">

*DiLink > Settings > General > Disable background Apps > BYDMate = **OFF***

### 5. Настройка (опционально)

В **Настройках** можно изменить:
- **Ёмкость батареи** — по умолчанию 72.9 кВт·ч (Leopard 3)
- **Тарифы** — домашний (AC) и быстрая зарядка (DC), валюта
- **Пороги расхода** — границы для цветовой индикации (зелёный/жёлтый/красный)

---

## AI Инсайты

BYDMate может анализировать вашу статистику вождения с помощью AI (LLM). Это опциональная функция — приложение полностью работает и без неё.

### Настройка

1. Зарегистрируйтесь на [OpenRouter](https://openrouter.ai/) (бесплатно)
2. В личном кабинете OpenRouter создайте **API Key** (раздел Keys)
3. В BYDMate откройте **Настройки** → раздел **AI Инсайты**
4. Вставьте API-ключ в поле "OpenRouter API Key"
5. Нажмите **"Выбрать модель"** — откроется список доступных LLM (есть бесплатные)
6. Нажмите **"Сохранить и получить инсайт"**

### Что анализирует

AI получает обезличенную статистику за 7 и 30 дней и возвращает:

- **Факты** — метрики, рассчитанные из реальных данных (расход с трендом, % коротких поездок, idle drain)
- **Инсайты** — корреляции, аномалии и поведенческие рекомендации от LLM

Запрос отправляется **раз в день**. Результат кэшируется локально. Никакие персональные данные (GPS, маршруты) не передаются — только агрегированная статистика.

---

## Сборка из исходников

```bash
# Требуется: JDK 17, Android SDK 34
git clone https://github.com/AndyShaman/BYDMate.git
cd BYDMate
./gradlew assembleDebug
```

---

## Стек технологий

- **Kotlin** 2.1 + **Jetpack Compose** + Material 3
- **Room** (SQLite) + **Hilt** (DI) + **OkHttp**
- **osmdroid** (OpenStreetMap) + **Coroutines/Flow**
- Min SDK 29 / Target SDK 29 / Compile SDK 34

---

## Благодарности

- **[BYD Trip Info](https://www.byd-seal-forum.de/forum/thread/1811-byd-trip-info-app/)** (`org.jayb.bydapp`) by jayb — оригинальное приложение для DiLink, вдохновение для BYDMate
- **[DiPlus](https://www.dilink.cn/)** (迪加) by Van Design — приложение-мост к данным автомобиля

---

## Лицензия

**GPLv3** с дополнительными условиями атрибуции.
См. [LICENSE](LICENSE) для деталей.

Copyright (C) 2026 [AndyShaman](https://github.com/AndyShaman)

---

<details>
<summary><b>English version</b></summary>

## What is BYDMate?

BYDMate is an Android app for BYD vehicles with DiLink 5.0 head unit (Leopard 3 / Fangchengbao Bao 3). It logs trips, GPS routes, real energy consumption from BMS, and provides AI-powered driving analytics — all locally on the head unit.

### Why?

The BYD onboard computer **underestimates consumption by 10-30%**. BYDMate reads real consumption data from the BMS (energydata SQLite database) and shows information not available in the stock system: idle drain, cell balance, trip costs, AI driving insights.

### Features

- **Real consumption** from BMS energydata (not onboard estimates)
- **Trip logging** with GPS routes, distance, speed
- **AI Insights** — LLM-powered driving analysis via OpenRouter (optional)
- **Idle drain** monitoring from BMS data
- **Battery health** — temperature, cell balance, 12V voltage
- **Trip map** with speed-colored routes (osmdroid, no Google Maps)
- **Automation** — WHEN→THEN rules: triggers on 25 parameters → 37 D+ commands (windows, climate, lights, locks, mirrors)
- **Auto-start** via WorkManager on boot
- **CSV export** for trips and charges

### How it works

BYDMate reads vehicle data from two sources:
- **BYD energydata** (built-in BMS SQLite database) — accurate per-trip consumption
- **DiPlus** app's local API (`localhost:8988`) — live SOC, speed, temperatures, cell voltages

No OBD adapter needed. No cloud/server — everything stays on the head unit (except optional AI via OpenRouter).

### Installation

1. Install **[DiPlus (D+)](https://drive.google.com/file/d/1ndKgzh-HWRPrPw2eTbKh9pwhdDwYJ0Ug/view?usp=drive_link)** on your DiLink head unit (requires ADB)
2. Download BYDMate APK from [Releases](https://github.com/AndyShaman/BYDMate/releases)
3. Transfer to DiLink via USB and install
4. Grant location + storage permissions
5. Disable "Disable background Apps" for BYDMate in DiLink Settings

### AI Insights

1. Get an API key from [OpenRouter](https://openrouter.ai/) (free models available)
2. Enter the key in BYDMate Settings and select a model
3. Click "Save and get insight"

AI analyzes 7-day and 30-day driving stats. Key metrics (consumption trends, short trips ratio, idle drain) are calculated deterministically. LLM provides correlations, anomalies, and behavioral advice in Russian.

### Building

```bash
# Requirements: JDK 17, Android SDK 34
git clone https://github.com/AndyShaman/BYDMate.git
cd BYDMate
./gradlew assembleDebug
```

### Credits

- **[BYD Trip Info](https://www.byd-seal-forum.de/forum/thread/1811-byd-trip-info-app/)** by jayb — original DiLink trip app, inspiration for BYDMate
- **[DiPlus](https://www.dilink.cn/)** by Van Design — local vehicle data API bridge

### License

GPLv3 with attribution. See [LICENSE](LICENSE).

</details>
