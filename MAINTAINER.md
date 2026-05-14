# Maintainer

Termux AI Classic is maintained by **Davide A. Guglielmi** (GitHub:
[DioNanos](https://github.com/DioNanos)).

This is **not** an independent fork of Termux — Termux AI Classic is a
Termux-compatible build tuned for AI CLI workflows on Android. It preserves
classic Termux licensing and notices (GPLv3 / Apache / MIT components as in
upstream Termux), keeps the `com.termux` package name and the classic Target
SDK 28 compatibility model, and adds a narrow Android-side compatibility
layer for AI CLI use.

## Scope of maintenance

In scope:

- the Termux-compatible APK build with Classic compatibility (`com.termux`,
  Target SDK 28, bootstrap `apt-android-7`)
- Android keyboard microphone input through normal IME text input
- the toolbar text input path for quick command submission
- the `termux-ai` shell command exposing a small JSON-first Android context
  surface
- Wave 1 internal compatibility with `termux-api` commands (TTS, toast,
  brightness, torch, camera info, notifications, wifi info, battery,
  clipboard, vibrate)
- the GitHub release flow with APK assets

Out of scope here:

- changes that belong upstream — please file those on the
  [Termux](https://github.com/termux) organization repositories directly
- expanding to sensitive phone automation (SMS, calls, call logs, contacts,
  continuous location, USB, NFC, notification listener access) — these are
  intentionally not part of the public release
- the Play Store compatibility model

## Reporting

| Channel | Where |
|---|---|
| Termux AI Classic bug reports, PRs | [DioNanos/termux-ai](https://github.com/DioNanos/termux-ai) |
| Generic Termux bugs (not AI-Classic-specific) | [Termux organization](https://github.com/termux) |
| Security disclosures (Termux AI Classic) | [`SECURITY.md`](./SECURITY.md) — see also `security@mmmbuto.com` |
| General contact | `dev@mmmbuto.com` |

When reporting a bug, please include: device, Android version, the APK
release tag (e.g. `v0.118.0-ai.11`), and the failing command (especially for
`termux-ai` and Termux:API-compatible shims).

## Identity

- Profile: [github.com/DioNanos](https://github.com/DioNanos)
- Project hub: [mmmbuto.com](https://mmmbuto.com)
- Maintainer page and dev journal: [dev.mmmbuto.com](https://dev.mmmbuto.com)

## License

Termux AI Classic preserves Termux licensing and notices. See
[`LICENSE.md`](./LICENSE.md) for the GPL/Apache/MIT component breakdown.

---

*Per aspera ad astra.*
