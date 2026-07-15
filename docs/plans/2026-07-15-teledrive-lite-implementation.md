# TeleDrive Lite Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Build, test, package, document, and publish TeleDrive Lite 0.1.0-alpha as a native Android encrypted Telegram cloud-drive client.

**Architecture:** Use one Android `app` module with layered Kotlin packages, Room as a local cache, WorkManager for reliable transfers, a hand-written dependency container, and a versioned encrypted pinned index as cloud truth. Use PBKDF2-HMAC-SHA256 plus AES-256-GCM and keep all plaintext file data on-device.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Coroutines/Flow, Room, WorkManager, OkHttp, kotlinx.serialization, Android Keystore, MockWebServer, JUnit, AndroidX Test.

---

### Task 1: Reproducible Android project foundation

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/teledrive/lite/TeleDriveApplication.kt`
- Create: `app/src/main/java/com/teledrive/lite/MainActivity.kt`
- Create: `app/src/main/java/com/teledrive/lite/app/AppContainer.kt`
- Create: `app/src/main/java/com/teledrive/lite/ui/theme/{Color,Theme,Type}.kt`
- Create: `app/src/main/java/com/teledrive/lite/navigation/{Routes,TeleDriveNavHost}.kt`
- Create: `app/src/main/res/values/{strings,themes,colors}.xml` and adaptive icon resources

**Steps:**
1. Add Gradle/version-catalog/configuration files (generated/configuration exception to TDD).
2. Generate the Gradle wrapper and verify the wrapper JAR is tracked.
3. Add a minimal Compose activity, application, theme and navigation host with required Codex file headers.
4. Run `gradlew.bat assembleDebug`; expected result: BUILD SUCCESSFUL.
5. Inspect `git diff --check`, scan placeholders, and commit `chore: initialize Android project`.

### Task 2: Cryptography, hashing, and streaming chunk primitives

**Files:**
- Test: `app/src/test/java/com/teledrive/lite/crypto/CryptoEngineTest.kt`
- Test: `app/src/test/java/com/teledrive/lite/crypto/KeyDerivationTest.kt`
- Test: `app/src/test/java/com/teledrive/lite/transfer/StreamingChunkerTest.kt`
- Test: `app/src/test/java/com/teledrive/lite/util/Sha256Test.kt`
- Create: `app/src/main/java/com/teledrive/lite/crypto/{CryptoEngine,KeyDerivation,KeyWrapping,CryptoModels}.kt`
- Create: `app/src/main/java/com/teledrive/lite/transfer/{StreamingChunker,ChunkModels}.kt`
- Create: `app/src/main/java/com/teledrive/lite/util/{Sha256,SecureErase}.kt`

**Steps:**
1. Write tests for round-trip, wrong key, tampering, unique nonces, PBKDF2 determinism, key wrapping, empty and exact-boundary chunks, recombination and SHA-256.
2. Run the focused tests; expected RED because production classes do not exist.
3. Implement minimal versioned AES-GCM/PBKDF2/chunker APIs; nonces are generated internally and chunks use bounded buffers.
4. Re-run focused and full unit tests; expected GREEN.
5. Refactor names and zero temporary sensitive byte arrays, re-run tests, then commit `feat: implement encrypted file chunking`.

### Task 3: Telegram Bot API client and connection validation

**Files:**
- Test: `app/src/test/java/com/teledrive/lite/telegram/TelegramBotApiClientTest.kt`
- Test: `app/src/test/java/com/teledrive/lite/telegram/TelegramRedactionTest.kt`
- Create: `app/src/main/java/com/teledrive/lite/telegram/{TelegramBotApiClient,TelegramDtos,TelegramModels,TelegramError,TelegramRequestBody,RetryPolicy}.kt`
- Create: `app/src/main/java/com/teledrive/lite/repository/ConnectionRepository.kt`

**Steps:**
1. Add MockWebServer tests for `getMe`, `getChat`, `getChatMember`, `getUpdates`, send/delete/pin/unpin, `getFile`, file download, `ok=false`, HTTP errors, 429 `retry_after`, timeout and sanitization.
2. Run the focused tests; expected RED from missing client types.
3. Implement a base-URL-injectable OkHttp client whose response bodies are always closed and whose public errors never include token or file URL.
4. Limit automatic retry to idempotent requests; uploads surface a retryable error without resending.
5. Run tests and assemble, then commit `feat: add Telegram Bot API client`.

### Task 4: Room cache, repositories, and virtual folders

**Files:**
- Android test: `app/src/androidTest/java/com/teledrive/lite/database/TeleDriveDatabaseTest.kt`
- Test: `app/src/test/java/com/teledrive/lite/repository/FolderTreeValidatorTest.kt`
- Create: `app/src/main/java/com/teledrive/lite/model/{FileStatus,TransferStatus,SortMode,DomainModels}.kt`
- Create: `app/src/main/java/com/teledrive/lite/database/{Entities,Daos,Converters,TeleDriveDatabase,Migrations}.kt`
- Create: `app/src/main/java/com/teledrive/lite/repository/{FileRepository,TransferRepository,FolderTreeValidator}.kt`

**Steps:**
1. Write RED tests for root/multilevel folders, search/sort, DAO flows, transactions, missing target and cycle rejection.
2. Implement seven required entities with foreign keys/indexes, DAO `Flow`s, a no-destructive-migration registry and repository-only UI access.
3. Implement rename/move/batch state transitions and staged delete metadata retention.
4. Run unit tests; run instrumented tests only if an emulator/device is available and record the actual result separately.
5. Assemble and commit `feat: add Room database and virtual folders`.

### Task 5: Keystore configuration and first-run flow

**Files:**
- Test: `app/src/test/java/com/teledrive/lite/settings/ConfigValidatorTest.kt`
- Create: `app/src/main/java/com/teledrive/lite/settings/{KeystoreCipher,SecureConfigStore,SessionKeyStore,ThemePreference}.kt`
- Create: `app/src/main/java/com/teledrive/lite/ui/setup/{SetupViewModel,SetupScreen}.kt`
- Create: `app/src/main/java/com/teledrive/lite/ui/tutorial/TutorialScreen.kt`

**Steps:**
1. Write RED validation tests for token shape, signed channel id, minimum password, mismatch, strength, and redacted diagnostics.
2. Implement Android Keystore AES-GCM storage for token/chat id and an independently wrapped session master key; never persist the password.
3. Implement bot test, channel permission/test-message cleanup, explicit channel detection confirmation and save/initialize states.
4. Enable `FLAG_SECURE` while sensitive screens are visible and map domain errors to simplified Chinese.
5. Run tests/assemble and commit `feat: implement secure app configuration`.

### Task 6: Encrypted pinned index and recovery

**Files:**
- Test: `app/src/test/java/com/teledrive/lite/index/IndexCodecTest.kt`
- Test: `app/src/test/java/com/teledrive/lite/index/IndexAtomicUpdaterTest.kt`
- Create: `app/src/main/java/com/teledrive/lite/index/{CloudIndex,IndexCodec,EncryptedIndexCodec,IndexValidator,IndexMigrator,IndexSynchronizer,IndexRestorer}.kt`
- Create: `app/src/main/java/com/teledrive/lite/repository/IndexRepository.kt`

**Steps:**
1. Write RED tests for serialization, versions, revision, encryption, wrong password, tamper, invalid references, atomic success and every intermediate failure.
2. Implement the binary envelope so KDF parameters can be read before decryption and duplicated parameters must match after decryption.
3. Implement upload -> verify -> pin -> `getChat` confirmation -> stable commit -> old-index cleanup ordering.
4. Implement restore staging and one-transaction Room replacement so failure cannot overwrite local data.
5. Run tests/assemble and commit `feat: add encrypted pinned index recovery`.

### Task 7: Upload pipeline and foreground progress

**Files:**
- Test: `app/src/test/java/com/teledrive/lite/transfer/UploadCoordinatorTest.kt`
- Create: `app/src/main/java/com/teledrive/lite/transfer/{UploadCoordinator,TransferProgress,TransferError}.kt`
- Create: `app/src/main/java/com/teledrive/lite/worker/{UploadWorker,WorkScheduler}.kt`
- Create: `app/src/main/java/com/teledrive/lite/service/TransferNotificationFactory.kt`
- Create: `app/src/main/java/com/teledrive/lite/storage/{ContentUriSource,TempFileManager}.kt`

**Steps:**
1. Write RED coordinator tests for ordered chunks, progress, failure, cancel, orphan records, index-last commit and retry status.
2. Implement streaming source -> encrypted temp chunk -> multipart upload -> persisted chunk loop with a unique serial upload queue.
3. Implement connected constraints, foreground info, progress/speed, cancellation and temp cleanup.
4. Wire `ACTION_OPEN_DOCUMENT` with multiple selection and persistable URI grants.
5. Run tests/assemble and commit `feat: implement upload transfer workers`.

### Task 8: Download, verification, and safe deletion

**Files:**
- Test: `app/src/test/java/com/teledrive/lite/transfer/DownloadCoordinatorTest.kt`
- Test: `app/src/test/java/com/teledrive/lite/repository/SafeDeleteCoordinatorTest.kt`
- Create: `app/src/main/java/com/teledrive/lite/transfer/DownloadCoordinator.kt`
- Create: `app/src/main/java/com/teledrive/lite/worker/DownloadWorker.kt`
- Create: `app/src/main/java/com/teledrive/lite/storage/VerifiedDestinationWriter.kt`
- Create: `app/src/main/java/com/teledrive/lite/repository/SafeDeleteCoordinator.kt`

**Steps:**
1. Write RED tests for ordered download/decrypt, GCM failure, SHA mismatch, cancellation, partial deletion and retry.
2. Implement reconstruction to private temp storage, final SHA verification, then SAF copy with correct MIME type.
3. Implement delete state machine that preserves failed message ids and updates the cloud index only after all target chunks are deleted.
4. Wire `ACTION_CREATE_DOCUMENT`, download notification, retry and cancellation.
5. Run tests/assemble and commit `feat: implement download and integrity verification` plus `feat: implement safe deletion and orphan cleanup`.

### Task 9: Complete Compose file manager and settings

**Files:**
- Create: `app/src/main/java/com/teledrive/lite/ui/home/{HomeViewModel,HomeScreen}.kt`
- Create: `app/src/main/java/com/teledrive/lite/ui/file/{FileDetailScreen,FileActions}.kt`
- Create: `app/src/main/java/com/teledrive/lite/ui/transfer/{TransferViewModel,TransferQueueScreen}.kt`
- Create: `app/src/main/java/com/teledrive/lite/ui/search/{SearchViewModel,SearchScreen}.kt`
- Create: `app/src/main/java/com/teledrive/lite/ui/settings/{SettingsViewModel,SettingsScreen}.kt`
- Create: `app/src/main/java/com/teledrive/lite/ui/restore/{RestoreViewModel,RestoreScreen}.kt`
- Create: `app/src/main/java/com/teledrive/lite/ui/about/AboutScreen.kt`
- Test: `app/src/test/java/com/teledrive/lite/util/{NameValidatorTest,DiagnosticRedactorTest}.kt`

**Steps:**
1. Write RED tests for filename/path validation, sort reducers, diagnostics and settings bounds.
2. Implement breadcrumb navigation, pull sync, empty/list states, create/rename/move/delete confirmations, search/sort and batch selection.
3. Implement transfer filters and controls, settings, restore, tutorial and required Codex/about/security disclosures.
4. Add light/dark/system themes and an original non-branded adaptive vector icon.
5. Run unit tests, lint and assemble; commit `feat: add settings and diagnostics`.

### Task 10: Open-source release engineering and publication

**Files:**
- Create: `README.md`, `LICENSE`, `NOTICE`, `CODEX.md`, `CONTRIBUTING.md`, `SECURITY.md`
- Create: `CODE_OF_CONDUCT.md`, `CHANGELOG.md`, `THIRD_PARTY_NOTICES.md`, `.gitignore`
- Create: `.github/ISSUE_TEMPLATE/{bug_report,feature_request}.yml`
- Create: `.github/pull_request_template.md`, `.github/workflows/android.yml`

**Steps:**
1. Add the unmodified Apache-2.0 text, Chinese-first README, explicit security contact placeholder and third-party notices.
2. Add CI for `test`, `lint`, `assembleDebug`, wrapper validation/cache and debug APK artifact with no secrets.
3. Run `gradlew.bat test`, `gradlew.bat lint`, and `gradlew.bat assembleDebug`; fix every reproducible failure using systematic debugging and regression tests.
4. Run `git status --ignored`, `git diff --check`, tracked-file/history credential scans, wrapper/file checks and copy the verified APK to the workspace `outputs` directory.
5. Commit all coherent changes using Conventional Commits, then clone the local committed repository to a clean temporary directory and repeat all three Gradle commands.
6. Verify the GitHub identity and any existing same-name repository before creation; never overwrite unrelated history or force-push.
7. Create the public repository, set description/topics/default `main`, push, inspect Actions, fix failures, and repeat until green or an external blocker is proven.
8. Record exact test counts, build paths, hashes, Actions state, limitations and manual-device checks in `CODEX.md` and the final report.
