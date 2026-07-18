# Changelog

All notable changes follow semantic versioning. This project is pre-release software.

## [0.1.3-alpha] - 2026-07-18

### Added

- Added a drive-style files/transfers/settings navigation structure
- Added per-record dismissal and one-tap cleanup for safe terminal transfer history
- Added actionable cleanup guidance for failed or canceled uploads with remote orphan chunks

### Changed

- Rebuilt the files page with a branded dashboard header, rounded search, quick upload/folder actions, compact file rows, and expandable add menu
- Split active transfers and history into a dedicated transfer center
- Refreshed the application palette with cloud blue and teal security accents

### Fixed

- Automatically synchronize a dirty pinned index and retry once when deletion is requested immediately after a successful upload
- Keep failed upload records until remote orphan chunks are cleaned so cleanup metadata cannot be lost

## [0.1.2-alpha] - 2026-07-18

### Changed

- Redesigned the home screen around explicit upload, folder, batch, and per-entry actions
- Added clear Chinese file and transfer status labels with a guided empty state
- Increased the default chunk size for new uploads from 18 MiB to 19 MiB while staying below Telegram's encrypted download boundary

### Fixed

- Hide files immediately after safe deletion is accepted and restore terminal failures as visible retryable items
- Return precise deletion success, folder-queue, and partial-failure feedback

## [0.1.1-alpha] - 2026-07-16

### Changed

- Lowered the minimum sync-password length from 12 to 8 characters

## [0.1.0-alpha] - 2026-07-16

### Added

- Native Android project with Compose Material 3
- Telegram Bot API connection and private-channel validation
- AES-256-GCM, PBKDF2-HMAC-SHA256, per-file keys and key wrapping
- Streaming file chunking and SHA-256 integrity checks
- Room cache, migrations and virtual folders
- Reliable encrypted upload and verified download workers
- Encrypted pinned-index atomic update and cross-device recovery
- Safe deletion, partial failure recovery and orphan cleanup
- Chinese setup/tutorial/settings/file-management UI, arbitrary-target batch move/delete, and theme controls
- Unit, MockWebServer and Android Room/migration tests
- GitHub Actions and open-source project documentation

### Known limitations

- Alpha and not formally security audited
- No signed production release
- Real Telegram/SAF/background behavior requires maintainer device testing
- Arbitrary-target multi-select move UI can be expanded beyond the current root shortcut
