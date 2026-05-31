# Drakmod Player (Drake Mod Player)

A modern Android player for tracker music files — MOD, XM, IT, S3M, and dozens more. Built with Jetpack Compose, Media3, and Oboe for low-latency native audio playback.

A spiritual successor to [cmatsuoka/xmp-android](https://github.com/cmatsuoka/xmp-android), rebuilt with a modern Android look.

### Build Status (CI builds)
[![Build Signed Release APK](https://github.com/LossyDragon/DragonModPlayer/actions/workflows/release-build.yml/badge.svg?branch=master)](https://github.com/LossyDragon/DragonModPlayer/actions/workflows/release-build.yml)

---
## Features

*UI and Features are still in a Work in Progress, but the app is in a usable state.*

### Playback
- **Supports 90+ tracker formats** via [libxmp](https://github.com/libxmp/libxmp) (MOD, XM, IT, S3M, MTM, STM, OKT, FAR, and more)
- **Low-latency audio** via Google's [Oboe](https://github.com/google/oboe) library
- **Gapless track transitions**
- **Background playback** with Media3 foreground service
- **Configurable audio settings**: sample rate, buffer size, amplification, stereo mixing, pan separation, interpolation, lowpass filter, and per-format player flags
- **Repeat modes and Shuffle**
- **Seek, queue navigation, and sub-song playback** for modules with multiple sequences
- **Queue persistence** (WIP) — your playback state survives app restarts, with optional auto-resume

### Interface
- **Material 3 Expressive** UI with dynamic theming
- **Custom seed color picker** — recolor the entire app on the fly. AMOLED (dark mode) available too
- **Playback view** - (WIP) Module pattern view and channel visualization
- **Module info, instrument list, and song messages** viewable per track

### Integration
- **Android Auto support** (WIP, usable) — browse and play from your car
- **Media session integration** — lock screen, watch, and notification controls
- **System playback resumption** (WIP, not working)
- **Voice search** (WIP, not started)

### Under the Hood
- Made in **Kotlin** with **Jetpack Compose**
- **Koin** for dependency injection
- **DataStore** for preferences
- **Room** for playlists
- **Navigation 3** for screen routing
- **Native C++ JNI bridge** to libxmp

---

## Requirements
- Android 8.0 (API 26) or higher
- Architectures: arm64-v8a, armeabi-v7a, x86, x86_64

---

## Featured Acknowledgements
- [libxmp](https://github.com/libxmp/libxmp) — the heart of this player
- [cmatsuoka/xmp-android](https://github.com/cmatsuoka/xmp-android) — the original inspiration
- [Oboe](https://github.com/google/oboe) — Google's low-latency Android audio library
- [Material 3](https://m3.material.io/) and [MaterialKolor](https://github.com/jordond/MaterialKolor) for theming

---

## License
TBD
