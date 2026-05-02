# Termux AI Classic

> Optimized Termux Classic build for **AI CLI workflows on Android**.
> It keeps the normal Termux environment and adds a small compatibility layer for
> phone-first use with Codex, Gemini CLI, Qwen Code, and similar terminal tools.

[![release](https://img.shields.io/github/v/release/DioNanos/termux-ai?include_prereleases&style=flat-square)](https://github.com/DioNanos/termux-ai/releases/tag/v0.118.0-ai.5)
[![apk](https://img.shields.io/github/downloads/DioNanos/termux-ai/v0.118.0-ai.5/total?style=flat-square&label=APK%20downloads)](https://github.com/DioNanos/termux-ai/releases/tag/v0.118.0-ai.5)
[![Android 7+](https://img.shields.io/badge/Android-7%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#install)
[![target SDK 28](https://img.shields.io/badge/target%20SDK-28-blue?style=flat-square)](#classic-compatibility)
[![license](https://img.shields.io/github/license/DioNanos/termux-ai?style=flat-square)](./LICENSE.md)

## Install

Download the latest APK from:

- [GitHub release v0.118.0-ai.5](https://github.com/DioNanos/termux-ai/releases/tag/v0.118.0-ai.5)

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

- keeps classic Termux package and proot compatibility
- improves Android keyboard microphone input in the terminal
- adds a toolbar text input path for quick command submission
- installs small `termux-ai-*` shell helpers inside `$PREFIX/bin`
- exposes a minimal Android context bridge for AI-aware helpers
- includes `mandoc` in the first bootstrap to avoid manpage database warnings

What this build does not do:

- replace upstream Termux
- target the Play Store compatibility model
- bypass Android scoped storage
- turn the hardware bridge into a full Android automation framework
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

## AI And Android Bridge

The bridge is intentionally small in this release. It is meant to give terminal
AI tools a clean way to ask for basic Android context without adding another
app.

Available command families:

- `sys.info`
- `sys.battery`
- `sys.clipboard.get`
- `sys.clipboard.set`
- `sys.vibrate`
- `sys.notify`
- `sensor.list`
- `sensor.read`
- `storage.info`
- `storage.list`
- `storage.read`
- `storage.write`

Generated shell helpers:

- `termux-ai`
- `termux-ai-info`
- `termux-ai-battery`
- `termux-ai-clipboard`
- `termux-ai-vibrate`
- `termux-ai-notify`
- `termux-ai-sensor`

Storage bridge access is restricted to allowed app roots. It is not a bypass for
Android scoped storage.

## Releases

- Current GitHub release: [v0.118.0-ai.5](https://github.com/DioNanos/termux-ai/releases/tag/v0.118.0-ai.5)
- Current line: `0.118.0-ai.x`
- Upstream base: Termux app classic line
- First public build: `0.118.0-ai.5`

The first public release is intentionally marked as a pre-release while real
device feedback is collected.

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
- The hardware bridge is a base layer and will expand only through clean Android
  public APIs.

## Roadmap

- improve voice input compatibility across Android keyboards
- add more AI-friendly terminal affordances without breaking shell behavior
- expand the Android bridge where public APIs allow it cleanly
- keep Classic releases stable for current AI CLI use
- study a separate modern-runtime line for target SDK 35+ without pretending it
  is classic Termux

## License And Thanks

Termux AI Classic preserves Termux licensing and notices. See the repository
license files for GPL, Apache, and MIT components.

Thanks to the Termux project and community for the foundation this work builds
on.
