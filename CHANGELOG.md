# Changelog

## 1.1.7 - 2026-05-19

### Fixed

- `resume(id)` now transitions `PAUSED -> QUEUED` immediately in DB before dispatch, so Flow observers stop showing paused state without optimistic UI workarounds.
- Resumed downloads continue from existing partial `.bt` bytes by dispatching queued work without resetting progress counters.
- Active progress persistence now updates on every progress callback (downloaded bytes, speed, status, lastModified), improving Flow emission consistency for `observeDownloads`, `observeDownloadById`, and `observeDownloadByTag`.
- `startNow(id)` semantics were hardened:
  - `PAUSED` delegates to resume behavior (no restart from zero).
  - `QUEUED` / `SCHEDULED` / `DEFAULT` starts immediately.
  - `STARTED` / `PROGRESS` only raises priority.
  - `SUCCESS` / `FAILED` / `CANCELLED` is a no-op.
- Immediate and retrying downloads are no longer represented as `SCHEDULED`; scheduling state is reserved for true future-scheduled items.
- Added `DownloadModel.isScheduledRequest` and persisted `DownloadEntity.isScheduledRequest` so consumers can distinguish scheduled-origin downloads reliably without inferring from timestamps.

### Tests

- Added `DownloadStatePolicyTest` covering startNow semantics, scheduled/immediate normalization, and queue-capacity gating policy.

## 1.1.6 - 2026-05-19

### Fixed

- Added network availability callback dispatch in `DownloadManager` so queued downloads are re-dispatched immediately when internet connectivity returns.
- Improved recovery for app-open scenarios where downloads could remain queued after temporary network loss.

## 1.1.5 - 2026-05-18

### Fixed

- Replaced stale per-download WorkManager work when dispatching a queued download. This prevents due scheduled items from staying in `QUEUED` when an old unfinished unique work with the same download id makes WorkManager ignore the new download request.

## 1.1.4 - 2026-05-18

### Fixed

- Added a dedicated `DownloadQueueDrainWorker` so queued scheduled downloads continue to be dispatched in the background even when all download slots were full at their scheduled time.
- Queue dispatch now reports pending work and schedules a unique queue-drain retry worker whenever items remain queued after a dispatch attempt.
- Updated `DownloadScheduleWorker`, `DownloadWorker`, and app-open queue dispatch paths to enqueue the queue-drain worker whenever pending downloads remain.
- Fixed the sample quick schedule action so it no longer requires unmetered Wi-Fi and charging by default, making scheduled downloads start during normal sample testing.

## 1.1.3 - 2026-05-18

### Fixed

- Fixed scheduled downloads that were due while all concurrent slots were full getting stuck in `QUEUED` when the app UI/process was closed.
- Updated `DownloadScheduleWorker` to keep retrying with lightweight WorkManager backoff until its scheduled item is actually accepted into the real download queue.
- Made queue dispatch serialized with a `Mutex` to avoid races when several scheduled downloads become due at the same time.
- Moved terminal queue continuation in `DownloadWorker` into `NonCancellable` cleanup so pause/cancel/failure can still unblock the next queued item in the background.
- Cancelled the pending schedule trigger when `startNow(id)` is used on a due scheduled item that is already `QUEUED`.

## 1.1.2 - 2026-05-18

### Fixed

- Changed scheduled downloads so they no longer enter the active queue or consume `maxConcurrentDownloads` before their scheduled time.
- Added a lightweight `DownloadScheduleWorker` that wakes at the scheduled time, moves the item to `QUEUED`, and lets the normal queue manager start the real download when a slot is available.
- Moved actual download WorkRequest creation into a shared coordinator so app-open and app-closed queue continuation use the same scheduling path.
- Updated `DownloadWorker` to schedule the next queued item after terminal completion/failure even when no UI observer is alive.

### Documentation

- Documented the standard scheduled-download flow: `SCHEDULED` until due, then `QUEUED`, then real download when concurrency allows.

## 1.1.1 - 2026-05-18

### Fixed

- Fixed notification pause, resume, retry, and cancel actions when the app process is closed by keeping the broadcast alive with `goAsync()` until the download action completes.
- Fixed queue recovery after background pause/cancel so the next queued download can be scheduled even when no UI observer is alive.
- Updated the Compose sample so immediate user-created downloads call `startNow(id)` after enqueue.

### Documentation

- Documented required permissions for consuming apps.
- Documented notification/background behavior when the app UI is closed or the process is recreated.

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
