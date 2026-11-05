# Fixed Sugar

Fixed Sugar is a personal, offline Android diary for tracking blood sugar, food and drink, daily notes, and weight. The interface is available in English and Persian, with Solar Hijri dates in Persian. Fixed Sugar starts in Persian when the system language is Persian and starts in English otherwise. The language button in Settings switches to the language shown on it and remembers the choice.

## Build and run

Use JDK 17 or later and install Android SDK Platform 36 and Build Tools 35.0.0. Set `ANDROID_HOME` to the SDK directory or open the project in Android Studio.

```sh
./gradlew assembleDebug assembleRelease lintDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. The shrunk release APK is at `app/build/outputs/apk/release/app-release-unsigned.apk`. Sign it with your release key for distribution. The app supports Android 7.0 (API 24) and targets Android 16 (API 36).

## Check on a device or emulator

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.fixedsugar.test/app.fixedsugar.DiaryChecks
```

The checks cover Nowruz and leap Esfand, mixed digits, cross-midnight glucose linking, reminder state, snapshot rotation and write failure, and transfer validation. They use an isolated test diary.

## Backups and reminders

Settings shows the available local snapshots and lets you restore one after confirming replacement. Export a JSON file to retain a copy outside the app. Exports contain readable health data. Import requires an empty installation.

Blood sugar reminders arrive through Android notifications. Android notification permissions, exact-alarm access, and idle-mode limits affect delivery timing. The diary remains usable with those permissions off.

## v1 verification

The shrunk, unsigned APK measured 78,372 bytes. The merged release manifest reports minimum API 24 and target API 36. Build, lint, and the diary checks passed on Android 7.0 and Android 16 emulators. Core screens were exercised on a 1 GB Android 7 emulator with a test-signed release APK.

The Android 16 emulator checks covered airplane-mode logging, notification routing and cancellation, reboot recovery, and manual file transfer. Notification delivery used shortened test alarms. Physical-device timing and OEM battery behavior were outside this test run.
