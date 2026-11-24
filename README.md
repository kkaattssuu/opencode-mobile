<img src="docs/icon.png" alt="OpenCode Mobile icon" width="96" height="96">

# OpenCode Mobile

A small Android front end for **OpenCode 2 running on the phone in Termux**.
No laptop, root, remote pairing, or tunnel required. Model-provider requests
still need internet unless you configure a local provider.

## Install

1. Download **OpenCode-Mobile.apk** from [Releases](https://github.com/kkaattssuu/opencode-mobile/releases/latest) and install it.
2. Install [Termux from F-Droid](https://f-droid.org/packages/com.termux/)
   or its official GitHub releases, and open it once.
3. In this app, tap **Allow Termux commands** and grant Android's
   Termux permission. Tap **Check Termux connection**. If needed, copy the
   displayed setup command and paste it in Termux, then check again.
4. Tap **Install OpenCode**. Termux shows installation progress and prompts.
   Return to the app when finished. Existing working installations are detected
   automatically; they are not reinstalled or upgraded on startup.
5. Tap **Launch OpenCode**. It signs in to the phone's server automatically.
    Set up your AI provider in OpenCode's own interface.

On a fresh phone the one-time Termux permission and pasted bridge command are
required; the launcher cannot silently grant itself access to another app.
**Copy & open Termux** copies the command and takes you to Termux to paste it.
Installation requires internet and enough free space for the roughly 200 MB
runtime plus its dependencies. No root or Android settings changes are needed.

Requires **Android 10+**, an **ARM64 phone**, and **aarch64 Termux**, matching
the community runtime's minimum API 29. Keep Android System
WebView updated. Work profiles, Termux forks and nonstandard package names
are not supported.

Projects start under `~/projects` in Termux. For shared phone storage, run
`termux-setup-storage` in Termux and grant its permission. Prefer Termux-private
projects for Git: shared Android storage has Unix filesystem limitations.

The launcher uses a Matrix-inspired black/green theme, large touch targets,
monospace headings, and matching local chat colors. Error/diff colors stay
distinct; no flashing or animated code rain. The setup smiley blinks gently
every four seconds, pauses when hidden/backgrounded, and respects disabled
system animations. The launcher icon stays static. Back returns to setup.
Reopening the app reconnects or starts the server. Android foreground-service
startup is used for Termux, only after the launcher becomes visible.
The top bar has a **power** icon. Long-press it for its label.
To stop it, tap the **power** icon and confirm. This interrupts active
AI tasks, shuts down this server's persistent terminals, verifies the server
process belongs to this app, gracefully stops it, and closes the launcher.
Chats/files are kept and unrelated Termux sessions are untouched. Automatic
startup stays off until you tap **Launch OpenCode** again.
There is no separate launcher foreground service: the server
belongs to Termux, whose own service keeps its commands running. Android/OEM
battery limits can still stop it; background survival is not guaranteed.

## Security and updates

- **Install OpenCode** adds and trusts the **third-party**
  [bd-loser/opencode-bionic](https://github.com/bd-loser/opencode-bionic)
  signed package repository. The installer script is pinned to commit
  `9f0ff5fccd6a1f8ff41213ffdf58d96d4cf2cfa5`; packages are not pinned.
  This is not an official OpenCode Android release.
- Update from Termux with `pkg upgrade opencode`, then exit Termux and reopen
  this app. The app does not silently upgrade tools.
- The server binds only to `127.0.0.1:4096`, uses a random app-private password,
  and issues single-use local pairing codes to establish a WebView cookie.
  It does not expose an internet tunnel. Other phone apps can reach loopback,
  so authentication must remain enabled.
- The WebView cannot access `file:`/`content:` URLs. External HTTP(S) links,
  including provider sign-in pages, open in a browser. Sign-in methods relying
  on a desktop-specific callback may need a provider key instead.
- Disabling the Termux command permission revokes the launcher's access.
  Enabling `allow-external-apps` lets other apps with that permission request
  Termux commands too; grant it only to trusted apps.
- Clearing launcher data generates a new password. Stop its old Termux server
  before reconnecting. Uninstalling the launcher does not remove projects,
  credentials, the repository or OpenCode from Termux.

## Build and checks

Use Android SDK 35 and **JDK 21 with javac**, not just a JRE.
Set `ANDROID_HOME` or `sdk.dir` in `local.properties`.

```sh
./gradlew assembleDebug lintDebug
python3 -m unittest discover -s tests -p 'test_*.py'
mkdir -p build/checks
javac -d build/checks app/src/main/java/app/opencodemobile/ServerConnection.java tests/ServerConnectionCheck.java
java -ea -cp build/checks app.opencodemobile.ServerConnectionCheck
```

For a signed release, supply `OPENCODE_STORE_FILE`, `OPENCODE_STORE_PASSWORD`,
`OPENCODE_KEY_ALIAS`, and `OPENCODE_KEY_PASSWORD` through your private environment,
then run `./gradlew assembleRelease lintRelease`. Missing signing credentials
cause release signing to fail; they are never committed. Keep a secure private
backup of the release key: losing it can prevent future app updates.

Published release APKs have debugging disabled and are signed with a dedicated
release key. `assembleDebug` remains available for local development.
Host checks cover the HTTP/auth transport, setup/start scripts and theme contrast.
Fresh-install tests use an empty mocked Termux prefix with no host tool fallback:
dependencies absent, successful install, offline failure/retry, and preservation
of existing project files. They do not download or execute third-party packages
and are not a substitute for a fresh ARM64 device installation test.
The optional `PhoneCheck` Android test companion is app-scoped and never changes
device settings. Device security policies may block installing it. Full
fresh-device installation, provider sign-in, paid AI prompts and long
background/idle survival are not covered by the host tests. Chat theme
overrides depend on OpenCode's current CSS tokens.

## Distribution

The publishing build uses the neutral app ID `app.opencodemobile`. It installs
separately from earlier development builds and does not copy their private data.
If a development build already runs a server on port 4096, stop it with that
build's power button before launching this one. Do not clear data or uninstall
anything to switch builds.

Earlier 1.0.6 downloads were debug-signed. Android may reject an in-place update
from those builds to the differently signed release. Do not uninstall or clear
data to work around a signature mismatch; keep the earlier build and arrange a
data-preserving migration before switching. This does not affect new installs.
Future release-signed updates must use the same release key.
See [PRIVACY.md](PRIVACY.md) for what is and is not included.
