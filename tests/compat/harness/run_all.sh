#!/usr/bin/env bash
# Sandboxed entry point for the compat harness + its negative
# counter-proof.
#
# Run from the repo root as required:
#
#   systemd-run --user --scope -p MemoryMax=2G \
#     bash tests/compat/harness/run_all.sh
#
# It gives both scripts a private HOME and TMPDIR on disk under /tmp and
# propagates a nonzero exit code if either the unmutated harness fails or
# any mutation of the counter-proof unexpectedly stays green.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SANDBOX="$(mktemp -d /tmp/termuxai-s0-sandbox.XXXXXX)"
mkdir -p "$SANDBOX/home" "$SANDBOX/tmp"
cleanup() { rm -rf "$SANDBOX"; }
trap cleanup EXIT

rc=0

HOME="$SANDBOX/home" TMPDIR="$SANDBOX/tmp" \
  bash "$HERE/termux-api-compat.test.sh"
harness_rc=$?
[ "$harness_rc" -eq 0 ] || rc=1
printf 'harness rc=%d\n' "$harness_rc"

printf -- '--- mutation selftest (negative counter-proof) ---\n'
HOME="$SANDBOX/home" TMPDIR="$SANDBOX/tmp" \
  bash "$HERE/mutation_selftest.sh"
selftest_rc=$?
[ "$selftest_rc" -eq 0 ] || rc=1
printf 'mutation selftest rc=%d\n' "$selftest_rc"

printf -- '--- compat client JVM harness (transport seam) ---\n'
HOME="$SANDBOX/home" TMPDIR="$SANDBOX/tmp" \
  bash "$HERE/compat-client.test.sh"
s1b1_rc=$?
[ "$s1b1_rc" -eq 0 ] || rc=1
printf 'compat-client rc=%d\n' "$s1b1_rc"

printf -- '--- camera pin-name suite ---\n'
HOME="$SANDBOX/home" TMPDIR="$SANDBOX/tmp" \
  bash "$HERE/camera-names.test.sh"
cameranames_rc=$?
[ "$cameranames_rc" -eq 0 ] || rc=1
printf 'camera-names rc=%d\n' "$cameranames_rc"

printf -- '--- AudioTrack fallback suite ---\n'
HOME="$SANDBOX/home" TMPDIR="$SANDBOX/tmp" \
  bash "$HERE/audiotrack-fallback.test.sh"
audiotrack_rc=$?
[ "$audiotrack_rc" -eq 0 ] || rc=1
printf 'audiotrack-fallback rc=%d\n' "$audiotrack_rc"

printf -- '--- camera producer-path suite ---\n'
HOME="$SANDBOX/home" TMPDIR="$SANDBOX/tmp" \
  bash "$HERE/camera-producer.test.sh"
cameraproducer_rc=$?
[ "$cameraproducer_rc" -eq 0 ] || rc=1
printf 'camera-producer rc=%d\n' "$cameraproducer_rc"

printf -- '--- signature check (all touched main classes, signature stubs) ---\n'
HOME="$SANDBOX/home" TMPDIR="$SANDBOX/tmp" \
  bash "$HERE/signature-check.test.sh"
sig_rc=$?
[ "$sig_rc" -eq 0 ] || rc=1
printf 'signature-check rc=%d\n' "$sig_rc"

printf -- '--- MCP content-free audit log v2 (leak, wiring, rotation, mutations) ---\n'
HOME="$SANDBOX/home" TMPDIR="$SANDBOX/tmp" \
  bash "$HERE/mcp-audit.test.sh"
mcpaudit_rc=$?
[ "$mcpaudit_rc" -eq 0 ] || rc=1
printf 'mcp-audit rc=%d\n' "$mcpaudit_rc"

printf -- '--- MCP verified token + stdio shim (bypass, epochs, wiring, shim, E2E, installer, mutations) ---\n'
HOME="$SANDBOX/home" TMPDIR="$SANDBOX/tmp" \
  bash "$HERE/mcp-auth.test.sh"
mcpauth_rc=$?
[ "$mcpauth_rc" -eq 0 ] || rc=1
printf 'mcp-auth rc=%d\n' "$mcpauth_rc"

printf 'run_all rc=%d (sandbox %s removed)\n' "$rc" "$SANDBOX"
exit "$rc"
