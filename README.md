# The Launch

A minimal, fast Android home-screen launcher focused on a clean, distraction-free app grid.

## Goal

Most launchers are cluttered with widgets, feeds, and animations. The Launch aims to be the
opposite: a lightweight replacement home screen that shows your apps, lets you search and favorite
them, and gets out of the way otherwise. It's built as a personal-use launcher, prioritizing
simplicity and speed over customization depth.

## Features

- App grid with search-as-you-type filtering
- Dock of up to 5 favorite apps (long-press any app to add/remove)
- Work profile app support, with a badge to distinguish work apps
- Optional clock and wallpaper visibility toggles (Settings)
- Manual refresh button to force-reload the installed app list
- Automatic refresh on app install/uninstall/update via broadcast receiver

## Tech

- Kotlin, single-module Android app (`app/`)
- `LauncherApps` + `UserManager` system services for enumerating installed apps across profiles
- MVVM-ish structure: `LauncherViewModel` (state via `StateFlow`) + `MainActivity` (view binding via `findViewById`)
- No external UI framework — plain Views, `RecyclerView` with a `GridLayoutManager`

## Requirements

- Android 7.0 (API 24) or higher
- Must be set as the default home app (Settings > Apps > Default apps > Home app)

## Building

```
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`
