# Changelog

## 1.1.0 - 2026-05-18

### Added

- Added public `BTDownloader.schedule(...)` API for WorkManager-backed scheduled downloads.
- Added `scheduledAtEpochMs` to persisted download state and public `DownloadModel`.
- Added Jetpack Compose sample app with an MVI-style ViewModel.
- Added sample dialog for custom downloads with URL, optional file name, priority, network constraint, charging constraint, delay scheduling, and date/time scheduling.
- Added sample support for deriving the file name from the URL when the optional file name is empty.

### Changed

- Updated the sample app to launch under `com.khush.sample` while keeping the source package `com.sherafatpour.bluetile.sample`.
- Registered `MainActivity` with a fully-qualified class name in the sample manifest.
- Bumped Room database schema version to `5` for the scheduled download field.
- Updated documentation and JitPack coordinates to `1.1.0`.

### Verified

- `./gradlew :app:assembleDebug :ketch:assembleDebug --warning-mode all`
- `./gradlew :app:installDebug`
- `adb -s RFCR60SP4DF shell am start -W -n com.khush.sample/com.sherafatpour.bluetile.sample.MainActivity`

## 1.0.1 - 2026-05-18

### Added

- Added queue preemption behavior for manual `startNow(id)` so a queued item can start immediately by pausing a lower-priority active download when all slots are full.

### Changed

- Updated sample and documentation references from older naming to BTDownloader.

## 1.0.0 - 2026-05-18

### Added

- Initial JitPack-ready BTDownloader release.
- WorkManager-backed background downloads.
- Room-backed persistence and Flow observation.
- Pause, resume, retry, cancel, clear, queue priority, constraints, retry policy, checksum verification, speed limit, notifications, and temporary `.bt` file behavior.
