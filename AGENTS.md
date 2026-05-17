# Agent Guide

This repository contains an Android download manager library (`:ketch`) and a sample app (`:app`). Treat `:ketch` as the product surface and `:app` as an integration/demo consumer.

## Core Rules

- Keep SAF, MediaStore picker UI, runtime permission UI, and destination-selection flows in the consuming app. The library receives a ready writable destination.
- Preserve the existing public API when possible. Add trailing default parameters or new overloads instead of breaking callers.
- Downloads must use temporary `.bt` files until success. The final requested extension must appear only after the download is complete.
- Cancel and clear paths must remove both the final file and the `.bt` temp file.
- Queue scheduling must respect `DownloadConfig.maxConcurrentDownloads`, then `DownloadPriority`, then `timeQueued`.
- WorkManager constraints and retry policy should come from each download request.
- Avoid `GlobalScope`. Worker cleanup that must survive cancellation should use `NonCancellable`.
- Do not add UI dependencies to `:ketch`. Keep `appcompat`, `material`, and `constraintlayout` in `:app` unless library code truly needs them.
- Notification code must check `POST_NOTIFICATIONS`/notification-enabled state before posting. The consuming app owns requesting permission.
- Notification actions rebuild `Ketch` through the singleton if the process was recreated; keep README/docs explicit that apps should initialize Ketch in `Application.onCreate()`.
- `HEAD`/ETag checks are best effort. Never fail the main download only because a CDN rejects or times out on `HEAD`.
- Do not require `Content-Length`; chunked and signed CDN responses must stream successfully with unknown total length.
- Keep default network headers CDN-friendly, but allow caller headers to override them.

## Important Files

- Public API: `ketch/src/main/java/com/ketch/Ketch.kt`
- Public models: `ketch/src/main/java/com/ketch/*Config.kt`, `DownloadPriority.kt`, `DownloadConstraints.kt`, `RetryPolicy.kt`
- Scheduler: `ketch/src/main/java/com/ketch/internal/download/DownloadManager.kt`
- Worker: `ketch/src/main/java/com/ketch/internal/worker/DownloadWorker.kt`
- Stream writer: `ketch/src/main/java/com/ketch/internal/download/DownloadTask.kt`
- Database: `ketch/src/main/java/com/ketch/internal/database/*`
- File behavior: `ketch/src/main/java/com/ketch/internal/utils/FileUtil.kt`
- Full docs: `docs/KETCH_LIBRARY_DOCUMENTATION.md`

## Verification

Run this before handing off changes:

```bash
./gradlew :ketch:assembleDebug :ketch:testDebugUnitTest :app:assembleDebug --warning-mode all
```

Expected result: `BUILD SUCCESSFUL` with no project deprecation warnings.

## Release

- Release version is stored in `gradle.properties` as `VERSION_NAME`.
- JitPack coordinates for this repository are `com.github.sherafatpour:uturn-android-ketch:<tag>`.
- `:ketch` owns the Maven publication named `release`.
- `jitpack.yml` runs `./gradlew :ketch:publishReleasePublicationToMavenLocal -x test`.
- Before tagging a release, verify:

```bash
./gradlew :ketch:assembleRelease :ketch:publishReleasePublicationToMavenLocal
./gradlew :ketch:compileDebugKotlin :app:assembleDebug
```

## Documentation Expectations

When adding or changing public behavior:

- Update `README.md`.
- Update `docs/KETCH_LIBRARY_DOCUMENTATION.md`.
- Update this file if the change affects architectural rules for future agents.

## Current Design Boundaries

- The library is path-based today. If URI-based writing is added later, it should be a separate destination abstraction, not a SAF picker inside `:ketch`.
- `Room` currently uses destructive migration. Production-safe schema migrations are a future hardening task.
- Retry is delegated to WorkManager via `Result.retry()` and WorkManager backoff.
- `QUEUED` can mean internal BTDownloader queue or WorkManager waiting for constraints/backoff.
