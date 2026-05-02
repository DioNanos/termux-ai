# Termux AI

> Optimized Termux version for **AI CLI workflows on Android**.
> Built for Codex, Gemini CLI, Qwen Code, Claude-style coworkers, and Android-native agent tooling.

## Install

Install the release APK on Android 7+.

Current local test build:

```text
app/build/outputs/apk/release/termux-app_apt-android-7-release_universal.apk
```

Important: Termux uses the `com.termux` package and shared signing model. If another Termux build is already installed with a different signature, uninstall Termux and its plugins before installing this APK.

## Focus

Termux AI keeps the normal Termux environment and adds the pieces needed to make AI CLI tools work better on Android:

- voice-friendly terminal input;
- Android speech recognition from the toolbar;
- toolbar send behavior that executes spoken commands;
- Android hardware/context bridge for AI helpers;
- generated `termux-ai-*` shell helpers inside `$PREFIX/bin`;
- compatibility with normal Termux package and proot workflows.

## Current Android Delta

- Text Input toolbar sends command text plus Enter.
- Microphone button uses Android `SpeechRecognizer`.
- `RECORD_AUDIO` permission is requested for voice input.
- Internal AI bridge exposes safe base commands:
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
- Startup installs helper wrappers when the Termux bootstrap is present:
  - `termux-ai`
  - `termux-ai-info`
  - `termux-ai-battery`
  - `termux-ai-clipboard`
  - `termux-ai-vibrate`
  - `termux-ai-notify`
  - `termux-ai-sensor`

## Build

```bash
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/dag/Android/Sdk ./gradlew --no-daemon assembleRelease
```

Local signing is read from `.signing/keystore.properties` and `.signing/*.jks`.
The `.signing/` directory is ignored and must never be committed.

## Release Flow

- develop on Forge for active work;
- main for tested, sanitized promotion;
- GitHub stays private until explicitly approved for public release.

## License

Termux AI preserves Termux licensing and notices.

See the repository license files for GPL, Apache, and MIT components.
