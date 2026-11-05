#!/usr/bin/env bash
# Compat matrix harness: measures the behaviour of the three observable
# entries (native termux-ai, libexec termux-api <Method>, termux-* aliases)
# against the contract recorded in ../MATRIX.md.
#
# Everything runs against a stub termux-am: no phone, no app, no $PREFIX.
# Cases tagged [baseline-defect] assert the CURRENT (wrong) behaviour on
# purpose; they must be updated by the layer that fixes the defect.
#
# Contract of every case: expected stdout, expected stderr and expected exit
# code are all asserted. A characterization case accepts ONLY the exact
# measured defect (e.g. "cmd is -e and rc is 1"), never "any failure": an
# unexpected exit code, unexpected stderr, empty/malformed stdout or a
# different payload must turn the case red. harness/mutation_selftest.sh
# proves that by mutating a sandbox copy of the asset.
#
# Needs: bash 4+, python3. Run via run_all.sh (private HOME/TMPDIR).
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPAT_ROOT="$(cd "$HERE/.." && pwd)"
REPO_ROOT="$(cd "$COMPAT_ROOT/../.." && pwd)"
CLI_ASSET="$REPO_ROOT/app/src/main/assets/termux-ai"
GOLDEN_NATIVE="$COMPAT_ROOT/fixtures/golden/native"
GOLDEN_COMPAT="$COMPAT_ROOT/fixtures/golden/compat"
PIN_DIR="$COMPAT_ROOT/fixtures/upstream-pin"
PIN_DOC="$COMPAT_ROOT/UPSTREAM_PIN.md"
INSTALLER_SRC="$REPO_ROOT/app/src/main/java/com/termux/app/terminal/ai/TermuxAiCliInstaller.java"

[ -f "$CLI_ASSET" ] || { echo "harness: CLI asset not found: $CLI_ASSET" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/termuxai-s0.XXXXXX")"
PASS=0
FAIL=0

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

ok()  { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL %s\n' "$1"; [ -z "${2:-}" ] || printf '     %s\n' "$2"; }
check() { # check NAME CONDITION-EXIT-CODE [DETAIL]
  if [ "$2" -eq 0 ]; then ok "$1"; else bad "$1" "${3:-}"; fi
}

# ---------------------------------------------------------------- stub am
# The stub emulates the am<->shim interface: one completion line per
# broadcast, result code and data inline, single-line payload, no escaping
# (what run_broadcast's sed expects). It answers per bridge cmd from a
# routes file; an unknown cmd answers result=1 + the unknown-command
# envelope, which is how dispatch defects become observable.
mkdir -p "$WORK/bin" "$WORK/home"
cat > "$WORK/bin/termux-am" <<'STUB'
#!/usr/bin/env bash
exec python3 - "$AM_ROUTES" "$AM_LOG" "$@" <<'PY'
import json, os, sys
routes_path, log_path = sys.argv[1], sys.argv[2]
argv = sys.argv[3:]
with open(log_path, "ab") as f:
    f.write(json.dumps(argv).encode("utf-8", "surrogateescape") + b"\n")
cmd = None
for i, a in enumerate(argv):
    if a == "--es" and i + 2 < len(argv) and argv[i + 1] == "cmd":
        cmd = argv[i + 2]
        break
routes = {}
if os.path.exists(routes_path):
    with open(routes_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            key, _, path = line.partition(" ")
            routes[key] = path
if cmd is not None and cmd in routes and os.path.exists(routes[cmd]):
    with open(routes[cmd], "rb") as f:
        payload = f.read().rstrip(b"\n").decode("utf-8", "replace")
    result = 0
else:
    payload = '{"ok":false,"error":"Unknown command: %s"}' % (cmd if cmd is not None else "<none>")
    result = 1
sys.stdout.write("Broadcast completed: result=%d, data=\"%s\"\n" % (result, payload))
sys.exit(0)
PY
STUB
chmod +x "$WORK/bin/termux-am"

{
  printf '%s %s\n' sys.battery          "$GOLDEN_NATIVE/battery.json"
  printf '%s %s\n' sys.clipboard.get    "$GOLDEN_NATIVE/clipboard-get.json"
  printf '%s %s\n' sys.clipboard.set    "$GOLDEN_NATIVE/clipboard-set.json"
  printf '%s %s\n' sys.vibrate          "$GOLDEN_NATIVE/vibrate.json"
  printf '%s %s\n' notification.show    "$GOLDEN_NATIVE/notification-show.json"
  printf '%s %s\n' notification.channel "$GOLDEN_NATIVE/notification-channel.json"
  printf '%s %s\n' notification.remove  "$GOLDEN_NATIVE/notification-remove.json"
  printf '%s %s\n' ui.toast             "$GOLDEN_NATIVE/toast.json"
  printf '%s %s\n' audio.info           "$GOLDEN_NATIVE/audio-info.json"
  printf '%s %s\n' audio.volume         "$GOLDEN_NATIVE/volume-set.json"
  printf '%s %s\n' audio.volume.list    "$GOLDEN_NATIVE/volume-list.json"
  printf '%s %s\n' display.brightness   "$GOLDEN_NATIVE/brightness.json"
  printf '%s %s\n' camera.info          "$GOLDEN_NATIVE/camera-info.json"
  printf '%s %s\n' camera.torch         "$GOLDEN_NATIVE/torch.json"
  printf '%s %s\n' wifi.connection      "$GOLDEN_NATIVE/wifi-connection.json"
  printf '%s %s\n' tts.engines          "$GOLDEN_NATIVE/tts-engines.json"
  printf '%s %s\n' tts.speak            "$GOLDEN_NATIVE/tts-speak.json"
} > "$WORK/routes"
export AM_ROUTES="$WORK/routes"
export AM_LOG="$WORK/am.log"
: > "$AM_LOG"

# The same shipped asset, installed under every name the installer uses —
# the CLI dispatches on its own basename, exactly like $PREFIX does.
ENTRY_NAMES="termux-ai termux-api termux-audio-info termux-battery-status \
termux-brightness termux-camera-info termux-clipboard-get termux-clipboard-set \
termux-notification termux-notification-channel termux-notification-remove \
termux-toast termux-torch termux-tts-engines termux-tts-speak termux-vibrate \
termux-volume termux-wifi-connectioninfo"
for name in $ENTRY_NAMES; do
  cp "$CLI_ASSET" "$WORK/bin/$name"
  chmod +x "$WORK/bin/$name"
done

WORK_STDIN="/dev/null"
run_entry() { # run_entry NAME [ARGS...] — stdin from $WORK_STDIN
  local name="$1"; shift
  PATH="$WORK/bin:$PATH" HOME="$WORK/home" \
    bash "$WORK/bin/$name" "$@" > "$WORK/out" 2> "$WORK/err" < "$WORK_STDIN"
  RC=$?
  WORK_STDIN="/dev/null"
}

# Preamble every assertion snippet shares: loads OUT/ERR/RC plus the last
# broadcast argv as CMD (index 8: broadcast --receiver-foreground -n R -a A
# --es cmd <CMD> ...) and EXTRAS (everything after the cmd value).
cat > "$WORK/preamble.py" <<'PY'
import json, sys
OUT = open(sys.argv[1], "rb").read()
ERR = open(sys.argv[2], "rb").read()
RC = int(sys.argv[4])
_lines = [json.loads(l) for l in open(sys.argv[3], encoding="utf-8", errors="replace") if l.strip()]
LAST = _lines[-1] if _lines else []
CMD = LAST[8] if len(LAST) > 8 else None
EXTRAS = LAST[9:]
PY

# amcheck NAME <<'PY'  ...assertions...  PY
# The heredoc is the assertion program (no eval anywhere): it runs with the
# preamble above prepended; a raised exception or a false assert fails.
amcheck() {
  local name="$1"
  { cat "$WORK/preamble.py"; cat; } | python3 - "$WORK/out" "$WORK/err" "$AM_LOG" "$RC"
  check "$name" $? "rc=$RC out=$(head -c 120 "$WORK/out" | tr '\n' ' ') err=$(head -c 80 "$WORK/err")"
}

# =========================================================== native entry
run_entry termux-ai battery
amcheck "native battery: cmd sys.battery, envelope on stdout, empty stderr, rc 0" <<'PY'
assert CMD == "sys.battery" and b"level" in OUT and OUT.startswith(b'{"ok":true') \
  and ERR == b"" and RC == 0
PY

run_entry termux-ai clipboard get
amcheck "native clipboard get: cmd sys.clipboard.get, envelope, empty stderr, rc 0" <<'PY'
assert CMD == "sys.clipboard.get" and OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-ai clipboard set hello world
amcheck "native clipboard set: text extra, args space-joined, rc 0" <<'PY'
assert CMD == "sys.clipboard.set"
assert EXTRAS[EXTRAS.index("--es") + 1:EXTRAS.index("--es") + 3] == ["text", "hello world"]
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-ai bogus
amcheck "native unknown verb: JSON error on stdout, empty stderr, rc 1" <<'PY'
assert OUT.startswith(b'{"ok":false') and b"Unknown command" in OUT and ERR == b"" and RC == 1
PY

run_entry termux-ai audio info
amcheck "native audio info: envelope, empty stderr, rc 0" <<'PY'
assert CMD == "audio.info" and b"sample_rate" in OUT and OUT.startswith(b'{"ok":true') \
  and ERR == b"" and RC == 0
PY

run_entry termux-ai volume
amcheck "native volume (no args): list cmd, envelope, rc 0" <<'PY'
assert CMD == "audio.volume.list" and OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-ai volume music 5
amcheck "native volume set: stream+volume extras, rc 0" <<'PY'
assert CMD == "audio.volume"
assert EXTRAS[EXTRAS.index("--es") + 2] == "music"
assert EXTRAS[EXTRAS.index("--ei") + 2] == "5"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-ai brightness auto
amcheck "native brightness auto: --ez auto true, rc 0" <<'PY'
assert CMD == "display.brightness"
assert EXTRAS[EXTRAS.index("--ez") + 1:EXTRAS.index("--ez") + 3] == ["auto", "true"]
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-ai torch on
amcheck "native torch on: --ez enabled true, rc 0" <<'PY'
assert CMD == "camera.torch"
assert EXTRAS[EXTRAS.index("--ez") + 2] == "true"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-ai notify Title body text
amcheck "native notify: title+text extras, rc 0" <<'PY'
assert CMD == "notification.show"
assert "Title" in EXTRAS and "body text" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-ai wifi connection
amcheck "native wifi: envelope, empty stderr, rc 0" <<'PY'
assert CMD == "wifi.connection" and b"ssid" in OUT and OUT.startswith(b'{"ok":true') \
  and ERR == b"" and RC == 0
PY

run_entry termux-ai tts engines
amcheck "native tts engines: envelope, rc 0" <<'PY'
assert CMD == "tts.engines" and OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-ai toast ciao
amcheck "native toast: text extra, rc 0" <<'PY'
assert CMD == "ui.toast" and "ciao" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

# ============================================================ compat entry
run_entry termux-api AudioInfo
amcheck "[baseline-defect] compat AudioInfo prints the envelope, not the naked payload" <<'PY'
assert CMD == "audio.info" and OUT.startswith(b'{"ok":true,') and ERR == b"" and RC == 0
PY

run_entry termux-api BatteryStatus
amcheck "[baseline-defect] compat BatteryStatus prints the envelope, not the upstream object" <<'PY'
assert CMD == "sys.battery" and OUT.startswith(b'{"ok":true,') and ERR == b"" and RC == 0
PY

run_entry termux-api CameraInfo
amcheck "[baseline-defect] compat CameraInfo prints the envelope, not the upstream array" <<'PY'
assert CMD == "camera.info" and OUT.startswith(b'{"ok":true,') and ERR == b"" and RC == 0
PY

run_entry termux-api WifiConnectionInfo
amcheck "[baseline-defect] compat WifiConnectionInfo prints the envelope" <<'PY'
assert CMD == "wifi.connection" and OUT.startswith(b'{"ok":true,') and ERR == b"" and RC == 0
PY

run_entry termux-api Brightness --ei brightness 128
amcheck "[baseline-defect] compat Brightness forwards am extras but prints the envelope" <<'PY'
assert CMD == "display.brightness"
assert EXTRAS[EXTRAS.index("--ei") + 2] == "128" and OUT.startswith(b'{"ok":true,')
assert ERR == b"" and RC == 0
PY

run_entry termux-api Torch --ez enabled true
amcheck "[baseline-defect] compat Torch forwards extras, prints the envelope (upstream: empty stdout)" <<'PY'
assert CMD == "camera.torch"
assert EXTRAS[EXTRAS.index("--ez") + 2] == "true" and OUT.startswith(b'{"ok')
assert ERR == b"" and RC == 0
PY

run_entry termux-api Vibrate --ei duration_ms 250
amcheck "[baseline-defect] compat Vibrate forwards extras, prints the envelope (upstream: empty stdout)" <<'PY'
assert CMD == "sys.vibrate"
assert EXTRAS[EXTRAS.index("--ei") + 2] == "250" and OUT.startswith(b'{"ok')
assert ERR == b"" and RC == 0
PY

run_entry termux-api Notification --es title T
amcheck "[baseline-defect] compat Notification forwards extras, prints the envelope (upstream: empty stdout)" <<'PY'
assert CMD == "notification.show" and "T" in EXTRAS and OUT.startswith(b'{"ok')
assert ERR == b"" and RC == 0
PY

run_entry termux-api NotificationChannel --es id ch --es name N
amcheck "[baseline-defect] compat NotificationChannel forwards extras, prints the envelope (upstream: a plain text line)" <<'PY'
assert CMD == "notification.channel" and "N" in EXTRAS and OUT.startswith(b'{"ok')
assert ERR == b"" and RC == 0
PY

run_entry termux-api NotificationRemove --es id 42
amcheck "[baseline-defect] compat NotificationRemove forwards extras, prints the envelope (upstream: empty stdout)" <<'PY'
assert CMD == "notification.remove" and "42" in EXTRAS and OUT.startswith(b'{"ok')
assert ERR == b"" and RC == 0
PY

run_entry termux-api Clipboard
amcheck "compat Clipboard with no extras defaults to sys.clipboard.get" <<'PY'
assert CMD == "sys.clipboard.get" and OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-api Clipboard -e api_version 2 --ez set true
amcheck "[baseline-defect] compat Clipboard set-form eats argv1 as cmd (shim :535): cmd is -e, run fails" <<'PY'
assert CMD == "-e" and RC == 1 and b"Unknown command: -e" in OUT and ERR == b""
PY

printf 'synthetic stdin' > "$WORK/stdin"
WORK_STDIN="$WORK/stdin" run_entry termux-api Clipboard -e api_version 2 --ez set true
amcheck "compat entry: no text extra ever reaches the bridge (stdin not consumed on this path)" <<'PY'
assert CMD == "-e" and RC == 1 and ERR == b"" and b"Unknown command: -e" in OUT
assert "text" not in EXTRAS and "synthetic stdin" not in EXTRAS
PY

run_entry termux-api Volume
amcheck "compat Volume with no extras defaults to audio.volume.list" <<'PY'
assert CMD == "audio.volume.list" and OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-api Volume -a set-volume --es stream music --ei volume 5
amcheck "[baseline-defect] compat Volume set-form eats argv1 as cmd (shim :549): cmd is -a, run fails" <<'PY'
assert CMD == "-a" and RC == 1 and b"Unknown command: -a" in OUT and ERR == b""
PY

run_entry termux-api TextToSpeech --es engine LIST_AVAILABLE
amcheck "compat TextToSpeech upstream engines invocation (--es engine LIST_AVAILABLE) lands on tts.speak" <<'PY'
assert CMD == "tts.speak" and "LIST_AVAILABLE" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-api TextToSpeech LIST_AVAILABLE
amcheck "compat TextToSpeech bare argv1 LIST_AVAILABLE maps to tts.engines (the only engines path here)" <<'PY'
assert CMD == "tts.engines" and OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-api NoSuchMethod
amcheck "compat unknown method: JSON error on stdout, empty stderr, rc 1 (upstream: stderr + empty stdout)" <<'PY'
assert OUT.startswith(b'{"ok":false') and b"Unsupported internal API method" in OUT and ERR == b"" and RC == 1
PY

# ============================================================= alias entry
run_entry termux-audio-info
amcheck "[baseline-defect] alias termux-audio-info prints the envelope" <<'PY'
assert CMD == "audio.info" and OUT.startswith(b'{"ok":true,') and ERR == b"" and RC == 0
PY

run_entry termux-battery-status
amcheck "[baseline-defect] alias termux-battery-status prints the envelope (upstream: naked object)" <<'PY'
assert CMD == "sys.battery" and OUT.startswith(b'{"ok":true,') and ERR == b"" and RC == 0
PY

run_entry termux-brightness 128
amcheck "alias termux-brightness N: --ei brightness N, no --ez auto false (upstream sends it too)" <<'PY'
assert CMD == "display.brightness"
assert EXTRAS[EXTRAS.index("--ei") + 2] == "128"
assert "--ez" not in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-brightness auto
amcheck "alias termux-brightness auto: --ez auto true" <<'PY'
assert CMD == "display.brightness"
assert EXTRAS[EXTRAS.index("--ez") + 1:EXTRAS.index("--ez") + 3] == ["auto", "true"]
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-brightness notanumber
amcheck "alias termux-brightness does not validate the level (upstream wrapper does)" <<'PY'
assert CMD == "display.brightness" and "notanumber" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-camera-info
amcheck "alias termux-camera-info: cmd camera.info, empty stderr, rc 0" <<'PY'
assert CMD == "camera.info" and OUT.startswith(b'{"ok":true,') and ERR == b"" and RC == 0
PY
python3 - "$GOLDEN_NATIVE/camera-info.json" "$WORK/out" "$WORK/err" "$RC" <<'PY'
import sys
golden = open(sys.argv[1], "rb").read()
out = open(sys.argv[2], "rb").read()
err = open(sys.argv[3], "rb").read()
rc = int(sys.argv[4])
assert out == golden, (out[:80], golden[:80])
assert err == b"" and rc == 0, (err, rc)
PY
check "[baseline-defect] alias termux-camera-info prints exactly the envelope golden (upstream: naked array), stderr empty, rc 0" $?

run_entry termux-clipboard-get
amcheck "[baseline-defect] alias termux-clipboard-get prints the envelope (upstream: raw text)" <<'PY'
assert CMD == "sys.clipboard.get" and OUT.startswith(b'{"ok":true,') and ERR == b"" and RC == 0
PY

run_entry termux-clipboard-set ciao
amcheck "alias termux-clipboard-set: text from args, rc 0" <<'PY'
assert CMD == "sys.clipboard.set"
assert EXTRAS[EXTRAS.index("--es") + 2] == "ciao"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

printf 'from stdin' > "$WORK/stdin"
WORK_STDIN="$WORK/stdin" run_entry termux-clipboard-set
amcheck "alias termux-clipboard-set reads stdin when no args (shim :576-577)" <<'PY'
assert CMD == "sys.clipboard.set"
assert EXTRAS[EXTRAS.index("--es") + 2] == "from stdin"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

printf 'a\n\n' > "$WORK/stdin"
WORK_STDIN="$WORK/stdin" run_entry termux-clipboard-set
amcheck "[baseline-defect] alias clipboard-set stdin: trailing newlines are stripped (upstream stdin is byte-exact)" <<'PY'
assert EXTRAS[EXTRAS.index("--es") + 2] == "a"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

printf 'a\0b' > "$WORK/stdin"
WORK_STDIN="$WORK/stdin" run_entry termux-clipboard-set
amcheck "[baseline-defect] alias clipboard-set stdin: NUL bytes are dropped, bash warns on stderr" <<'PY'
assert EXTRAS[EXTRAS.index("--es") + 2] == "ab"
assert OUT.startswith(b'{"ok":true') and RC == 0
# the exact measured defect: the NUL loss is bash's own command-substitution
# drop, which also emits exactly one warning line on stderr
assert ERR.endswith(b"\n") and ERR.count(b"\n") == 1
assert b"command substitution: ignored null byte in input" in ERR, ERR
PY

printf 'àè 😀' > "$WORK/stdin"
WORK_STDIN="$WORK/stdin" run_entry termux-clipboard-set
amcheck "alias clipboard-set stdin: UTF-8 is preserved" <<'PY'
assert EXTRAS[EXTRAS.index("--es") + 2] == "àè 😀"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-notification -t Title -c Content
amcheck "alias termux-notification -t/-c: title and text extras, rc 0" <<'PY'
assert CMD == "notification.show" and "Content" in EXTRAS and "Title" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

printf 'stdin ignored' > "$WORK/stdin"
WORK_STDIN="$WORK/stdin" run_entry termux-notification -t T -c C
amcheck "alias termux-notification: -c takes precedence, stdin not read" <<'PY'
assert CMD == "notification.show" and "C" in EXTRAS and "stdin ignored" not in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

printf 'body from stdin' > "$WORK/stdin"
WORK_STDIN="$WORK/stdin" run_entry termux-notification -t T
amcheck "alias termux-notification reads stdin when -c is absent (shim :592-594)" <<'PY'
assert CMD == "notification.show" and "body from stdin" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-notification --channel ch --ongoing -i 9 -t T -c C
amcheck "alias termux-notification: id/channel/ongoing extras" <<'PY'
assert CMD == "notification.show" and "9" in EXTRAS and "ch" in EXTRAS and "true" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-notification --priority high
amcheck "alias termux-notification: unsupported option is an explicit error (option subset)" <<'PY'
assert RC == 1 and b"Unsupported termux-notification option" in OUT and ERR == b""
PY

run_entry termux-notification-channel -d ch
amcheck "alias termux-notification-channel -d: delete extras, rc 0" <<'PY'
assert CMD == "notification.channel"
assert EXTRAS[EXTRAS.index("--ez") + 2] == "true"
assert EXTRAS[EXTRAS.index("--es") + 2] == "ch"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-notification-channel ch Name
amcheck "alias termux-notification-channel create: id+name extras, rc 0" <<'PY'
assert CMD == "notification.channel" and "Name" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-notification-remove 42
amcheck "alias termux-notification-remove: id extra, rc 0" <<'PY'
assert CMD == "notification.remove"
assert EXTRAS[EXTRAS.index("--es") + 2] == "42"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-toast -s hi
amcheck "alias termux-toast -s: short flag + text, rc 0" <<'PY'
assert CMD == "ui.toast"
assert EXTRAS[EXTRAS.index("--ez") + 2] == "true"
assert EXTRAS[EXTRAS.index("--es") + 2] == "hi"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

printf 'toast body' > "$WORK/stdin"
WORK_STDIN="$WORK/stdin" run_entry termux-toast
amcheck "alias termux-toast reads stdin with no timeout (upstream uses read -t 3)" <<'PY'
assert CMD == "ui.toast"
assert EXTRAS[EXTRAS.index("--es") + 2] == "toast body"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-toast -b red hi
amcheck "alias termux-toast: -b is not supported (upstream has -b/-c/-g)" <<'PY'
assert RC == 1 and b"Illegal option" in OUT and ERR == b""
PY

run_entry termux-torch on
amcheck "alias termux-torch on: --ez enabled true, rc 0" <<'PY'
assert CMD == "camera.torch" and EXTRAS[EXTRAS.index("--ez") + 2] == "true"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-torch off
amcheck "alias termux-torch off: --ez enabled false, rc 0" <<'PY'
assert CMD == "camera.torch" and EXTRAS[EXTRAS.index("--ez") + 2] == "false"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-torch maybe
amcheck "alias termux-torch rejects bad params (upstream parity)" <<'PY'
assert RC == 1 and b"Usage" in OUT and ERR == b""
PY

run_entry termux-vibrate
amcheck "alias termux-vibrate: default duration_ms 1000" <<'PY'
assert CMD == "sys.vibrate" and EXTRAS[EXTRAS.index("--ei") + 2] == "1000"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-vibrate -d 250
amcheck "alias termux-vibrate -d 250" <<'PY'
assert CMD == "sys.vibrate"
assert EXTRAS[EXTRAS.index("--ei") + 2] == "250"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-vibrate -f
amcheck "alias termux-vibrate: -f (force) is not supported (upstream has it)" <<'PY'
assert RC == 1 and b"Illegal option" in OUT and ERR == b""
PY

run_entry termux-volume
amcheck "alias termux-volume (no args) lists streams" <<'PY'
assert CMD == "audio.volume.list" and OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-volume music 5
amcheck "alias termux-volume music 5: stream+volume extras (already correct)" <<'PY'
assert CMD == "audio.volume"
assert EXTRAS[EXTRAS.index("--es") + 2] == "music"
assert EXTRAS[EXTRAS.index("--ei") + 2] == "5"
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-volume music notanumber
amcheck "alias termux-volume does not validate the volume (upstream wrapper does)" <<'PY'
assert CMD == "audio.volume" and "notanumber" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-tts-engines
amcheck "alias termux-tts-engines: tts.engines, rc 0" <<'PY'
assert CMD == "tts.engines" and OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-tts-speak -p 1.5 -r 2.0 -e eng ciao
amcheck "alias termux-tts-speak: pitch/rate/engine options + text, rc 0" <<'PY'
assert CMD == "tts.speak"
assert EXTRAS[EXTRAS.index("--ef") + 2] == "1.5"
assert "2.0" in EXTRAS and "eng" in EXTRAS and "ciao" in EXTRAS
assert OUT.startswith(b'{"ok":true') and ERR == b"" and RC == 0
PY

run_entry termux-wifi-connectioninfo
amcheck "[baseline-defect] alias termux-wifi-connectioninfo prints the envelope" <<'PY'
assert CMD == "wifi.connection" and OUT.startswith(b'{"ok":true,') and ERR == b"" and RC == 0
PY

# ======================================================= byte-exact stdout
# The envelope printed by the broadcast entries must be exactly the golden
# line: data + one newline, no extra bytes (compared on files, not through
# command substitution), with empty stderr and rc 0.
run_entry termux-battery-status
python3 - "$GOLDEN_NATIVE/battery.json" "$WORK/out" "$WORK/err" "$RC" <<'PY'
import sys
golden = open(sys.argv[1], "rb").read()
out = open(sys.argv[2], "rb").read()
err = open(sys.argv[3], "rb").read()
rc = int(sys.argv[4])
assert out == golden, (out[:80], golden[:80])
assert err == b"" and rc == 0, (err, rc)
PY
check "alias battery stdout is byte-exact: envelope golden + one newline, stderr empty, rc 0" $?

run_entry termux-api BatteryStatus
python3 - "$GOLDEN_COMPAT/BatteryStatus.json" "$WORK/out" "$WORK/err" "$RC" <<'PY'
import sys
golden = open(sys.argv[1], "rb").read()
out = open(sys.argv[2], "rb").read()
err = open(sys.argv[3], "rb").read()
rc = int(sys.argv[4])
assert out != golden and out.startswith(b'{"ok":true,'), out[:80]
assert err == b"" and rc == 0, (err, rc)
PY
check "[baseline-defect] compat BatteryStatus stdout differs from the upstream naked payload (envelope still printed)" $?

# ===================================================== golden contract
# Every golden is validated against the schema fixed at the pins (see
# GOLDEN_CONTRACT.md): key names, casing, types, mandatory fields, value
# domains, emit order and the exact serialization (2-space indent, trailing
# newline only where the pin's writer produces one). A wrong key/casing/type
# or a missing mandatory field fails here — not merely "is valid JSON".
python3 - "$GOLDEN_COMPAT" "$GOLDEN_NATIVE" <<'PY'
import json, os, sys

compat_dir, native_dir = sys.argv[1], sys.argv[2]

def read(d, n):
    with open(os.path.join(d, n), "rb") as f:
        return f.read()

def jload(d, n):
    return json.loads(read(d, n))

def kind(v):
    if isinstance(v, bool): return "bool"
    if isinstance(v, int): return "int"
    if isinstance(v, float): return "float"
    if isinstance(v, str): return "string"
    if isinstance(v, list): return "array"
    if isinstance(v, dict): return "object"
    return "null"

def field(obj, key, want, ctx):
    assert kind(obj.get(key)) == want, "%s: %s must be %s, got %r" % (ctx, key, want, obj.get(key))

def exact_keys(obj, keys, ctx):
    assert set(obj) == set(keys), "%s: key set %s != %s" % (ctx, sorted(obj), sorted(keys))

def emit_order(obj, pin_order, ctx):
    extra = set(obj) - set(pin_order)
    assert not extra, "%s: unknown keys %s" % (ctx, sorted(extra))
    positions = [pin_order.index(k) for k in obj]
    assert positions == sorted(positions), "%s: key order %s violates the pin emit order" % (ctx, list(obj))

# Serialization: android JsonWriter(setIndent("  ")) == json.dumps(indent=2).
# GOLDEN_CONTRACT.md §1 decides the trailing newline per payload.
COMPACT_NL = {
    "AudioInfo.json", "BatteryStatus.json", "CameraInfo.json",
    "WifiConnectionInfo.json", "WifiConnectionInfo-unconnected.json",
}
for name in sorted(os.listdir(compat_dir)):
    if not name.endswith(".json"):
        continue
    data = read(compat_dir, name)
    obj = json.loads(data)
    expected = json.dumps(obj, indent=2).encode() + (b"\n" if name in COMPACT_NL else b"")
    assert data == expected, "%s: not the pinned 2-space-indent serialization" % name

# --- AudioInfo (AudioAPI.java:68-88)
a = jload(compat_dir, "AudioInfo.json")
exact_keys(a, ["PROPERTY_OUTPUT_SAMPLE_RATE", "PROPERTY_OUTPUT_FRAMES_PER_BUFFER",
               "AUDIOTRACK_SAMPLE_RATE", "AUDIOTRACK_BUFFER_SIZE_IN_FRAMES",
               "BLUETOOTH_A2DP_IS_ON", "WIREDHEADSET_IS_CONNECTED"], "AudioInfo")
field(a, "PROPERTY_OUTPUT_SAMPLE_RATE", "string", "AudioInfo")
field(a, "PROPERTY_OUTPUT_FRAMES_PER_BUFFER", "string", "AudioInfo")
field(a, "AUDIOTRACK_SAMPLE_RATE", "int", "AudioInfo")
field(a, "AUDIOTRACK_BUFFER_SIZE_IN_FRAMES", "int", "AudioInfo")
field(a, "BLUETOOTH_A2DP_IS_ON", "bool", "AudioInfo")
field(a, "WIREDHEADSET_IS_CONNECTED", "bool", "AudioInfo")

# --- BatteryStatus (BatteryStatusAPI.java:149-168)
b = jload(compat_dir, "BatteryStatus.json")
pin_order = ["present", "technology", "health", "plugged", "status", "temperature",
             "voltage", "current", "current_average", "percentage", "level", "scale",
             "charge_counter", "energy", "cycle"]
emit_order(b, pin_order, "BatteryStatus")
for k, t in [("present", "bool"), ("health", "string"), ("plugged", "string"),
             ("status", "string"), ("temperature", "float"), ("voltage", "int"),
             ("level", "int"), ("scale", "int")]:
    assert k in b, "BatteryStatus: mandatory %s missing" % k
    field(b, k, t, "BatteryStatus")
for k, t in [("technology", "string"), ("current", "int"), ("current_average", "int"),
             ("percentage", "int"), ("charge_counter", "int"), ("energy", "int"), ("cycle", "int")]:
    if k in b:
        field(b, k, t, "BatteryStatus")
assert b["health"] in {"COLD", "DEAD", "GOOD", "OVERHEAT", "OVER_VOLTAGE", "UNKNOWN",
                       "UNSPECIFIED_FAILURE"}, b["health"]
assert b["plugged"] in {"UNPLUGGED", "PLUGGED_AC", "PLUGGED_DOCK", "PLUGGED_USB",
                        "PLUGGED_WIRELESS"} or b["plugged"].startswith("PLUGGED_"), b["plugged"]
assert b["status"] in {"CHARGING", "DISCHARGING", "FULL", "NOT_CHARGING", "UNKNOWN"}, b["status"]

# --- CameraInfo (CameraInfoAPI.java:31-145): an array, lowercase facing,
#     every per-camera field the handler always emits.
AE_MODES = {"CONTROL_AE_MODE_OFF", "CONTROL_AE_MODE_ON", "CONTROL_AE_MODE_ON_ALWAYS_FLASH",
            "CONTROL_AE_MODE_ON_AUTO_FLASH", "CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE",
            "CONTROL_AE_MODE_ON_EXTERNAL_FLASH"}
CAPABILITIES = {"backward_compatible", "burst_capture", "constrained_high_speed_video",
                "depth_output", "logical_multi_camera", "manual_post_processing",
                "manual_sensor", "monochrome", "motion_tracking", "private_reprocessing",
                "raw", "read_sensor_settings", "yuv_reprocessing"}
cams = jload(compat_dir, "CameraInfo.json")
assert isinstance(cams, list) and cams, "CameraInfo: root must be a non-empty array"
for i, cam in enumerate(cams):
    ctx = "CameraInfo[%d]" % i
    exact_keys(cam, ["id", "facing", "jpeg_output_sizes", "focal_lengths",
                     "auto_exposure_modes", "physical_size", "capabilities"], ctx)
    field(cam, "id", "string", ctx)
    if kind(cam["facing"]) == "string":
        assert cam["facing"] in {"back", "front"}, "%s: facing must be lowercase back/front, got %r" % (ctx, cam["facing"])
    else:
        assert kind(cam["facing"]) == "int", ctx + ": facing must be string or int"
    sizes = cam["jpeg_output_sizes"]
    assert kind(sizes) == "array" and sizes, ctx + ": jpeg_output_sizes must be a non-empty array"
    for s in sizes:
        exact_keys(s, ["width", "height"], ctx + ".jpeg_output_sizes[]")
        field(s, "width", "int", ctx); field(s, "height", "int", ctx)
    fl = cam["focal_lengths"]
    assert kind(fl) == "array" and fl and all(kind(x) in {"int", "float"} for x in fl), ctx
    ae = cam["auto_exposure_modes"]
    assert kind(ae) == "array" and ae, ctx
    for m in ae:
        assert (kind(m) == "string" and m in AE_MODES) or kind(m) == "int", "%s: bad ae mode %r" % (ctx, m)
    ps = cam["physical_size"]
    exact_keys(ps, ["width", "height"], ctx + ".physical_size")
    field(ps, "width", "float", ctx); field(ps, "height", "float", ctx)
    caps = cam["capabilities"]
    assert kind(caps) == "array" and caps, ctx
    for c in caps:
        assert (kind(c) == "string" and c in CAPABILITIES) or kind(c) == "int", "%s: bad capability %r" % (ctx, c)

# --- TextToSpeech engines (TextToSpeechAPI.java:124-140)
engines = jload(compat_dir, "TextToSpeech-LIST_AVAILABLE.json")
assert isinstance(engines, list) and engines, "TTS LIST_AVAILABLE: non-empty array"
for e in engines:
    exact_keys(e, ["name", "label", "default"], "TTS engine entry")
    field(e, "name", "string", "TTS engine entry")
    field(e, "label", "string", "TTS engine entry")
    field(e, "default", "bool", "TTS engine entry")

# --- Volume list (VolumeAPI.java:20-27,95-116): fixed stream order
vols = jload(compat_dir, "Volume-list.json")
assert isinstance(vols, list), "Volume-list: root must be an array"
assert [v["stream"] for v in vols] == ["alarm", "music", "notification", "ring", "system", "call"], \
    "Volume-list: stream order must follow the pin streamMap"
for v in vols:
    exact_keys(v, ["stream", "volume", "max_volume"], "Volume-list entry")
    field(v, "stream", "string", "Volume-list entry")
    field(v, "volume", "int", "Volume-list entry")
    field(v, "max_volume", "int", "Volume-list entry")

# --- WifiConnectionInfo (WifiAPI.java:31-48): connected branch, full field set
w = jload(compat_dir, "WifiConnectionInfo.json")
assert list(w) == ["bssid", "frequency_mhz", "ip", "link_speed_mbps", "mac_address",
                   "network_id", "rssi", "ssid", "ssid_hidden", "supplicant_state"], \
    "WifiConnectionInfo: key set/order must match the pin emit order, got %s" % list(w)
for k, t in [("bssid", "string"), ("frequency_mhz", "int"), ("ip", "string"),
             ("link_speed_mbps", "int"), ("mac_address", "string"), ("network_id", "int"),
             ("rssi", "int"), ("ssid", "string"), ("ssid_hidden", "bool"),
             ("supplicant_state", "string")]:
    field(w, k, t, "WifiConnectionInfo")

# --- WifiConnectionInfo null-info branch (WifiAPI.java:34-35)
u = jload(compat_dir, "WifiConnectionInfo-unconnected.json")
assert u == {"API_ERROR": "No current connection"}, u

# --- plain-text and empty targets (NotificationAPI.java:99,109; §2 empty list)
assert read(compat_dir, "NotificationChannel-create.txt") == \
    b'Created channel with id "synthetic-channel" and name "Synthetic".\n'
assert read(compat_dir, "NotificationChannel-delete.txt") == \
    b'Deleted channel with id "synthetic-channel".\n'
for name in ("Brightness-empty.txt", "Clipboard-set-empty.txt", "Notification-empty.txt",
             "NotificationRemove-empty.txt", "TextToSpeech-speak-empty.txt",
             "Toast-empty.txt", "Torch-empty.txt", "Vibrate-empty.txt",
             "Volume-set-empty.txt"):
    assert read(compat_dir, name) == b"", "%s must be zero bytes" % name
clip = read(compat_dir, "Clipboard-get.txt")
assert clip and not clip.endswith(b"\n") and not clip.startswith(b'{"ok"'), \
    "Clipboard-get: raw text, no trailing newline, not an envelope"

# --- native envelopes vs the app source (TermuxAiSocketServer at the baseline)
NATIVE_SPECS = {
    "battery.json": {"level": "int", "charging": "bool", "current_ua": "int", "charge_uah": "int"},
    "clipboard-get.json": {"text": "string"},
    "clipboard-set.json": {"status": "string"},
    "vibrate.json": {"duration_ms": "int"},
    "notification-show.json": {"status": "string", "id": "string", "channel": "string"},
    "notification-remove.json": {"status": "string", "id": "string"},
    "toast.json": {"status": "string", "text": "string"},
    "audio-info.json": {"sample_rate": "string", "frames_per_buffer": "string",
                        "bluetooth_a2dp_on": "bool", "wired_headset_on": "bool",
                        "speakerphone_on": "bool", "music_active": "bool"},
    "volume-set.json": {"stream": "string", "volume": "int", "max_volume": "int"},
    "brightness.json": {"mode": "string", "brightness": "int"},
    "torch.json": {"status": "string", "camera": "string"},
    "wifi-connection.json": {"ssid": "string", "bssid": "string", "rssi": "int",
                             "link_speed_mbps": "int", "network_id": "int",
                             "supplicant_state": "string"},
    "tts-engines.json": {"engines": "array"},
    "tts-speak.json": {"queued": "bool", "chars": "int"},
}
for name in sorted(os.listdir(native_dir)):
    data = read(native_dir, name)
    env = json.loads(data)
    ctx = "native/" + name
    # stub route payloads must stay single-line: one JSON line + one newline
    assert data.count(b"\n") == 1 and data.endswith(b"\n"), ctx + ": must be one JSON line"
    assert kind(env.get("ok")) == "bool", ctx
    if name.startswith("error-"):
        assert env["ok"] is False and kind(env.get("error")) == "string", ctx
        continue
    assert env["ok"] is True and kind(env.get("data")) == "object", ctx
    d = env["data"]
    if name == "volume-list.json":
        exact_keys(d, {"streams"}, ctx)
        streams = d["streams"]
        assert [s["stream"] for s in streams] == ["alarm", "music", "notification", "ring", "system", "call"], ctx
        for s in streams:
            exact_keys(s, {"stream", "volume", "max_volume"}, ctx + ".streams[]")
            field(s, "stream", "string", ctx); field(s, "volume", "int", ctx); field(s, "max_volume", "int", ctx)
        continue
    if name == "notification-channel.json":
        assert d.get("status") in {"created", "deleted", "ignored"}, ctx
        if d["status"] == "created":
            exact_keys(d, {"status", "id", "name"}, ctx)
        elif d["status"] == "deleted":
            exact_keys(d, {"status", "id"}, ctx)
        else:
            exact_keys(d, {"status", "reason"}, ctx)
        continue
    if name == "camera-info.json":
        exact_keys(d, {"cameras"}, ctx)
        for c in d["cameras"]:
            exact_keys(c, {"id", "facing", "flash_available"}, ctx + ".cameras[]")
            field(c, "id", "string", ctx)
            assert c["facing"] in {"back", "front", "unknown"}, ctx + ": facing"
            field(c, "flash_available", "bool", ctx)
        continue
    if name == "tts-engines.json":
        for e in d["engines"]:
            exact_keys(e, {"name", "label", "default"}, ctx + ".engines[]")
            field(e, "name", "string", ctx); field(e, "label", "string", ctx); field(e, "default", "bool", ctx)
        continue
    if name == "brightness.json" and "brightness" not in d:
        exact_keys(d, {"mode"}, ctx + " (auto branch)")
        continue
    spec = NATIVE_SPECS[name]
    exact_keys(d, spec, ctx)
    for k, t in spec.items():
        field(d, k, t, ctx)
print("golden contract: compat + native schemas at the pin all hold")
PY
check "golden contract: every golden matches the pinned schema (keys, casing, types, order, serialization)" $?

# ====================================================== pin integrity
python3 - "$PIN_DOC" "$PIN_DIR" <<'PY'
import hashlib, os, sys
doc, pin_dir = sys.argv[1], sys.argv[2]
expected = {}
for line in open(doc, encoding="utf-8"):
    parts = line.split()
    if len(parts) == 2 and len(parts[0]) == 64 and parts[1].endswith(".in"):
        expected[parts[1]] = parts[0]
assert expected, "no sha256 entries parsed from UPSTREAM_PIN.md"
for name, digest in sorted(expected.items()):
    data = open(os.path.join(pin_dir, name), "rb").read()
    assert hashlib.sha256(data).hexdigest() == digest, name
assert len(os.listdir(pin_dir)) == len(expected), "vendored file set differs from the pin table"
PY
check "vendored upstream wrappers still match the pinned sha256 table" $?

# ================================================== wrapper ownership
python3 - "$INSTALLER_SRC" "$PIN_DIR" <<'PY'
import os, re, sys
src, pin_dir = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
block = re.search(r"MANAGED_HELPERS = \{(.*?)\};", text, re.S).group(1)
managed = set(re.findall(r'"([^"]+)"', block))
upstream_wrappers = {f[:-3] for f in os.listdir(pin_dir) if f.endswith(".in")}
collisions = sorted(managed & upstream_wrappers)
assert len(collisions) == 16, collisions
assert 'new File(libexecDir, "termux-api")' in text
# no ownership gate anywhere in the installer source
for marker in ("dpkg", "getOwnerPackage", "sha256", "MessageDigest", "rename"):
    assert marker not in text, marker
print("collisions:", ", ".join(collisions))
PY
check "[baseline-defect] installer overwrites all 16 upstream wrapper names + libexec/termux-api with no ownership gate" $?

# ================================================================ summary
printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
