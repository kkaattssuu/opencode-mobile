# Privacy and publication

This repository contains application source, tests using dummy credentials,
and the standard Gradle wrapper. It does **not** contain phone backups, chats,
provider credentials, personal screenshots, device logs, device identifiers,
local SDK paths, private signing keys, or a preconfigured server password.
The app ID is neutral and commits use the GitHub account's no-reply address.

## At runtime

- The launcher generates its server password on the phone. It is not shipped
  in source or APKs. Backups/device transfers are disabled for launcher data.
- The server listens only on the phone's loopback address. Authentication stays
  enabled; no remote tunnel is created.
- This launcher adds no analytics, advertising, or telemetry service.
- Installing OpenCode contacts Termux package servers and the documented
  third-party repository. Model requests follow the provider you select in
  OpenCode. Those services have their own privacy policies.
- Granting the Termux bridge permission allows app-requested commands in the
  Termux environment. Only grant that permission to apps you trust.
- The power button stops this server's tasks and terminals. It does not delete
  projects or chats, and does not stop unrelated Termux sessions.

## Before contributing

Do not commit real credentials, app data, screenshots of private sessions,
device dumps, or signing material. Keep `local.properties`, environment files,
build outputs, and keys ignored. Check staged changes before pushing.
