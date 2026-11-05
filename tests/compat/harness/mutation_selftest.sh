#!/usr/bin/env bash
# Negative counter-proof for termux-api-compat.test.sh.
#
# The previous harness was shown to stay green while an alias exited 9 after
# printing its output. This script re-derives that proof on a sandbox copy of
# the asset and asserts the OPPOSITE for the hardened harness: every
# alteration outside the punctual characterized defect must turn the harness
# red, and the unmutated control must stay green.
#
# Alteration classes (one mutation each, all on the sandbox asset only — the
# repository tree is never touched):
#   1 unexpected-nonzero-exit  output ok, exit 9 (the alias prints its output, then fails)
#   2 unexpected-stderr        noise on stderr on a success path
#   3 empty-stdout             the broadcast payload is never printed
#   4 malformed-json           error paths print non-JSON
#   5 wrong-result             a well-formed but different payload is printed
#   6 command-not-executed     an alias never broadcasts its command
#
# Needs bash 4+, python3. Run via run_all.sh (private HOME/TMPDIR).
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPAT_ROOT="$(cd "$HERE/.." && pwd)"
REPO_ROOT="$(cd "$COMPAT_ROOT/../.." && pwd)"
CLI_ASSET="$REPO_ROOT/app/src/main/assets/termux-ai"
INSTALLER_SRC="$REPO_ROOT/app/src/main/java/com/termux/app/terminal/ai/TermuxAiCliInstaller.java"
HARNESS="$HERE/termux-api-compat.test.sh"

SB="$(mktemp -d "${TMPDIR:-/tmp}/termuxai-s0-mut.XXXXXX")"
PRISTINE="$SB/pristine-termux-ai"
cleanup() { rm -rf "$SB"; }
trap cleanup EXIT

mkdir -p "$SB/app/src/main/assets" \
  "$SB/app/src/main/java/com/termux/app/terminal/ai" \
  "$SB/tests/compat/harness"
cp -r "$COMPAT_ROOT/fixtures" "$SB/tests/compat/fixtures"
cp "$COMPAT_ROOT/UPSTREAM_PIN.md" "$SB/tests/compat/UPSTREAM_PIN.md"
cp "$HARNESS" "$SB/tests/compat/harness/termux-api-compat.test.sh"
cp "$INSTALLER_SRC" "$SB/app/src/main/java/com/termux/app/terminal/ai/TermuxAiCliInstaller.java"
cp "$CLI_ASSET" "$PRISTINE"

SANDBOX_ASSET="$SB/app/src/main/assets/termux-ai"

PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL %s\n' "$1"; }

# mutate NAME EXPECTED-FAILING-CASE-FRAGMENT <<'PY' ... PY
# The heredoc is a python program that reads the pristine asset from stdin
# path argv[1] and writes the mutated copy to argv[2]; it must assert that
# its replacement actually happened.
mutate() {
  local name="$1" fragment="$2"
  cp "$PRISTINE" "$SANDBOX_ASSET"
  python3 - "$PRISTINE" "$SANDBOX_ASSET"
  local patched=$?
  if [ "$patched" -ne 0 ]; then bad "$name: mutation patch failed"; return; fi
  local log="$SB/harness.log"
  bash "$SB/tests/compat/harness/termux-api-compat.test.sh" > "$log" 2>&1
  local rc=$?
  if [ "$rc" -eq 0 ]; then
    bad "$name: harness stayed GREEN under mutation (rc 0) — false green"
    return
  fi
  if ! grep -q "^FAIL .*$fragment" "$log"; then
    bad "$name: harness went red (rc $rc) but no FAIL line matches '$fragment'"
    printf '     %s FAIL lines total; first: %s\n' "$(grep -c '^FAIL' "$log")" "$(grep -m1 '^FAIL' "$log")"
    return
  fi
  ok "$name: harness red as required (rc $rc, expected case failed)"
}

# ---------------------------------------------------------------- control
cp "$PRISTINE" "$SANDBOX_ASSET"
bash "$SB/tests/compat/harness/termux-api-compat.test.sh" > "$SB/harness.log" 2>&1
if [ $? -eq 0 ]; then
  ok "control: unmutated sandbox harness is green"
else
  bad "control: unmutated sandbox harness is NOT green — fix the harness before trusting the mutations"
  printf '%s\n' "$(tail -n 5 "$SB/harness.log")"
fi

# ------------------------------------------------------------- mutations
# 1. an alias that fails after printing: output printed, then exit 9 (camera paths)
mutate "unexpected-nonzero-exit (camera alias exits 9 after the output)" \
  "alias termux-camera-info prints exactly the envelope" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
data = open(src, encoding="utf-8").read()
anchor = "  printf '%s\\n' \"$data\"\n"
patched = data.replace(anchor, anchor + "  if [ \"$cmd\" = \"camera.info\" ]; then return 9; fi\n", 1)
assert patched != data, "anchor not found: run_broadcast printf"
open(dst, "w", encoding="utf-8").write(patched)
PY

# 2. unexpected stderr on a success path (battery paths)
mutate "unexpected-stderr (battery success path writes to stderr)" \
  "native battery: cmd sys.battery" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
data = open(src, encoding="utf-8").read()
anchor = "  printf '%s\\n' \"$data\"\n"
patched = data.replace(anchor, anchor + "  if [ \"$cmd\" = \"sys.battery\" ]; then printf 'unexpected noise\\n' >&2; fi\n", 1)
assert patched != data, "anchor not found: run_broadcast printf"
open(dst, "w", encoding="utf-8").write(patched)
PY

# 3. empty stdout: the payload is never printed
mutate "empty-stdout (broadcast payload not printed)" \
  "native clipboard get: cmd sys.clipboard.get" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
data = open(src, encoding="utf-8").read()
anchor = "  printf '%s\\n' \"$data\"\n"
patched = data.replace(anchor, "  :\n", 1)
assert patched != data, "anchor not found: run_broadcast printf"
open(dst, "w", encoding="utf-8").write(patched)
PY

# 4. malformed JSON on every error path (fail_json + emit_error)
mutate "malformed-json (error paths print non-JSON)" \
  "native unknown verb" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
data = open(src, encoding="utf-8").read()
anchor = "  printf '{\"ok\":false,\"error\":%s}\\n' \"$(json_string \"$message\")\"\n"
patched = data.replace(anchor, "  printf 'not json at all\\n'\n")
assert patched != data and anchor not in patched, "anchor not found: error printf"
open(dst, "w", encoding="utf-8").write(patched)
PY

# 5. well-formed JSON, wrong payload
mutate "wrong-result (a different well-formed payload is printed)" \
  "alias battery stdout is byte-exact" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
data = open(src, encoding="utf-8").read()
anchor = "  printf '%s\\n' \"$data\"\n"
patched = data.replace(anchor, "  printf '%s\\n' '{\"ok\":true,\"data\":{\"wrong\":\"payload\"}}'\n", 1)
assert patched != data, "anchor not found: run_broadcast printf"
open(dst, "w", encoding="utf-8").write(patched)
PY

# 6. the alias never broadcasts its command
mutate "command-not-executed (termux-vibrate alias never broadcasts)" \
  "alias termux-vibrate: default duration_ms 1000" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
data = open(src, encoding="utf-8").read()
anchor = "      run_broadcast sys.vibrate --ei duration_ms \"$duration\"\n"
patched = data.replace(anchor, "      :\n", 1)
assert patched != data, "anchor not found: vibrate alias broadcast"
open(dst, "w", encoding="utf-8").write(patched)
PY

# --------------------------------------------------------------- summary
printf '\nmutation selftest: %d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
