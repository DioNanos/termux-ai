# Termux AI Classic

> Optimized Termux Classic build for **AI CLI workflows on Android**.
> It keeps the normal Termux environment and adds a small compatibility layer for
> phone-first use with Codex, Gemini CLI, Qwen Code, and similar terminal tools.

[![release](https://img.shields.io/github/v/release/DioNanos/termux-ai?style=flat-square)](https://github.com/DioNanos/termux-ai/releases/tag/v0.118.0-ai.6)
[![apk](https://img.shields.io/github/downloads/DioNanos/termux-ai/v0.118.0-ai.6/total?style=flat-square&label=APK%20downloads)](https://github.com/DioNanos/termux-ai/releases/tag/v0.118.0-ai.6)
[![Android 7+](https://img.shields.io/badge/Android-7%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#install)
[![target SDK 28](https://img.shields.io/badge/target%20SDK-28-blue?style=flat-square)](#classic-compatibility)
[![license](https://img.shields.io/github/license/DioNanos/termux-ai?style=flat-square)](./LICENSE.md)

## Install

Download the latest APK from:

- [GitHub release v0.118.0-ai.6](https://github.com/DioNanos/termux-ai/releases/tag/v0.118.0-ai.6)

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
- installs a verified `termux-ai` shell command for core Android context
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

## AI And Android

This release focuses on making the normal terminal more practical for AI CLI
work from Android:

- Android keyboard microphone dictation works through normal IME text input.
- The toolbar text field can send command text quickly.
- The `termux-ai` command exposes a small JSON-first Android context surface.

Stable commands in this line:

```bash
termux-ai --version
termux-ai doctor
termux-ai ping
termux-ai info
termux-ai battery
termux-ai clipboard get
termux-ai clipboard set "hello from Termux AI"
```

Output is compact JSON by default. Commands return exit code `0` only when the
bridge reports success.

## Releases

- Current GitHub release: [v0.118.0-ai.6](https://github.com/DioNanos/termux-ai/releases/tag/v0.118.0-ai.6)
- Current line: `0.118.0-ai.x`
- Upstream base: Termux app classic line
- First public build: `0.118.0-ai.5`

This is the first public release of Termux AI Classic.

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
- Android bridge commands outside the documented `termux-ai` core are not stable
  public shell API in this release.

## Roadmap

- improve voice input compatibility across Android keyboards
- add more AI-friendly terminal affordances without breaking shell behavior
- expand Android bridge commands only after bootstrap-safe installation is
  validated on device
- keep Classic releases stable for current AI CLI use
- study a separate modern-runtime line for target SDK 35+ without pretending it
  is classic Termux

## License And Thanks

Termux AI Classic preserves Termux licensing and notices. See the repository
license files for GPL, Apache, and MIT components.

Thanks to the Termux project and community for the foundation this work builds
on.
