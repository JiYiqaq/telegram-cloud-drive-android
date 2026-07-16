# Encrypted Download Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add resumable Android downloads that stream Telegram chunks, authenticate and decrypt each chunk, write through SAF, and expose safe task progress, cancellation, retry, and integrity failures.

**Architecture:** A pure `DownloadCoordinator` validates immutable Room metadata, unwraps the per-file key, downloads one bounded encrypted chunk at a time, verifies AES-GCM associated data, and writes plaintext to an abstract destination. Room owns durable task state and SAF destination URIs; WorkManager owns network constraints, foreground execution, retry, and cancellation. The destination is truncated before every attempt because authenticated chunk boundaries make restart-from-zero safer than appending to an unknown external document.

**Tech Stack:** Kotlin, coroutines, Room, WorkManager, Android Storage Access Framework, Telegram Bot API client, AES-256-GCM, SHA-256, JUnit.

---

### Task 1: Pure authenticated download coordinator

**Files:**
- Create: `app/src/test/java/com/teledrive/lite/download/DownloadCoordinatorTest.kt`
- Create: `app/src/main/java/com/teledrive/lite/download/DownloadCoordinator.kt`

1. Write failing tests for ordered chunks, bounded encrypted sizes, associated-data authentication failure, remote-size mismatch, final SHA mismatch, and no success commit before destination completion.
2. Run `:app:testDebugUnitTest --tests 'com.teledrive.lite.download.DownloadCoordinatorTest'` and confirm missing download types fail compilation.
3. Implement the coordinator with one encrypted chunk in memory at a time, key erasure, per-chunk GCM verification, incremental SHA-256, and explicit failure types.
4. Run the same test and require all cases to pass.

### Task 2: Durable Room queue and SAF destination

**Files:**
- Modify: `app/src/main/java/com/teledrive/lite/database/Daos.kt`
- Create: `app/src/main/java/com/teledrive/lite/download/RoomDownloadStore.kt`
- Create: `app/src/main/java/com/teledrive/lite/download/ContentResolverDownloadDestination.kt`
- Create: `app/src/androidTest/java/com/teledrive/lite/download/RoomDownloadStoreTest.kt`

1. Write Android tests proving only cloud-indexed AVAILABLE/CORRUPTED files can queue, the SAF URI is persisted, progress is monotonic, success restores AVAILABLE, and integrity failure records CORRUPTED.
2. Implement queue/store transactions using existing `source_uri` as a generic persisted transfer URI, plus destination truncation/deletion behavior through `ContentResolver`.
3. Compile Android tests and run JVM regressions.

### Task 3: Telegram remote, Worker, scheduler, and explicit cancellation

**Files:**
- Create: `app/src/main/java/com/teledrive/lite/download/TelegramDownloadRemote.kt`
- Create: `app/src/main/java/com/teledrive/lite/download/DownloadWorker.kt`
- Create: `app/src/main/java/com/teledrive/lite/download/DownloadScheduler.kt`
- Create: `app/src/main/java/com/teledrive/lite/download/DownloadCancelReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/teledrive/lite/app/AppContainer.kt`

1. Test remote metadata/size validation and coordinator retry boundaries with fakes.
2. Implement `getFile` followed by bounded streaming download, foreground data-sync notifications, network constraints, automatic `retry_after` waiting, and DB-first explicit cancellation.
3. Treat GCM/SHA failures as non-retryable and delete or truncate incomplete output; allow safe network retries from byte zero.

### Task 4: SAF UI and verification

**Files:**
- Modify: `app/src/main/java/com/teledrive/lite/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/teledrive/lite/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

1. Add a CreateDocument launcher for AVAILABLE/CORRUPTED files and route its URI to the scheduler.
2. Display download progress, size, speed, failure reason, cancel, and safe retry in the shared transfer queue.
3. Run `clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin`, inspect reports, scan secrets, request code review, fix findings, and commit.
