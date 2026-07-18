# Termux AI Classic

> Optimized Termux Classic build for **AI CLI workflows on Android**.
> It keeps the normal Termux environment and adds a small compatibility layer for
> phone-first use with Codex, Gemini CLI, Qwen Code, and similar terminal tools.

## ✨ Multi-user Workspaces

The headline feature: run **multiple workspaces** on a single install — like
multiple Linux users sharing one system.

- **One shared native base.** A single `$PREFIX` (`/usr`, packages, repos) is
  shared across all workspaces — full native speed, **no proot**, no emulation.
- **Each workspace is its own `$HOME`.** Separate files, projects, dotfiles,
  shell history, and per-workspace tools/versions (e.g. `nvm`, `pyenv`,
  `pip --user` install into the workspace home — node 18 in one, node 20 in
  another).
- **Manage from the drawer.** A Workspaces button (next to settings) lets you
  **create / switch / delete** workspaces. The active workspace applies to
  newly opened sessions.
- **`default` is your existing home** — nothing changes if you never create a
  workspace.

### How to use

1. Open the drawer → tap the **Workspaces** icon (next to ⚙ settings).
2. **New workspace** → name it (lowercase letters, digits, `-` or `_`).
3. Tap a workspace to make it active (marked `✓`), then open a **new session**.
4. Verify: `echo $HOME` points to the workspace; `echo $PREFIX` is unchanged
   (shared, native).

> Workspaces are an **organizational** separation for development convenience
> (same Android UID), not a security sandbox. App config (colors, font,
> `termux.properties`) is global, tied to the default home. Switching applies
> to new sessions; `default` and the active workspace cannot be deleted.

[![release](https://img.shields.io/github/v/release/DioNanos/termux-ai?style=flat-square)](https://github.com/DioNanos/termux-ai/releases/latest)
[![apk](https://img.shields.io/github/downloads/DioNanos/termux-ai/latest/total?style=flat-square&label=APK%20downloads)](https://github.com/DioNanos/termux-ai/releases/latest)
[![Android 7+](https://img.shields.io/badge/Android-7%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#install)
[![target SDK 28](https://img.shields.io/badge/target%20SDK-28-blue?style=flat-square)](#classic-compatibility)
[![license](https://img.shields.io/github/license/DioNanos/termux-ai?style=flat-square)](./LICENSE.md)

## Install

Download the latest APK from:

- [Latest GitHub release](https://github.com/DioNanos/termux-ai/releases/latest)

Choose the APK that matches the device CPU, or use the larger universal APK:

| APK | Typical devices |
|---|---|
| `arm64-v8a` | Most current Android phones and tablets, including Pixel and Galaxy devices |
| `armeabi-v7a` | Older 32-bit ARM devices |
| `x86_64` | 64-bit Intel devices and emulators |
| `x86` | Legacy 32-bit Intel devices and emulators |
| `universal` | One APK containing all four architectures |

Requirements:

- Android 7+ / API 24+
- a device supported by the upstream Termux bootstrap packages
- no conflicting `com.termux` install signed with a different key

Termux AI Classic uses the `com.termux` package name. Android requires app
updates to be signed with the same key as the already installed app. If another
Termux build is installed with a different signature, back up your data first,
then uninstall Termux and its plugins before installing this APK.

## Scope

What this build does:

- **multi-user workspaces**: multiple isolated `$HOME`s over one shared native base (see above)
- keeps classic Termux package and proot compatibility
- improves Android keyboard microphone input in the terminal
- adds a toolbar text input path for quick command submission
- installs a verified `termux-ai` shell command for core Android context
- internalizes the first wave of Termux:API-compatible helpers for AI CLI use
- runs boot scripts directly from the active workspace without requiring the separate Termux:Boot app
- includes `mandoc` in the first bootstrap to avoid manpage database warnings

What this build does not do:

- replace upstream Termux
- target the Play Store compatibility model
- bypass Android scoped storage
- expose sensitive phone automation such as SMS, calls, call logs, or contacts
- carry broad product changes unrelated to AI CLI use from Android

## Classic Compatibility

This is a Classic Termux-compatible build.

- Package name: `com.termux`
- Minimum Android: 7
- Target SDK: 28
- Compile SDK: 36
- Bootstrap variant: `apt-android-7`

Target SDK 28 is intentional. Android blocks execution of binaries extracted in
the app data directory for apps targeting API 29+, while Termux executes package
binaries from:

```text
/data/data/com.termux/files/usr/bin
```

Keeping target SDK 28 preserves classic Termux behavior. New Android versions
may show an "app was built for an older Android version" warning; that is the
tradeoff for full classic Termux package compatibility.

## AI And Android

This release focuses on making the normal terminal more practical for AI CLI
work from Android:

- Android keyboard microphone dictation works through normal IME text input.
- The toolbar text field can send command text quickly.
- The `termux-ai` command exposes a small JSON-first Android context surface.

Stable `termux-ai` commands in this line:

```bash
termux-ai --version
termux-ai doctor
termux-ai api list
termux-ai ping
termux-ai info
termux-ai battery
termux-ai clipboard get
termux-ai clipboard set "hello from Termux AI"
termux-ai tts engines
termux-ai tts speak "hello from Termux AI"
termux-ai toast "done"
termux-ai volume
termux-ai audio info
termux-ai brightness 120
termux-ai torch on
termux-ai torch off
termux-ai camera info
termux-ai notify "Termux AI" "task finished"
termux-ai wifi connection
termux-ai aicore info
termux-ai aicore generate "say hello"
```

Output is compact JSON by default. Commands return exit code `0` only when the
bridge reports success.

## Termux:API Compatibility

Termux AI Classic includes a first internal compatibility wave for common
Termux:API shell commands. These commands are installed inside Termux by the app
and do not require the separate `com.termux.api` application for the supported
surface:

```bash
termux-tts-speak "hello"
termux-tts-engines
termux-toast "done"
termux-volume
termux-audio-info
termux-brightness 120
termux-torch on
termux-torch off
termux-camera-info
termux-notification -t "Termux AI" -c "task finished"
termux-notification-channel ai "AI"
termux-notification-remove 1773
termux-wifi-connectioninfo
termux-battery-status
termux-clipboard-get
termux-clipboard-set "hello"
termux-vibrate
```

The app also installs a `$PREFIX/libexec/termux-api` shim for the supported
Wave 1 methods. Compatibility is intentionally scoped to AI-safe helpers first;
SMS, calls, call logs, contacts, continuous location, USB, NFC, and notification
listener access are not part of this public release.

## Boot Scripts Without A Companion App

Termux AI Classic runs executable files from either of these directories after
Android sends `BOOT_COMPLETED`:

```text
~/.config/termux/boot
~/.termux/boot
```

Scripts are launched in filename order inside the active workspace. Open Termux AI once
after a fresh installation, create a script, and make it executable. For
example:

```sh
mkdir -p ~/.config/termux/boot
printf '%s\n' '#!/data/data/com.termux/files/usr/bin/sh' \
  'date -Ins >> "$HOME/boot-proof.log"' \
  > ~/.config/termux/boot/10-proof
chmod 700 ~/.config/termux/boot/10-proof
```

Android does not deliver boot broadcasts to an app that has been force-stopped
until the app is opened again. Credential-protected Termux files are available
after the user unlocks the device.

## Releases

- Current GitHub release: [v0.118.0-ai.18](https://github.com/DioNanos/termux-ai/releases/tag/v0.118.0-ai.18)
- Current line: `0.118.0-ai.x`
- Upstream base: Termux app classic line
- First public build: `0.118.0-ai.5`

`v0.118.0-ai.18` upgrades Commons IO to 2.14.0 with NIO core-library
desugaring for Android 7 compatibility, retains the dependency hardening from
the previous line, and publishes signed per-architecture APKs alongside a
universal APK.

## Build

```bash
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/android-sdk ./gradlew --no-daemon assembleRelease
```

The build downloads the upstream Termux bootstrap zips and applies a small
`mandoc` overlay before native packaging. The overlay keeps the first bootstrap
cleaner without committing large generated bootstrap archives.

## Limitations

- Target SDK 28 is required for classic Termux binary execution.
- Updating an existing F-Droid Termux install in place is not possible unless
  the APK is signed with the same key.
- Android may show an older-target warning on recent versions.
- Voice input depends on the active Android keyboard and speech service.
- The internal Termux:API compatibility layer covers only the documented Wave 1
  commands in this release.
- Android may require manual permission grants for some device functions, such
  as brightness control.

## Roadmap

- improve voice input compatibility across Android keyboards
- add more AI-friendly terminal affordances without breaking shell behavior
- expand Android bridge commands only after bootstrap-safe installation and
  device smoke tests are validated
- keep Classic releases stable for current AI CLI use
- study a separate modern-runtime line for target SDK 35+ without pretending it
  is classic Termux

## License And Thanks

Termux AI Classic preserves Termux licensing and notices. See the repository
license files for GPL, Apache, and MIT components.

Thanks to the Termux project and community for the foundation this work builds
on.

DioNanos-specific additions are licensed under Apache-2.0 where compatible with the upstream Termux licensing. See [LICENSE-DIONANOS.md](LICENSE-DIONANOS.md).

---

## Contact

Maintained by [DioNanos](https://github.com/DioNanos).

- General / dev: **dev@mmmbuto.com**
- Security disclosures: **security@mmmbuto.com**
- Project hub: <https://mmmbuto.com>
