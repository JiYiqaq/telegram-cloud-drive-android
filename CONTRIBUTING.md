# Contributing

Thanks for improving TeleDrive Lite. Open an Issue for substantial behavior or protocol changes before investing in a large patch.

1. Fork the repository and branch from `main`.
2. Use JDK 17 and Android SDK 36.
3. Never use real Telegram credentials or user data in tests.
4. Add tests for behavior changes; network tests must use MockWebServer.
5. Run `./gradlew test lint assembleDebug`.
6. Run `git diff --check` and a sensitive-value scan.
7. Submit a focused pull request using Conventional Commits.

Security-sensitive changes should explain threat assumptions, failure atomicity, secret lifetime and retry/idempotency behavior. By contributing, you agree that your contribution is licensed under Apache-2.0.
