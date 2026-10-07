# Noise Machine

A minimal native Android noise machine app — **Kotlin + Jetpack Compose**, no
cross-platform frameworks.

## Features

- Four toggleable noise generators: **white, pink, brown, green** (green is a
  deep low rumble), synthesized on-device with `AudioTrack` — no audio files.
- 2×2 grid of circular buttons with per-noise animated wave visualizations.
- Master volume slider.
- **Sleep timer** (30m / 90m / 6h / 8h) with a gentle 30-second fade-out and a
  live countdown in the status line and notification.
- Background playback via a foreground service with a persistent notification
  (tap to return, Stop action to silence).

## Build

Requires JDK 17 and the Android SDK (compileSdk 34).

```sh
./gradlew assembleDebug
# or: gradle assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
Signed debug builds install directly; release APKs are attached to
[GitHub Releases](../../releases) for install/update via Obtainium.

## Project layout

```
app/src/main/java/com/noisemachine/app/
├── MainActivity.kt     # Compose UI: buttons, wave canvas, volume, sleep timer
├── NoiseService.kt     # Foreground service: playback, timer, notification
├── NoiseGenerator.kt   # PCM synthesis for white/pink/brown/green noise
└── NoiseType.kt        # Noise type definitions (colors, wave params)
```
