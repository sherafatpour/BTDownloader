# BTDownloader Audit

## Architecture

BTDownloader is a pragmatic Android library architecture rather than a strict clean architecture stack:

- Public facade: `BTDownloader` exposes the API and owns singleton construction.
- Scheduling layer: `internal.download.DownloadManager` persists requests in Room and schedules `DownloadWorker` through WorkManager.
- Execution layer: `DownloadWorker` performs background work and delegates byte streaming to `DownloadTask`.
- Persistence layer: Room stores durable download state in `DownloadEntity`.
- Network layer: Retrofit/OkHttp stream file responses and perform best-effort `HEAD` checks.
- Notification layer: foreground progress notifications and terminal-state notifications are handled internally without permission UI.
- Consumer app boundary: SAF, MediaStore, runtime permission UI, and destination picking stay in the app.

## Existing Capabilities

- Queue scheduling by `maxConcurrentDownloads`, then priority, then `timeQueued`.
- Room persistence and Flow observation.
- WorkManager background execution with constraints.
- Pause, resume, retry, cancel, clear by id/tag/all.
- Temporary `.bt` file while downloading and final extension only after success.
- Resume via HTTP `Range` when a temp file exists.
- Best-effort ETag validation.
- Retry through WorkManager backoff.
- Foreground progress notifications with pause/cancel actions.
- Success, failed, paused, and cancelled notifications.
- Caller headers and metadata.
- Custom logger.

## Added In This Pass

- Dynamic queued priority update: `BTDownloader.setPriority(id, priority)`.
- Stale incomplete cleanup: `BTDownloader.cleanupIncompleteDownloads()`.
- Global speed throttling: `DownloadConfig.speedLimitBytesPerSecond`.
- Free-space preflight buffer: `DownloadConfig.freeSpaceBufferBytes`.
- Checksum verification: `DownloadChecksum` with `MD5` and `SHA256`.
- File collision handling: `autoRenameIfExists`.
- Stable error category: `DownloadError` on `DownloadModel.errorType`.
- Retrofit/OkHttp service reuse per timeout configuration.
- Sample app controls for pause all, resume all, clear completed, batch enqueue, and runtime priority selection.

## Checklist

| Area | Status |
| --- | --- |
| Dynamic priority | Partial: queued/future scheduling supported, active preemption not implemented |
| Concurrent limit | Present |
| Automatic queue | Present |
| Pause/resume single | Present |
| Pause/resume all | Present |
| Retry with exponential backoff | Present |
| Cancel single/all | Present |
| Multi-part chunks | Missing |
| Range resume | Present for single stream |
| Per-chunk resume/error handling | Missing |
| Network constraints | Present through WorkManager |
| Runtime network changes | Present through WorkManager constraints, not custom monitor callbacks |
| Speed throttling | Present as global config |
| Destination path selection | App-owned, library receives writable path |
| Free-space preflight | Present when content length is known |
| Temporary files | Present with `.bt` |
| Stale incomplete cleanup | Present |
| Checksum verification | Present |
| Auto rename collision | Present with `autoRenameIfExists` |
| Progress notification | Present |
| Notification actions | Present |
| Grouped/custom notifications | Missing |
| Room persistence | Present |
| App restart resume | Present if app initializes BTDownloader and WorkManager can run |
| Device reboot resume | Partial: WorkManager persists work, but no explicit boot recovery policy |
| Typed error model | Present as enum category |
| Logging | Present |
| Connection pooling | Improved by service reuse |
| Buffer optimization | Uses `DEFAULT_BUFFER_SIZE`; no adaptive tuning |
| Battery optimization | WorkManager constraints; no custom battery policy |
| HTTPS | Present through OkHttp |
| Certificate pinning | Missing |
| Encryption/secure credentials | Missing; caller-owned |
| Flow API | Present |
| LiveData/RxJava | Missing |
| Documentation/sample | Improved, not exhaustive class-by-class KDoc yet |

## Main Risks

- `Room` still uses destructive migration, so production apps should pin a migration plan before shipping schema-sensitive updates.
- Active priority changes do not preempt an already-running worker.
- Multi-part downloading is intentionally not bolted on yet; it needs separate chunk tables, merge recovery, and server capability detection.
- Free-space checks only work when total length is known.
- Notification grouping/custom layouts are not implemented.
- Android 14+ foreground service behavior depends on host app notification permission and WorkManager foreground execution support.

## Migration Notes

- Database version moved from `3` to `4`.
- New columns: `checksumAlgorithm`, `checksumValue`, `errorType`, `autoRenameIfExists`.
- Current database builder still uses destructive migration, so existing rows may be dropped on upgrade.
- To make migration production-safe, add explicit `Migration(3, 4)` that adds the four columns with defaults.
