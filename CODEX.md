# Codex implementation disclosure

OpenAI Codex created the initial project architecture and implementation under the direction of the project maintainer. The maintainer supplied the product requirements, security constraints, target platform, technology choices, open-source requirements, and acceptance criteria.

## Implemented with Codex assistance

- Android Gradle/Kotlin/Compose project structure and navigation
- Telegram Bot API client, error handling, retry policy and MockWebServer tests
- Keystore-backed setup state, KDF, AES-256-GCM and key wrapping
- Streaming chunking, SHA-256, upload/download workers and notifications
- Room schema, migrations, repositories and virtual-folder rules
- Encrypted pinned-index protocol and cross-device recovery
- Safe deletion, partial recovery and orphan cleanup
- Settings, diagnostics, about, tutorial and file-management UI
- Tests, CI, documentation and open-source community files

## Main implementation period

Initial implementation: July 15–16, 2026.

## Development stages

The work proceeded through foundation, Telegram/configuration, crypto/chunking, Room/directories, pinned index, upload, download, safe deletion, UI/settings, documentation, verification and GitHub publication.

## Verification record

Commands actually run during development include targeted and full variants of:

```text
gradle --no-daemon testDebugUnitTest
gradle --no-daemon compileDebugAndroidTestKotlin
gradle --no-daemon lintDebug
gradle --no-daemon assembleDebug
```

Exact final counts, APK hash, clean-clone result and GitHub Actions status are recorded in the final task report and may be updated here by the maintainer after release.

## Human checks still required

- Real Bot/administrator setup against the maintainer's own private channel
- API-limit review against current official Telegram Bot API documentation
- API 28 and recent Android device/emulator UI walkthrough
- Background transfer, process-death, notification permission and SAF provider matrix
- Accessibility, translations, screenshots and release signing
- Independent security review and cryptographic implementation audit

## Known limits and risks

This is an alpha implementation and has not received a formal third-party security audit. Telegram traffic metadata remains visible to Telegram. A lost sync password is unrecoverable. Bot API behavior and limits may change. A single private Telegram channel must not be treated as the only backup.

Codex-generated code does not represent OpenAI certification, endorsement, warranty, or security approval. This project has no affiliation with OpenAI or Telegram. The maintainer is responsible for final review, testing, release decisions, incident response, and ongoing maintenance.
