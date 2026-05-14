# Security Policy

Termux AI Classic is a Termux-compatible APK build tuned for AI CLI workflows
on Android. Security issues fall into two scopes — please use the correct
channel based on where the issue actually lives.

## Upstream Termux issues

For vulnerabilities in the upstream Termux components inherited by this build
(terminal emulator, bootstrap packages, base Termux compatibility surface),
see the upstream Termux project's security policy:

- https://termux.dev/security

## Termux AI Classic specific issues

For vulnerabilities specific to the Termux AI Classic surface — the
`termux-ai` shell command, the Wave 1 Termux:API-compatible internal shims,
the toolbar text input path, the Android keyboard mic input path, the AICore
integration, or the APK packaging itself — please report privately to:

- `security@mmmbuto.com`

Please do not file public GitHub issues for unpatched vulnerabilities.

When reporting, please include:

- the APK release tag (e.g. `v0.118.0-ai.11`) — find it via "About" in the
  app or via `termux-ai --version`
- the Android version and the device model
- a minimal reproduction or proof of concept
- whether the issue is exploitable without granted Android permissions

Acknowledgement and remediation timing follow the usual responsible
disclosure norms: acknowledgement within 7 days, fix or mitigation timeline
proposed within 30 days where feasible.

## Out of scope

The following are intentionally excluded from this public release and are
not in scope for security reports against Termux AI Classic:

- SMS, calls, call logs, contacts
- continuous location
- USB and NFC access
- notification listener access

If you found a way to elicit these via the Wave 1 shims or the `termux-ai`
command, that is in scope and worth reporting.

---

*Maintainer: Davide A. Guglielmi (`security@mmmbuto.com`)*
