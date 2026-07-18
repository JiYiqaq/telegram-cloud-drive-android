# Security policy

TeleDrive Lite `0.1.3-alpha` is pre-release software and has not undergone a formal third-party security audit.

## Reporting a vulnerability

Do not report exploitable security issues in a public Issue. Use [GitHub private vulnerability reporting](https://github.com/JiYiqaq/telegram-cloud-drive-android/security/advisories/new) so the report is visible only to the repository maintainers.

Never send or upload:

- Bot Token or synchronization password
- master key, file data key, keystore or signing key
- real Room database or encrypted pinned index
- private-channel Chat ID or channel name
- user files, logs containing secrets, or Telegram download URLs

Provide a minimal synthetic reproduction, affected commit/version, impact, and suggested mitigation. Use clearly invalid placeholders.

If a Bot Token leaks, revoke/regenerate it immediately with `@BotFather`; deleting the public message is not sufficient. Rotate any other exposed credential. Do not reuse the leaked Token.

The maintainer will review valid private reports, but no response-time SLA is promised for this volunteer project.
