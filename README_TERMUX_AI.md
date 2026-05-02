# termux-ai

`termux-ai` is an unofficial Termux app fork focused on making Android a practical
runtime for AI CLI agents such as Codex, Gemini CLI, Qwen Code, and future Claude
workflows.

The base stays close to upstream Termux:

- package name remains `com.termux`;
- Termux bootstrap and package behavior remain upstream-compatible;
- proot remains optional compatibility tooling, not the base runtime;
- signing keys, local SDK paths, and runtime API keys are not stored in Git.

## V1 Focus

- Voice-friendly terminal toolbar input for AI CLI prompts and commands.
- Android `SpeechRecognizer` support from the toolbar input field.
- `Send` from the toolbar text input writes a full command line and presses enter.
- Internal safe-core Android bridge for AI helpers:
  - system info;
  - battery;
  - clipboard get/set;
  - vibrate;
  - notification;
  - sensor list/read;
  - guarded app storage list/read/write.
- Basic `$PREFIX/bin/termux-ai-*` wrappers generated on app startup when the
  Termux bootstrap is present.

## Build

Use a full JDK, not a JRE-only install:

```sh
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/dag/Android/Sdk ./gradlew --no-daemon assembleRelease
```

Local signing is read from `.signing/keystore.properties` and `.signing/*.jks`.
The `.signing/` directory is ignored and must not be committed.

## License

This fork preserves Termux licensing. See the upstream Termux license files in
this repository for the applicable GPL, Apache, and MIT notices.
