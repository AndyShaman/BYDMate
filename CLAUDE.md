# BYDMate

Android app for BYD DiLink 5.0 head unit (Leopard 3 / 方程豹豹3).
Trip logger + charging tracker + GPS routes + real consumption statistics.

## Project Docs

- Design: `docs/plans/2026-03-28-bydmate-design.md` -- full spec, algorithms, DB schema, screens
- Implementation plan: `docs/plans/2026-03-28-bydmate-implementation.md` -- ordered build steps

## Key Decisions

- **No OBD** -- causes alarm on BYD, breaks after OTA. All data via DiPlus API + BYD's built-in SQLite.
- **No cloud/server** -- everything local on DiLink head unit.
- **No Google Play Services** -- DiLink runs AOSP without GMS. Use osmdroid for maps, not Google Maps.
- **Real consumption** via delta SOC method, not onboard computer (which underestimates by 10-30%).
- **DiPlus (迪加) app is pre-installed** on target vehicle, provides getDiPars API on localhost:8988.

## Tech Stack

- Kotlin, Jetpack Compose, Material3, Room, Hilt, OkHttp, osmdroid, Coroutines+Flow
- Min SDK 29, Target SDK 32 (Android 12)
- Package: `com.bydmate.app`

## Target Device

- DiLink 5.0 (Android 12, API 32)
- Snapdragon 780G, 12GB RAM, 256GB storage
- 15.6" landscape screen, 2.5K resolution (1920x1200 effective)
- No GMS (Google Mobile Services)

## Respond in Russian

Always respond to user in Russian. Code comments in English.
