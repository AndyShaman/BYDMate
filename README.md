<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="BYDMate icon">

# BYDMate

### Trip & Charge Tracker for BYD DiLink 5.0

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![GitHub release](https://img.shields.io/github/v/release/AndyShaman/BYDMate?style=flat-square)](https://github.com/AndyShaman/BYDMate/releases)

**Реальная статистика расхода, зарядок, GPS-маршрутов и здоровья батареи — локально, без облака.**

[Возможности](#-возможности) | [Установка](#-установка) | [Как работает](#-как-работает) | [Сборка](#-сборка-из-исходников) | [Благодарности](#-благодарности)

</div>

---

## Зачем это нужно

Штатный бортовой компьютер BYD **занижает расход на 10–30%**. BYDMate считает реальное потребление через дельту SOC батареи и показывает данные, которых нет в штатной системе: расход на стоянке (idle drain), баланс ячеек, деградацию батареи, стоимость поездок.

Приложение работает **полностью локально** на головном устройстве DiLink 5.0 — никакие данные не покидают автомобиль.

---

## Возможности

| | Функция | Описание |
|---|---------|----------|
| **SOC** | Реальный расход | Дельта SOC метод вместо бортового компьютера |
| **GPS** | Трекинг поездок | GPS-маршруты, дистанция, скорость, температура |
| **AC/DC** | Зарядки | Power curve, определение AC/DC, расчёт стоимости |
| **Battery** | Здоровье батареи | Баланс ячеек, 12V, SOH, тренд деградации |
| **Idle** | Расход на стоянке | Мониторинг idle drain (отопление, кондиционер) |
| **Map** | Карта маршрутов | osmdroid (OpenStreetMap), без Google Maps |
| **Import** | Импорт истории | Автоимпорт из BYD energydata + DiPlus |
| **Update** | Автообновление | Проверка новых версий на GitHub |
| **Currency** | Мультивалюта | BYN, RUB, UAH, KZT, USD, EUR, CNY |

---

## Целевое устройство

| Параметр | Значение |
|----------|----------|
| Платформа | DiLink 5.0 (Android 12, API 32) |
| Процессор | Snapdragon 780G |
| Экран | 15.6" landscape, 1920x1200 |
| GMS | Нет (AOSP без Google Play Services) |
| Протестировано | BYD Leopard 3 (方程豹豹3) |

---

## Как работает

BYDMate получает данные автомобиля через локальный API приложения **DiPlus** (迪加) каждые 9 секунд:

```
DiPlus API (localhost:8988)  →  BYDMate Service  →  Room DB  →  Compose UI
         ↑                           ↓
   BYD CAN Bus              GPS + Location Manager
```

| Данные | Источник |
|--------|----------|
| SOC, скорость, мощность, температура | DiPlus API (`getDiPars`) |
| Напряжение ячеек, 12V батарея | DiPlus API |
| GPS координаты | Android LocationManager |
| История поездок | BYD energydata (SQLite) |

**Без OBD-адаптера** — BYD блокирует сторонние OBD-устройства и может сбрасывать ошибки после OTA-обновлений. BYDMate использует тот же API, что и встроенные приложения BYD.

---

## Установка

1. Скачайте APK из [**Releases**](https://github.com/AndyShaman/BYDMate/releases)
2. Перенесите на DiLink через USB-флешку или по сети
3. Установите (разрешите установку из неизвестных источников)
4. При первом запуске выдайте разрешения на локацию и хранилище
5. Приложение автоматически запустит DiPlus если он не активен

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
- Min SDK 29 · Target SDK 29 · Compile SDK 34

---

## Благодарности

BYDMate создан благодаря работе и вдохновению следующих проектов:

- **[BYD Trip Info](https://www.byd-seal-forum.de/forum/thread/1811-byd-trip-info-app/)** (`org.jayb.bydapp`) by jayb — оригинальное приложение для чтения energydata и отображения статистики поездок на DiLink. Именно оно вдохновило на создание BYDMate и послужило reference-реализацией для работы с BYD energydata.

- **[DiPlus](https://www.dilink.cn/)** (迪加) by Van Design — приложение-мост к данным автомобиля через локальный HTTP API. Без DiPlus работа BYDMate была бы невозможна.

- **[BYD Trip Stats](https://github.com/angoikon/byd-trip-stats)** by [@angoikon](https://github.com/angoikon) — аналитический дашборд для BYD DiLink. Альтернативный подход через MQTT/Electro bridge.

- **[TeslaMate](https://github.com/teslamate-org/teslamate)** — open-source трекер для Tesla, послуживший ориентиром по UX и функциональности (trip logging, idle drain, battery degradation).

---

## Лицензия

**GPLv3** с дополнительными условиями атрибуции.
См. [LICENSE](LICENSE) для деталей.

Copyright (C) 2026 [AndyShaman](https://github.com/AndyShaman)

---

<div align="center">

*Сделано для владельцев BYD, которые хотят знать реальный расход своего электромобиля.*

</div>

---

<details>
<summary><b>English version</b></summary>

## What is BYDMate?

BYDMate is an Android app for BYD vehicles with DiLink 5.0 head unit (Leopard 3 / Bao 3). It tracks trips, charging sessions, GPS routes, and real energy consumption — all locally on the head unit, no cloud required.

### Why?

The BYD onboard computer **underestimates consumption by 10–30%**. BYDMate calculates real consumption via battery SOC delta and shows data not available in the stock system: idle drain, cell balance, battery degradation, trip costs.

### Features

- **Real consumption** via delta SOC method (not onboard estimates)
- **Trip logging** with GPS routes, distance, speed, temperature
- **Charge tracking** with power curve, AC/DC detection, cost calculation
- **Battery health** — cell balance, 12V monitoring, SOH degradation tracking
- **Idle drain** monitoring while parked
- **Interactive map** with trip routes (osmdroid, no Google Maps)
- **Auto-import** from BYD energydata + DiPlus databases
- **Auto-update** from GitHub Releases
- **Multi-currency** support

### How it works

BYDMate reads vehicle data from the **DiPlus** app's local API (`localhost:8988`) every 9 seconds. No OBD adapter needed — uses the same API as BYD's built-in apps. No cloud/server — everything stays on the head unit.

### Installation

1. Download APK from [Releases](https://github.com/AndyShaman/BYDMate/releases)
2. Transfer to DiLink via USB
3. Install and grant location + storage permissions

### Building

```bash
# Requirements: JDK 17, Android SDK 34
git clone https://github.com/AndyShaman/BYDMate.git
cd BYDMate
./gradlew assembleDebug
```

### Credits

- **[BYD Trip Info](https://www.byd-seal-forum.de/forum/thread/1811-byd-trip-info-app/)** by jayb — original energydata reader for DiLink, the inspiration for BYDMate
- **[DiPlus](https://www.dilink.cn/)** by Van Design — local vehicle data API bridge
- **[BYD Trip Stats](https://github.com/angoikon/byd-trip-stats)** by @angoikon — alternative BYD analytics dashboard
- **[TeslaMate](https://github.com/teslamate-org/teslamate)** — open-source Tesla tracker, UX reference

### License

GPLv3 with attribution. See [LICENSE](LICENSE).

</details>
