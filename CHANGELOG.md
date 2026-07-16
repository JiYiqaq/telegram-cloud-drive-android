# Changelog

All notable changes follow semantic versioning. This project is pre-release software.

## [Unreleased]

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
