# Hermes Agent Android App

Native Android WebView client for [Hermes Web UI](https://github.com/EKKOLearnAI/hermes-web-ui).

## Features

- **First-launch URL prompt** — configure any Hermes server (LAN or public) on first open
- **Runtime URL switching** — change server anytime in Settings, with auto cache/cookie purge
- **Mobile / Desktop UA toggle** — switch User-Agent on the fly, persists across sessions
- **Pull-to-refresh** — swipe down to reload
- **File upload** — chat attachments work natively
- **TTS audio autoplay** — voice responses play without tap
- **Session isolation** — different servers keep their sessions separate
- **Dark mode** — follows system theme

## Install

Download the latest APK from [Releases](https://github.com/1williamaoayers/hermes-web-ui/releases).

```
1. Allow "Install from unknown sources" in Android settings
2. Open the APK and install
3. Launch Hermes, enter your server URL (e.g. http://192.168.0.134:8648)
```

## Build Locally

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Tech Stack

- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3
- minSdk 24 (Android 7.0+), targetSdk 35
- AndroidX WebKit for modern WebView APIs
- Material 3 DayNight theme

## License

MIT
