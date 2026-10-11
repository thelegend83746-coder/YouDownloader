# You Downloader — Native Android App

> **Version:** 1.0.0 | **Platform:** Android (API 26+) | **Language:** Kotlin | **UI:** Jetpack Compose + Material 3 | **Architecture:** MVVM

**You Downloader** is a native Android application built with Jetpack Compose that allows users to save YouTube videos directly to device storage and play them anytime offline with clear, synchronized audio. It operates 100% on-device with zero server backend dependencies.

---

## 🎨 Brand & Design System
- **Theme Palette:** Signature Black & Red scheme
  - Background: Deep Black (`#0A0A0C` / `#0E0E10`)
  - Primary Accent: Crimson Red (`#E50914` / `#FF3B30`)
  - Surface: Elevated Charcoal Dark (`#141418` / `#1E1E24`)
- **Theme Modes:** Full instant switching support for **System Default**, **Dark Mode (Black & Red)**, and **Light Mode**.

---

## 🚀 Key Features & PRD Implementation

### 1. Link Input & Instant Preview (FR-1 & FR-2)
- Accepts standard YouTube URLs: `youtube.com/watch?v=...`, `youtu.be/...`, `youtube.com/shorts/...`, embed links, and mobile links.
- Clipboard paste button for instant one-tap entry.
- Real-time metadata preview:
  - Video thumbnail (via Coil with high-resolution fallback)
  - Video title and channel name
  - Duration badge
  - Available video qualities (1080p, 720p, 480p, 360p, and Audio Only)

### 2. Download Engine with Guaranteed Sound (FR-3)
- Native extraction via YouTube InnerTube Android/Web endpoints.
- Resolves progressive muxed streams (360p itag 18, 720p itag 22) with embedded audio.
- For adaptive video streams (1080p/720p), downloads high-bitrate AAC audio (itag 140) and seamlessly muxes tracks into standard MP4 containers using Android's native `MediaMuxer` API.
- Resumable chunk downloading with HTTP `Range` headers.
- Real-time progress updates (percentage, bytes downloaded, total file size).
- Pause, Resume, and Cancel actions.

### 3. Foreground Service & Background Persistence (FR-3, NFR)
- Android Foreground Service (`DownloadService`) with low-importance persistent notification.
- Notification displays live progress bar, video title, and Pause/Resume/Cancel quick action buttons.
- Partial `WakeLock` ensures downloads complete smoothly even when screen is locked or app is minimized.

### 4. Offline Library & Storage Management (FR-4, NFR)
- Local database powered by **Room** (`videos` table storing metadata, download status, file paths, and playback timestamps).
- Storage space gauge showing free and total device capacity with low storage safety guard.
- Automatic detection if a file was deleted externally from file manager.

### 5. Media3 ExoPlayer Offline Playback (FR-5)
- Full local playback without internet connection.
- Remembers last playback position per video in Room database.
- Custom overlay controls: Play/Pause, 10s Rewind / 10s Fast-Forward, seek slider, elapsed/total duration, and landscape fullscreen toggle.
- Share video intent via Android `FileProvider`.
- Delete video with confirmation dialog (removes file, thumbnail, and database record).

### 6. Preferences & Settings (FR-7, FR-8)
- **Jetpack DataStore** for instant preference persistence:
  - Theme switching (System, Dark, Light)
  - Wi-Fi only download toggle
  - Preferred default quality
  - Storage location inspection
- **About & Legal Section:** App version, open-source license attribution, and YouTube Terms of Service content-use disclaimer.

---

## 📂 Project Architecture & Directory Structure

```
YouDownloader/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/youdownloader/app/
│       │   ├── YouDownloaderApp.kt          # Application lifecycle & Notification channels
│       │   ├── MainActivity.kt              # Root activity, intent listener & theme host
│       │   ├── data/
│       │   │   ├── extractor/
│       │   │   │   └── YouTubeExtractor.kt  # Native YouTube InnerTube & stream resolver
│       │   │   ├── downloader/
│       │   │   │   ├── DownloadEngine.kt    # Resumable stream downloader & MediaMuxer
│       │   │   │   └── DownloadService.kt   # Foreground service with persistent notification
│       │   │   ├── local/
│       │   │   │   ├── AppDatabase.kt       # Room Database & TypeConverters
│       │   │   │   ├── VideoDao.kt          # Room DAO with Kotlin Flow queries
│       │   │   │   └── PreferencesManager.kt# Jetpack DataStore for themes and settings
│       │   │   ├── model/
│       │   │   │   └── VideoModel.kt        # Room entity, DownloadStatus, VideoQuality
│       │   │   └── repository/
│       │   │       └── VideoRepository.kt   # Data coordinator & storage cleanup
│       │   ├── ui/
│       │   │   ├── components/
│       │   │   │   └── CommonComponents.kt  # Bottom sheet, download cards, dialogs
│       │   │   ├── navigation/
│       │   │   │   ├── Screen.kt            # Navigation destinations & bottom bar tabs
│       │   │   │   └── AppNavigation.kt     # Jetpack Navigation host & bottom nav bar
│       │   │   ├── screens/
│       │   │   │   ├── splash/
│       │   │   │   │   └── SplashScreen.kt  # Splash screen with black & red branding
│       │   │   │   ├── home/
│       │   │   │   │   ├── HomeScreen.kt    # Downloads in-progress & offline library
│       │   │   │   │   └── HomeViewModel.kt
│       │   │   │   ├── player/
│       │   │   │   │   ├── PlayerScreen.kt  # Media3 ExoPlayer offline video player
│       │   │   │   │   └── PlayerViewModel.kt
│       │   │   │   └── settings/
│       │   │   │       ├── SettingsScreen.kt# Appearance, Wi-Fi only, licenses, legal
│       │   │   │       └── SettingsViewModel.kt
│       │   │   └── theme/
│       │   │       ├── Color.kt             # Black & Red signature palette
│       │   │       ├── Theme.kt             # Material 3 Dynamic Theme Provider
│       │   │       └── Type.kt              # Typography
│       │   └── util/
│       │       ├── Formatters.kt            # Duration, file size & speed formatters
│       │       └── StorageUtils.kt          # Disk space calculation, FileProvider sharing
│       └── res/
│           ├── drawable/                    # Vector icons (Play, Download, Logo)
│           ├── values/                      # colors.xml, strings.xml, themes.xml
│           └── xml/file_paths.xml           # FileProvider paths for sharing
├── gradle/
│   ├── libs.versions.toml                   # Gradle Version Catalog
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 🛠️ Building & Running

### Requirements:
- Android SDK 35 (compileSdk 35, minSdk 26)
- JDK 17
- Gradle 8.9+

### Build APK via CLI:
```bash
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## ⚖️ Legal & Policy Disclaimer
You Downloader is intended strictly for personal offline viewing of videos and audio that the user has explicit rights or permission to download (e.g. personal uploads, public domain material, or Creative Commons licensed content). Downloading copyright-protected content without permission is prohibited by YouTube's Terms of Service and applicable intellectual property laws.
