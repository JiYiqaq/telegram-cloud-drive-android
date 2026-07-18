# Drive Dashboard Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the crowded home page with a drive-style files/transfers dashboard, allow terminal transfer records to be removed, and make deletion automatically recover from a dirty cloud index.

**Architecture:** Keep the existing Room, WorkManager, and safe-deletion protocol. Add small tested domain policies for transfer-history dismissal and delete-start recovery, expose them through repositories and `HomeViewModel`, then rebuild the Compose presentation around local files/transfers tabs plus the existing settings route.

**Tech Stack:** Kotlin 2, Jetpack Compose Material 3, Room, WorkManager, Coroutines/Flow, JUnit 4, Gradle 8.13.

---

### Task 1: Recover delete start after a dirty index

**Files:**
- Create: `app/src/test/java/com/teledrive/lite/deletion/DeletionStartRecoveryTest.kt`
- Create: `app/src/main/java/com/teledrive/lite/deletion/DeletionStartRecovery.kt`
- Modify: `app/src/main/java/com/teledrive/lite/ui/home/HomeViewModel.kt`

**Step 1: Write the failing tests**

Cover:

```kotlin
DeletionStartRecovery.run(enqueue, sync)
```

- `INDEX_CONFIRMATION_REQUIRED` triggers exactly one index sync and one enqueue retry.
- A different `DriveRepositoryFailure` is rethrown without syncing.
- Failure from the second enqueue is rethrown and never loops.

**Step 2: Verify RED**

Run:

```powershell
gradle testDebugUnitTest --tests com.teledrive.lite.deletion.DeletionStartRecoveryTest
```

Expected: compilation failure because `DeletionStartRecovery` does not exist.

**Step 3: Implement the minimal helper**

Catch only `DriveRepositoryException` with `failure == INDEX_CONFIRMATION_REQUIRED`, call the supplied sync function, then invoke enqueue once more. Update `HomeViewModel.deleteEntries` to use the helper for file entries and show a clear synchronization failure message.

**Step 4: Verify GREEN**

Run the focused test again; expected PASS.

**Step 5: Commit**

```bash
git add app/src/main app/src/test
git commit -m "fix: sync index before retrying deletion"
```

### Task 2: Make terminal transfer history dismissible

**Files:**
- Modify: `app/src/test/java/com/teledrive/lite/repository/TransferRulesTest.kt`
- Modify: `app/src/main/java/com/teledrive/lite/repository/TransferRules.kt`
- Modify: `app/src/main/java/com/teledrive/lite/database/Daos.kt`
- Modify: `app/src/main/java/com/teledrive/lite/repository/TransferRepository.kt`
- Modify: `app/src/main/java/com/teledrive/lite/ui/home/HomeViewModel.kt`

**Step 1: Write the failing policy test**

Assert that `TransferHistoryPolicy.canDismiss` returns true only for `SUCCESS`, `FAILED`, and `CANCELED`.

**Step 2: Verify RED**

Run the focused `TransferRulesTest`; expected compilation failure because the policy is missing.

**Step 3: Implement persistence and view-model actions**

- Add `TransferHistoryPolicy`.
- Add DAO queries to delete one terminal row and clear all terminal rows.
- Add `TransferRepository.dismissTerminal(taskId)` and `clearTerminalHistory()`.
- Add `HomeViewModel.dismissTransfer` and `clearTransferHistory`, returning explicit snackbar messages.

Active tasks remain protected even if a stale UI sends a dismissal request.

**Step 4: Verify GREEN**

Run `TransferRulesTest`; expected PASS. Compile `compileDebugAndroidTestKotlin` to verify Room query generation.

**Step 5: Commit**

```bash
git add app/src/main app/src/test
git commit -m "feat: clear completed transfer history"
```

### Task 3: Add tested dashboard presentation rules

**Files:**
- Modify: `app/src/test/java/com/teledrive/lite/ui/home/HomePresentationTest.kt`
- Modify: `app/src/main/java/com/teledrive/lite/ui/home/HomePresentation.kt`

**Step 1: Write failing tests**

Add tests for:

- active and terminal transfer grouping;
- terminal-history count;
- human-readable deletion guidance for `PARTIALLY_DELETED`;
- compact dashboard labels.

**Step 2: Verify RED**

Run focused `HomePresentationTest`; expected failure for missing APIs.

**Step 3: Implement the smallest presentation model**

Add `HomeSection`, terminal/active helpers, and status guidance without Android dependencies.

**Step 4: Verify GREEN**

Run focused test; expected PASS.

**Step 5: Commit**

```bash
git add app/src/main app/src/test
git commit -m "feat: model drive dashboard sections"
```

### Task 4: Rebuild the Compose home experience

**Files:**
- Rewrite: `app/src/main/java/com/teledrive/lite/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/teledrive/lite/ui/home/HomeEntryActions.kt`
- Modify: `app/src/main/java/com/teledrive/lite/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/teledrive/lite/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/teledrive/lite/ui/theme/Type.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Preserve behavior through the tested presentation APIs**

Use `HomePresentation` and existing retry/cancel policies; do not change transfer or deletion semantics in Composables.

**Step 2: Build the files tab**

Create a branded header, rounded search field, breadcrumb/sort toolbar, quick action cards, spacious file rows, selection toolbar, expandable upload FAB, and useful empty state. Keep download, detail, rename, move, and safe-delete dialogs.

**Step 3: Build the transfers tab**

Create active/history sections, progress cards, cancel/retry/cleanup actions, per-row dismissal, and a clear-history button. Empty state explains that finished records can be removed without deleting cloud files.

**Step 4: Add bottom navigation**

Files and transfers switch locally without losing folder state. Settings uses the existing `onOpenSettings` route.

**Step 5: Compile**

Run:

```powershell
gradle compileDebugKotlin compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL.

**Step 6: Commit**

```bash
git add app/src/main
git commit -m "feat: redesign home as drive dashboard"
```

### Task 5: Prepare and verify the release

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `SECURITY.md`
- Modify: `docs/MANUAL_TESTING.md`

**Step 1: Update version and documentation**

Increment to `0.1.3-alpha` / versionCode `4`. Document transfer-history removal, dirty-index delete recovery, the new navigation, and manual tests for upload-then-delete.

**Step 2: Run full verification**

Run:

```powershell
gradle test lint compileDebugAndroidTestKotlin assembleRelease
```

Expected: all tests pass, no lint errors, release APK built.

**Step 3: Sign and verify APK**

Use the persistent v0.1.2 signing certificate, run `zipalign` and `apksigner verify`, and produce `SHA256SUMS.txt`.

**Step 4: Review and integrate**

Inspect `git diff --check`, commit release metadata, merge to `main`, rerun tests on merged main, push, and wait for GitHub Actions.

**Step 5: Publish**

Create the `v0.1.3-alpha` prerelease and upload the signed APK plus checksum file. Release notes must explain that transfer-history deletion does not delete cloud files and retain the v0.1.1 signature warning.
