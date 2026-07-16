# Home File Operations UX Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development to implement this plan task-by-task.

**Goal:** Make upload, file/folder operations, batch selection, deletion feedback, and deleted-item visibility immediately understandable on the native Android home screen.

**Architecture:** Keep encryption, WorkManager, and safe-deletion protocols unchanged. Add small pure presentation/filtering helpers with unit tests, use them from `FileRepository`, `HomeViewModel`, and Compose, then reshape `HomeScreen` around explicit primary actions and progressive disclosure.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Kotlin Flow, Room, WorkManager, JUnit 4, Gradle/Android Lint.

---

### Task 1: Hide files while safe deletion is active

**Files:**
- Create: `app/src/main/java/com/teledrive/lite/repository/DirectoryEntryVisibility.kt`
- Create: `app/src/test/java/com/teledrive/lite/repository/DirectoryEntryVisibilityTest.kt`
- Modify: `app/src/main/java/com/teledrive/lite/repository/FileRepository.kt`

**Step 1: Write the failing test**

Test that available and partially deleted files remain visible while `FileStatus.DELETING` is removed. Also verify folders remain visible.

**Step 2: Run test to verify it fails**

Run: `gradle.bat --no-daemon :app:testDebugUnitTest --tests com.teledrive.lite.repository.DirectoryEntryVisibilityTest`

Expected: FAIL because `DirectoryEntryVisibility` does not exist.

**Step 3: Write minimal implementation**

Add `DirectoryEntryVisibility.filter(entries)` and apply it before sorting directory and search results.

**Step 4: Run test to verify it passes**

Run the same targeted Gradle test. Expected: PASS.

### Task 2: Provide accurate Chinese state and deletion feedback

**Files:**
- Create: `app/src/main/java/com/teledrive/lite/ui/home/HomePresentation.kt`
- Create: `app/src/test/java/com/teledrive/lite/ui/home/HomePresentationTest.kt`
- Modify: `app/src/main/java/com/teledrive/lite/ui/home/HomeViewModel.kt`

**Step 1: Write the failing tests**

Test every `FileStatus`, `TransferStatus`, and `TransferType` mapping. Test messages for one successfully queued file, multiple files, folders, mixed entries, partial enqueue failure, and total failure.

**Step 2: Run tests to verify RED**

Run: `gradle.bat --no-daemon :app:testDebugUnitTest --tests com.teledrive.lite.ui.home.HomePresentationTest`

Expected: FAIL because the presentation helper does not exist.

**Step 3: Implement and wire the helper**

Count successfully enqueued files and folders separately in `HomeViewModel.deleteEntries`, then return precise feedback without claiming folder completion early.

**Step 4: Run targeted tests to verify GREEN**

Expected: all new presentation tests PASS.

### Task 3: Reshape the Compose home screen

**Files:**
- Modify: `app/src/main/java/com/teledrive/lite/ui/home/HomeScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Add explicit primary actions**

Replace the symbol-only FAB with an extended “上传文件” action. Add a compact action card explaining local encryption and containing “上传文件” and “新建文件夹”. Add an upload call-to-action to the empty state.

**Step 2: Make batch mode intentional**

Add an explicit “批量管理/完成” toggle. Hide all checkboxes outside batch mode, clear selection on folder/search changes, and make card taps toggle selection while batch mode is active.

**Step 3: Reduce card action clutter**

Give files a “下载” primary button and folders an “打开” primary button. Move details, rename, move, and delete into a “更多” dropdown. Use the error color for destructive actions.

**Step 4: Clarify transfer cards**

Display Chinese transfer type/status labels and keep progress, bytes, speed, retry, cancel, and cleanup actions visible only when applicable.

**Step 5: Compile UI**

Run: `gradle.bat --no-daemon :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL.

### Task 4: Document, version, verify, and release

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `app/build.gradle.kts`

**Step 1: Update documentation and version**

Document the new home operations and deletion visibility. Set `versionCode = 3`, `versionName = "0.1.2-alpha"`, and add the dated changelog entry.

**Step 2: Run complete local verification**

Run: `gradle.bat --no-daemon test lint assembleDebug :app:compileDebugAndroidTestKotlin`

Expected: both Debug and Release tests have zero failures, Lint has zero errors, and the Debug APK is generated.

Verify with `aapt dump badging` and `apksigner verify --verbose --print-certs`.

**Step 3: Review and integrate**

Inspect the full diff, run `git diff --check`, merge the feature branch into `main`, and confirm a clean worktree.

**Step 4: Push and publish**

Push `main`, wait for the matching GitHub Actions run to pass, download its APK artifact, verify version/signature/hash, create `v0.1.2-alpha` as a prerelease, attach the APK and `SHA256SUMS.txt`, then re-download the public asset and verify it again.
