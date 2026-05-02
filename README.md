# Termux AI Classic

Optimized Termux Classic version for AI CLI workflows on Android.

Termux AI Classic keeps the normal Termux environment and focuses on making
command-line AI tools practical from a phone: Codex, Gemini CLI, Qwen Code, and
similar terminal-first assistants.

## Focus

- direct Android keyboard microphone input in the terminal;
- toolbar text input that can send commands quickly;
- small Android context bridge for AI-aware helpers;
- generated `termux-ai-*` shell helpers inside `$PREFIX/bin`;
- clean first bootstrap with `mandoc` included for manpage database updates;
- compatibility with normal Termux package and proot workflows.

The goal is not to replace every Android app with another app. The goal is to
make Termux a stronger Android cowork environment for AI CLIs.

## Current Status

This is a Classic Termux-compatible build.

- Package name: `com.termux`
- Minimum Android: 7
- Target SDK: 28
- Compile SDK: 36
- Bootstrap variant: `apt-android-7`

Target SDK 28 is intentional. Android blocks execution of binaries extracted in
the app data directory for apps targeting API 29+, while Termux executes its
package binaries from:

```text
/data/data/com.termux/files/usr/bin
```

Keeping target SDK 28 preserves classic Termux behavior. New Android versions
may show an "app was built for an older Android version" warning; that is the
tradeoff for full Termux package compatibility.

## Android Bridge

The bridge is intentionally small and safe in this release.

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

## Install Notes

Termux AI Classic uses the `com.termux` package name. Android requires an
installed app update to be signed with the same key as the existing app.

If another Termux build is already installed with a different signature, make a
backup first, then uninstall Termux and its plugins before installing this APK.
Future Termux AI Classic releases signed with the same key can update this
build.

## Build

```bash
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/dag/Android/Sdk ./gradlew --no-daemon assembleRelease
```

The build downloads the upstream Termux bootstrap zips and applies a small
`mandoc` overlay before native packaging. The overlay keeps the first bootstrap
cleaner without committing large generated bootstrap archives.

## Limitations

- This build stays on target SDK 28 for Termux compatibility.
- It is not Play Store-targeted in its current Classic form.
- Updating an existing F-Droid Termux install in place is not possible unless
  the APK is signed with the same key.
- The hardware bridge is a base layer, not a full Android automation framework.
- A future modern-runtime design would require a different package execution
  model instead of a minimal target SDK bump.

## Roadmap

- improve voice input compatibility across Android keyboards;
- add more AI-friendly terminal affordances without breaking shell behavior;
- expand the bridge only where Android public APIs allow it cleanly;
- keep Classic releases stable for current AI CLI use;
- study a separate modern-runtime line for target SDK 35+ without pretending it
  is classic Termux.

## License And Thanks

Termux AI Classic preserves Termux licensing and notices. See the repository
license files for GPL, Apache, and MIT components.

Thanks to the Termux project and community for the foundation this work builds
on.
