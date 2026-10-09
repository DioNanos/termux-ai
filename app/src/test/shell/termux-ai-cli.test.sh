#!/usr/bin/env bash
# Shell tests for the termux-ai CLI: a stub unix socket stands in for the app.
# Run: bash app/src/test/shell/termux-ai-cli.test.sh   (needs bash 4+, python3, nc)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI="${TERMUX_AI_CLI:-$HERE/../../main/assets/termux-ai}"
STUB="$HERE/stub_socket_server.py"
WORK="$(mktemp -d)"
ROUTES="$WORK/routes.jsonl"
STUB_PID=""
PASS=0
FAIL=0

cleanup() { stop_stub; rm -rf "$WORK"; }
trap cleanup EXIT

stop_stub() {
  if [ -n "$STUB_PID" ]; then kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; fi
  STUB_PID=""
}

# start_stub MODE [ARG]: fresh socket, empty request log, empty broadcast log.
start_stub() {
  stop_stub
  : > "$WORK/requests.log"
  : > "$WORK/broadcast.log"
  rm -f "$WORK/ai.sock"
  python3 "$STUB" "$WORK/ai.sock" "$WORK/requests.log" "$@" &
  STUB_PID=$!
  local i
  for i in $(seq 1 100); do [ -S "$WORK/ai.sock" ] && return 0; sleep 0.05; done
  echo "stub did not start" >&2; exit 1
}

# A fake termux-am records every broadcast: a replay would show up here.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/termux-am" <<'AMEOF'
#!/usr/bin/env bash
echo "$*" >> "$TEST_BROADCAST_LOG"
echo 'Broadcast completed: result=0, data="{\"ok\":true,\"data\":{\"via\":\"broadcast\"}}"'
AMEOF
chmod +x "$WORK/bin/termux-am"
export TEST_BROADCAST_LOG="$WORK/broadcast.log"

# run_cli ARGS...: stdout in $WORK/out, stderr in $WORK/err, exit code in RC.
run_cli() {
  PATH="$WORK/bin:$PATH" TERMUX_AI_SOCKET="$WORK/ai.sock" bash "$CLI" "$@" > "$WORK/out" 2> "$WORK/err" < /dev/null
  RC=$?
}

ok()   { PASS=$((PASS + 1)); printf 'ok   %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf 'FAIL %s\n' "$1"; [ -z "${2:-}" ] || printf '     %s\n' "$2"; }
check() { # check NAME CONDITION-EXIT-CODE [DETAIL]
  if [ "$2" -eq 0 ]; then ok "$1"; else bad "$1" "${3:-}"; fi
}
requests() { grep -c '"cmd":"aicore.generate"' "$WORK/requests.log"; }
downloads() { grep -c '"cmd":"aicore.download"' "$WORK/requests.log"; }
broadcasts() { wc -l < "$WORK/broadcast.log" | tr -d ' '; }

# ---------------------------------------------------------------- parser unit
# shellcheck disable=SC1090
source "$CLI"
set +e +u +o pipefail  # the CLI sets strict mode; the test harness handles failures itself

parses() { json_parse "$1"; }
parser_valid=(
  '{"ok":true}'
  '{"ok":true,"data":{"text":"a \"q\" \\ \n è 😀","n":-1.5e3,"l":[1,2,{"x":null}]}}'
  '  {"ok":false,"error":"x"}  '
  '[]'
)
for doc in "${parser_valid[@]}"; do
  parses "$doc"; check "parser accepts: ${doc:0:40}" $? "$JP_ERR"
done
# paths are unambiguous: a key is never confused with a nested path, an empty key never reaches the root
parses '{"a.b":1,"a":{"b":2}}'
[ "${J[a%2Eb]}" = "1" ] && [ "${J[a.b]}" = "2" ]; check "parser keeps the key a.b apart from a nested a -> b" $?
parses '{"ok":false,"":{"ok":true}}'
[ "${J[ok]}" = "false" ] && [ "${JT[ok]}" = "boolean" ]; check "parser: an empty key cannot overwrite a root field" $?
parses '{"x":[1],"x.0":2}'
[ "${J[x.0]}" = "1" ] && [ "${J[x%2E0]}" = "2" ]; check "parser keeps an array element apart from a key that looks like its path" $?
parses '{"%":1,"":2}'
[ "${J[%25]}" = "1" ] && [ "${J[%]}" = "2" ]; check "parser keeps the key % apart from the empty key" $?
parses '{"a":1,"a":2}'; [ $? -ne 0 ]; check "parser rejects a duplicate key" $?
parses '{"a":{"b":1,"b":2}}'; [ $? -ne 0 ]; check "parser rejects a duplicate key in a nested object" $?
parses '{"a":{},"a":1}'; [ $? -ne 0 ]; check "parser rejects a key repeated after an object" $?
parses '[1,{"a":1}]'
[ "${JSON_ROOT_TYPE}" = "array" ]; check "parser reports the type of the root value" $?
parses '{"a":1}'
[ "${JSON_ROOT_TYPE}" = "object" ]; check "parser reports an object root" $?

parser_invalid=(
  '' '   ' '{' '{"ok":tru' '{"ok":true,}' '[1,]' '{"a":1} x' '"abc' 'nan' '{"a":"\ud800"}'
  '{"a":"\udc00"}' '{"a":"\ux"}' '{"a":01}' '{"a":"line
break"}' '<html>' '{"a" 1}' '{"a":1 "b":2}'
)
for doc in "${parser_invalid[@]}"; do
  parses "$doc"; rc=$?
  check "parser rejects: $(printf '%s' "${doc:0:30}" | tr '\n' ' ')" "$([ "$rc" -ne 0 ] && echo 0 || echo 1)"
done
deep="$(printf '[%.0s' $(seq 1 40))$(printf ']%.0s' $(seq 1 40))"
parses "$deep"; check "parser rejects nesting deeper than 32" "$([ $? -ne 0 ] && echo 0 || echo 1)"
parses '{"ok":true,"data":{"text":"a\nb😀"}}'
[ "${J[data.text]}" = $'a\nb\xf0\x9f\x98\x80' ]; check "parser decodes escapes and surrogate pairs to UTF-8" $?
[ "${JT[ok]}" = boolean ] && [ "${J[ok]}" = true ]; check "parser records type and value of ok" $?

# ------------------------------------------------------ prompt serialization
start_stub echo
prompts=(
  $'one line'
  $'first line\nsecond line\n\nfourth'
  $'tab\there and "quotes" and back\\slash and \\n literal'
  $'accents: àèìòù ÀÈÌÒÙ, emoji 😀, 日本語'
  $'control \x01 \x07 \x1b \x1f chars'
  $'trailing backslash \\'
  '{"cmd":"aicore.download"}'
)
n=0
for prompt in "${prompts[@]}"; do
  n=$((n + 1))
  : > "$WORK/requests.log"
  run_cli aicore generate -- "$prompt"
  printf '%s\n' "$prompt" > "$WORK/expected"
  cmp -s "$WORK/out" "$WORK/expected"
  check "generate round-trips prompt $n byte for byte (via the app echo)" $? "rc=$RC"
  python3 - "$WORK/requests.log" "$prompt" <<'PY'
import json, sys
line = open(sys.argv[1], "rb").read().split(b"\n")[0].decode("utf-8")
request = json.loads(line)  # a single valid JSON frame
assert request["cmd"] == "aicore.generate", request
assert request["args"]["prompt"] == sys.argv[2], (request["args"]["prompt"], sys.argv[2])
PY
  check "payload $n is one valid JSON frame carrying the exact prompt" $?
done
start_stub echo-raw
run_cli aicore generate -- $'raw utf-8 😀 è'
[ "$(cat "$WORK/out")" = $'raw utf-8 😀 è' ]; check "raw UTF-8 response text is printed unchanged" $?

# prompt on stdin
printf 'from stdin\nsecond' | PATH="$WORK/bin:$PATH" TERMUX_AI_SOCKET="$WORK/ai.sock" bash "$CLI" aicore generate > "$WORK/out" 2> "$WORK/err"
[ "$(cat "$WORK/out")" = $'from stdin\nsecond' ]; check "prompt read from stdin" $?

# ------------------------------------------------------------ application errors
cat > "$WORK/err_resp" <<'EOF'
{"ok":false,"error":"Read failed: EAGAIN"}
EOF
start_stub reply "$WORK/err_resp"
run_cli aicore generate hello
[ "$RC" -ne 0 ]; check "ok:false gives rc != 0 in text mode" $? "rc=$RC"
[ ! -s "$WORK/out" ] && grep -q "Read failed: EAGAIN" "$WORK/err"; check "text mode: nothing on stdout, the error on stderr" $?
run_cli aicore generate --json hello
[ "$RC" -ne 0 ]; check "ok:false gives rc != 0 in --json mode" $? "rc=$RC"
grep -q '"ok":false' "$WORK/out" && grep -q "Read failed: EAGAIN" "$WORK/out"; check "--json mode: the error JSON on stdout" $?

cat > "$WORK/err_named" <<'EOF'
{"ok":false,"error":"Generation failed","error_name":"BUSY","error_code":9,"retry_delay_ms":2500}
EOF
start_stub reply "$WORK/err_named"
run_cli aicore generate hello
grep -q "BUSY" "$WORK/err" && grep -q "code 9" "$WORK/err" && grep -q "retry after 2500 ms" "$WORK/err"; check "AICore error name, code and retry delay are shown" $?

# response -> the reason the CLI must give
variants=(
  '|closed the connection without a response'
  '   |closed the connection without a response'
  '{"ok":tru|malformed response'
  '<html>oops</html>|malformed response'
  '[]|no ok field'
  '{"data":{"text":"x"}}|no ok field'
  '{"ok":"yes"}|no ok field'
  '{"ok":true,"data":{"text":"x"}} trailing|malformed response'
  '{"ok":true}|no data.text'
  '{"ok":false,"error":"must fail","":{"ok":true},"data":{"text":"bypass"}}|must fail'
  '{"ok":true,"data.text":"impostor"}|no data.text'
  '{"ok":true,"data":{"text":"first"},"data":{}}|malformed response'
  '{"ok":true,"data":{"text":"a","text":"b"}}|malformed response'
  '{"ok":false,"ok":true,"data":{"text":"x"}}|malformed response'
  '{"ok":true,"data":"x"}|no data.text'
  '{"ok":true,"data":["text"]}|no data.text'
  '{"ok":true,"data":null}|no data.text'
  '{"ok":true,"data":{"":"x"}}|no data.text'
  '{"ok":true,"":{"data":{"text":"x"}}}|no data.text'
  '{"ok":true,"data":{"text":1}}|no data.text'
  '[{"ok":true,"data":{"text":"x"}}]|no ok field'
)
for entry in "${variants[@]}"; do
  variant="${entry%%|*}"; reason="${entry#*|}"
  printf '%s' "$variant" > "$WORK/resp"
  start_stub reply "$WORK/resp"
  run_cli aicore generate hello
  [ "$RC" -ne 0 ] && [ ! -s "$WORK/out" ] && grep -q "$reason" "$WORK/err"; st=$?; check "text mode rejects response '$(printf '%s' "${variant:-<empty>}" | head -c 30)' ($reason)" $st "rc=$RC err=$(cat "$WORK/err")"
  run_cli aicore generate --json hello
  [ "$RC" -ne 0 ] && grep -q '"ok":false' "$WORK/out" && grep -q "$reason" "$WORK/out"; st=$?; check "--json mode rejects response '$(printf '%s' "${variant:-<empty>}" | head -c 30)'" $st "rc=$RC out=$(cat "$WORK/out")"
done

# handle_response on its own: an empty or blank response is never data
for blank in '' '   ' $'\n'; do
  ( handle_response text generate "$blank" ) > "$WORK/out" 2> "$WORK/err"; rc=$?
  [ "$rc" -ne 0 ] && grep -q "empty response" "$WORK/err"; st=$?; check "handle_response rejects the blank response '$(printf '%s' "$blank" | tr '\n' 'n')'" $st
done

printf '%s\n' '{"ok":true,"data":{"text":"","finish_reason":"stop"}}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli aicore generate hello
[ "$RC" -ne 0 ] && grep -q "empty completion" "$WORK/err"; check "an empty completion is an error in text mode" $?
run_cli aicore generate --json hello
[ "$RC" -eq 0 ] && grep -q '"ok":true' "$WORK/out"; check "an empty completion in --json mode is passed through" $?

# keys that contain dots or are empty are kept apart from the paths they look like
printf '%s\n' '{"ok":true,"data":{"text":"fine","":{"text":"x"},"a.b":"1","a":{"b":"2"}}}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli aicore generate hello
[ "$RC" -eq 0 ] && [ "$(cat "$WORK/out")" = "fine" ]; check "dotted and empty keys inside data do not change data.text" $? "rc=$RC out=$(cat "$WORK/out")"

printf '%s\n' '{"ok":true,"data":{"text":"he said \"hi\"\nline2\\end è","finish_reason":"stop"}}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli aicore generate hello
[ "$RC" -eq 0 ] && [ "$(cat "$WORK/out")" = $'he said "hi"\nline2\\end \xc3\xa8' ]; check "ok:true prints data.text with escapes decoded" $?

# ------------------------------------------------ untrusted text on a terminal
# The model's text can carry escape sequences. On a terminal they are shown as
# "?"; on a pipe the text is unchanged.
printf '%s\n' '{"ok":true,"data":{"text":"red \u001b[31mx\u001b[0m title \u001b]0;pwned\u0007 clip \u001b]52;c;Zm9v\u0007 c1 \u009b31m del \u007f ok\ttab\nline2","finish_reason":"stop"}}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli aicore generate hello
LC_ALL=C grep -q $'\033' "$WORK/out" && LC_ALL=C grep -q $'\a' "$WORK/out"; check "on a pipe the answer is unchanged (escape sequences kept)" $?
python3 - "$CLI" "$WORK/ai.sock" <<'PY' > "$WORK/pty.out"
import os, pty, subprocess, sys
cli, sock = sys.argv[1], sys.argv[2]
master, slave = pty.openpty()
env = dict(os.environ, TERMUX_AI_SOCKET=sock)
proc = subprocess.Popen(["bash", cli, "aicore", "generate", "hello"], stdout=slave, stderr=slave, stdin=subprocess.DEVNULL, env=env)
os.close(slave)
data = b""
while True:
    try:
        chunk = os.read(master, 4096)
    except OSError:
        break
    if not chunk:
        break
    data += chunk
proc.wait()
sys.stdout.buffer.write(data)
PY
st=$?
[ "$st" -eq 0 ] && [ -s "$WORK/pty.out" ]; check "the CLI ran on a pseudo-terminal" $?
LC_ALL=C grep -q -e $'\033' -e $'\a' -e $'\177' -e $'\302\233' "$WORK/pty.out"; st=$?
check "on a terminal no ESC, BEL, DEL or C1 byte reaches the screen" "$([ "$st" -ne 0 ] && echo 0 || echo 1)" "$(od -c "$WORK/pty.out" | head -3)"
grep -q "red ?\[31mx?\[0m" "$WORK/pty.out" && grep -q "ok	tab" "$WORK/pty.out" && grep -q "line2" "$WORK/pty.out"; check "on a terminal the readable text, tabs and newlines are kept" $?
printf '%s\n' '{"ok":false,"error":"bad \u001b]0;pwned\u0007 news"}' > "$WORK/resp"
start_stub reply "$WORK/resp"
python3 - "$CLI" "$WORK/ai.sock" <<'PY' > "$WORK/pty.err"
import os, pty, subprocess, sys
cli, sock = sys.argv[1], sys.argv[2]
master, slave = pty.openpty()
proc = subprocess.Popen(["bash", cli, "aicore", "generate", "hello"], stdout=subprocess.DEVNULL, stderr=slave, stdin=subprocess.DEVNULL, env=dict(os.environ, TERMUX_AI_SOCKET=sock))
os.close(slave)
data = b""
while True:
    try:
        chunk = os.read(master, 4096)
    except OSError:
        break
    if not chunk:
        break
    data += chunk
proc.wait()
sys.stdout.buffer.write(data)
PY
LC_ALL=C grep -q -e $'\033' -e $'\a' "$WORK/pty.err"; st=$?
check "on a terminal an error message cannot carry escape sequences either" "$([ "$st" -ne 0 ] && grep -q "bad" "$WORK/pty.err" && echo 0 || echo 1)"

# ------------------------------------------------------- no replay, no fallback
start_stub drop
run_cli aicore generate hello
check "broken transport after send: rc is the transport code (4)" "$([ "$RC" -eq 4 ] && echo 0 || echo 1)" "rc=$RC"
check "broken transport after send: the app got exactly one generate" "$([ "$(requests)" -eq 1 ] && echo 0 || echo 1)" "requests=$(requests)"
check "broken transport after send: zero broadcasts" "$([ "$(broadcasts)" -eq 0 ] && echo 0 || echo 1)" "broadcasts=$(broadcasts)"
grep -q "not retried" "$WORK/err"; check "broken transport after send: the error says it was not retried" $?
run_cli aicore download
check "download after a broken transport: rc 4, one request, zero broadcasts" "$([ "$RC" -eq 4 ] && [ "$(downloads)" -eq 1 ] && [ "$(broadcasts)" -eq 0 ] && echo 0 || echo 1)" "rc=$RC downloads=$(downloads) broadcasts=$(broadcasts)"

stop_stub
rm -f "$WORK/ai.sock"; : > "$WORK/broadcast.log"
run_cli aicore generate hello
check "socket absent: generate rc is the no-socket code (3)" "$([ "$RC" -eq 3 ] && echo 0 || echo 1)" "rc=$RC"
check "socket absent: generate does not fall back to the broadcast" "$([ "$(broadcasts)" -eq 0 ] && echo 0 || echo 1)"
grep -q "not available" "$WORK/err"; check "socket absent: the error is distinct and readable" $?
run_cli aicore download
check "socket absent: download rc 3 and zero broadcasts" "$([ "$RC" -eq 3 ] && [ "$(broadcasts)" -eq 0 ] && echo 0 || echo 1)" "rc=$RC"
run_cli aicore info
check "socket absent: the read-only info may fall back to the broadcast" "$([ "$RC" -eq 0 ] && [ "$(broadcasts)" -eq 1 ] && echo 0 || echo 1)" "rc=$RC broadcasts=$(broadcasts)"

# ----------------------------------------------------------- flags and selection
start_stub echo
run_cli aicore generate --stage preview --preference fast --top-k 40 --max-tokens 64 --temperature 0 hello
python3 - "$WORK/requests.log" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1], "rb").read().split(b"\n")[0])["args"]
assert args["stage"] == "preview" and args["preference"] == "fast", args
assert args["top_k"] == 40 and args["max_tokens"] == 64 and args["temperature"] == 0, args
assert args["prompt"] == "hello", args
PY
check "stage, preference, top-k, max-tokens and temperature reach the request" $?
: > "$WORK/requests.log"
run_cli aicore generate hello
python3 - "$WORK/requests.log" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1], "rb").read().split(b"\n")[0])["args"]
assert "stage" not in args and "preference" not in args and "top_k" not in args, args
PY
check "without flags the request carries no stage, preference or top-k" $?
: > "$WORK/requests.log"
for badflags in "--stage nightly" "--stage Stable" "--preference medium" "--max-tokens abc" "--max-tokens 0" "--temperature -1" "--temperature 1.5" "--temperature 1.01" "--temperature .5" "--top-k 0" "--top-k x" "--stage"; do
  : > "$WORK/requests.log"
  # shellcheck disable=SC2086
  run_cli aicore generate $badflags hello
  check "invalid '$badflags' is a usage error (rc 2) and sends nothing" "$([ "$RC" -eq 2 ] && [ "$(requests)" -eq 0 ] && [ ! -s "$WORK/out" ] && echo 0 || echo 1)" "rc=$RC requests=$(requests)"
done
: > "$WORK/requests.log"
run_cli aicore info --stage nightly
check "info with an unknown stage is a usage error" "$([ "$RC" -eq 2 ] && echo 0 || echo 1)" "rc=$RC"
run_cli aicore models --stage preview
check "models takes no options" "$([ "$RC" -eq 2 ] && echo 0 || echo 1)" "rc=$RC"
run_cli aicore download --preference slow
check "download with an unknown preference is a usage error" "$([ "$RC" -eq 2 ] && [ "$(downloads)" -eq 0 ] && echo 0 || echo 1)" "rc=$RC"

printf '%s\n' '{"ok":true,"data":{"available":true}}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli aicore info --stage preview --preference fast
grep -q '"cmd":"aicore.info"' "$WORK/requests.log" && grep -q '"stage":"preview"' "$WORK/requests.log" && grep -q '"preference":"fast"' "$WORK/requests.log" && [ "$(broadcasts)" -eq 0 ]
check "info goes through the socket with the selection" $?
printf '%s\n' '{"ok":false,"error":"no model"}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli aicore info
[ "$RC" -ne 0 ] && grep -q '"ok":false' "$WORK/out"; check "info ok:false gives rc != 0 and prints the JSON" $?

# ------------------------------------------------- aicore L3: model, foreground, download
start_stub echo
: > "$WORK/requests.log"
run_cli aicore generate --model gemini-nano hello
python3 - "$WORK/requests.log" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
args = req["args"]
assert req["cmd"] == "aicore.generate", req
assert args.get("model") == "gemini-nano", args
assert args["prompt"] == "hello", args
PY
check "aicore generate --model reaches the request as model" $?
: > "$WORK/requests.log"
run_cli aicore generate --model "bad name" hello
check "aicore generate --model with spaces is a usage error (rc 2), nothing sent" "$([ "$RC" -eq 2 ] && [ ! -s "$WORK/requests.log" ] && echo 0 || echo 1)" "rc=$RC"

printf '%s\n' '{"ok":false,"error":"the app is not in the foreground","error_name":"FOREGROUND_REQUIRED","error_code":2001,"remedy":"open the Termux AI app, or grant Termux the Display over other apps permission"}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli aicore generate hello
check "FOREGROUND_REQUIRED exits 5" "$([ "$RC" -eq 5 ] && echo 0 || echo 1)" "rc=$RC"
grep -q "over other apps" "$WORK/err"; check "FOREGROUND_REQUIRED prints the remedy" $? "$(cat "$WORK/err")"

printf '%s\n' '{"ok":false,"error":"background use is blocked by the engine","error_name":"BACKGROUND_USE_BLOCKED","error_code":30,"remedy":"open the Termux AI app so it is in the foreground"}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli aicore generate hello
check "BACKGROUND_USE_BLOCKED exits 6" "$([ "$RC" -eq 6 ] && echo 0 || echo 1)" "rc=$RC"
grep -q "foreground" "$WORK/err"; check "BACKGROUND_USE_BLOCKED prints the remedy" $? "$(cat "$WORK/err")"

routes_aicore() {
  cat > "$ROUTES" <<'JSONEOF'
aicore.download {"ok":true,"data":{"started":true,"stage":"stable","preference":"full"}}
aicore.generate {"ok":true,"data":{"text":"eco","finish_reason":"stop"}}
JSONEOF
}
routes_aicore
start_stub routes "$ROUTES"
: > "$WORK/requests.log"
run_cli aicore download
python3 - "$WORK/requests.log" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
assert req["cmd"] == "aicore.download", req
assert req["args"].get("wait") is False, req
PY
check "aicore download asks for a non-blocking download (wait false)" $?
[ "$RC" -eq 0 ]; check "aicore download returns at once with rc 0" "$([ "$RC" -eq 0 ] && echo 0 || echo 1)" "rc=$RC out=$(cat "$WORK/out")"
: > "$WORK/requests.log"
run_cli aicore download --wait
python3 - "$WORK/requests.log" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert args.get("wait") is True, args
PY
check "aicore download --wait asks for the blocking download" $?
: > "$WORK/requests.log"
run_cli aicore download --status
python3 - "$WORK/requests.log" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
assert req["cmd"] == "aicore.download", req
assert req["args"].get("status_only") is True, req
PY
check "aicore download --status asks for the status only" $?
run_cli aicore download --wait --status
check "aicore download --wait --status together is a usage error (rc 2)" "$([ "$RC" -eq 2 ] && echo 0 || echo 1)" "rc=$RC"


# ------------------------------------------------------------------- litert verbs
lrequests() { grep -c '"cmd":"litert.generate"' "$WORK/requests.log"; }

start_stub echo
run_cli litert generate --backend npu --model qwen3-0.6b --request-id t1 --max-tokens 64 --temperature 0.3 --top-k 20 hello world
python3 - "$WORK/requests.log" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
args = req["args"]
assert req["cmd"] == "litert.generate", req
assert args == {"request_id": "t1", "model": "qwen3-0.6b", "backend": "npu", "prompt": "hello world",
                "max_tokens": 64, "temperature": 0.3, "top_k": 20}, args
PY
check "litert generate sends the backend, model, request id and every flag" $?
[ "$RC" -eq 0 ] && [ "$(cat "$WORK/out")" = "hello world" ] && [ "$(broadcasts)" -eq 0 ]
check "litert generate prints the answer, rc 0, no broadcast" $?

: > "$WORK/requests.log"
run_cli litert generate --backend cpu --model m --request-id ctx1 --context 8192 hello
python3 - "$WORK/requests.log" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert args.get("context_tokens") == 8192, args
PY
check "litert generate --context reaches the request as context_tokens" $?
for badctx in 0 -4096 big 40.5; do
  : > "$WORK/requests.log"
  # shellcheck disable=SC2086
  run_cli litert generate --backend cpu --model m --context $badctx hello
  check "litert generate --context '$badctx' is a usage error (rc 2) and sends nothing" "$([ "$RC" -eq 2 ] && [ "$(lrequests)" -eq 0 ] && echo 0 || echo 1)" "rc=$RC"
done
: > "$WORK/requests.log"
run_cli litert generate --backend cpu --model m hello
python3 - "$WORK/requests.log" <<'PY'
import json, re, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert args["backend"] == "cpu" and args["model"] == "m", args
assert re.fullmatch(r"[A-Za-z0-9._-]{1,64}", args["request_id"]), args["request_id"]
assert "max_tokens" not in args and "temperature" not in args and "top_k" not in args, args
PY
check "without flags the request has a generated request id and no parameters" $?

start_stub echo
: > "$WORK/requests.log"
run_cli litert generate --backend gpu --model m --activation fp32 hello
python3 - "$WORK/requests.log" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert args["activation"] == "fp32" and args["backend"] == "gpu", args
PY
check "litert generate --activation fp32 sends the precision" $?
: > "$WORK/requests.log"
run_cli litert generate --backend gpu --model m hello
python3 - "$WORK/requests.log" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert "activation" not in args, args
PY
check "without --activation the request carries no activation at all" $?
for badact in "--activation fp8" "--activation FP32" "--activation default" "--activation" "--activation int8"; do
  : > "$WORK/requests.log"
  # shellcheck disable=SC2086
  run_cli litert generate --backend gpu --model m $badact hello
  check "litert generate '$badact' is a usage error (rc 2) and sends nothing" "$([ "$RC" -eq 2 ] && [ "$(lrequests)" -eq 0 ] && echo 0 || echo 1)" "rc=$RC"
done
run_cli --help
grep -q -- "--activation fp16|fp32" "$WORK/out"; check "--help lists --activation" $?
grep -q "unasked activation is fp32" "$WORK/out"; check "--help says the gpu activation default is fp32" $?

for badflags in "--model m" "--backend CPU --model m" "--backend tpu --model m" "--backend --model m" "--backend cpu" "--backend cpu --model ../x" "--backend cpu --model a//b" "--backend cpu --model /abs" "--backend cpu --model a/../b" "--backend cpu --model a/.h" "--backend cpu --model a/b/c/d" "--backend cpu --model .hidden" "--backend cpu --model m --request-id a/b" "--backend cpu --model m --stage stable" "--backend cpu --model m --preference fast" "--backend cpu --model m --max-tokens 0" "--backend cpu --model m --temperature 1.5" "--backend cpu --model m --top-k 0"; do
  : > "$WORK/requests.log"
  # shellcheck disable=SC2086
  run_cli litert generate $badflags hello
  check "litert generate '$badflags' is a usage error (rc 2) and sends nothing" "$([ "$RC" -eq 2 ] && [ "$(lrequests)" -eq 0 ] && [ ! -s "$WORK/out" ] && echo 0 || echo 1)" "rc=$RC requests=$(lrequests)"
done

start_stub echo
for id in qwen/q3 qwen/small/q0; do
  : > "$WORK/requests.log"
  run_cli litert generate --backend cpu --model "$id" hello
  python3 - "$WORK/requests.log" "$id" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert args["model"] == sys.argv[2], args
PY
  check "litert generate accepts the folder model id $id and sends it whole" $?
done

: > "$WORK/requests.log"
run_cli litert info
grep -q '"cmd":"litert.info"' "$WORK/requests.log" && [ "$(broadcasts)" -eq 0 ]
check "litert info goes through the socket" $?
for verb in unload restart; do
  printf '%s\n' '{"ok":true,"data":{"unloaded":false,"via":"none"}}' > "$WORK/resp"
  start_stub reply "$WORK/resp"
  : > "$WORK/requests.log"
  run_cli litert $verb
  python3 - "$WORK/requests.log" "$verb" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
assert req == {"cmd": "litert." + sys.argv[2], "args": {}}, req
PY
  check "litert $verb sends litert.$verb with no arguments through the socket" $?
  [ "$RC" -eq 0 ] && [ "$(broadcasts)" -eq 0 ]
  check "litert $verb: rc 0, no broadcast" $?
  run_cli litert $verb --force
  check "litert $verb takes no options (usage rc 2)" "$([ "$RC" -eq 2 ] && echo 0 || echo 1)" "rc=$RC"
done
run_cli --help
grep -q "termux-ai litert unload" "$WORK/out" && grep -q "termux-ai litert restart" "$WORK/out"; check "--help lists litert unload and restart" $?

printf '%s\n' '{"ok":true,"data":{"idle_unload_ms":300000,"source":"default"}}' > "$WORK/resp"
start_stub reply "$WORK/resp"
: > "$WORK/requests.log"
run_cli litert config
python3 - "$WORK/requests.log" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
assert req == {"cmd": "litert.config", "args": {}}, req
PY
check "litert config asks for the settings and sets nothing" $?
: > "$WORK/requests.log"
run_cli litert config --set idle_unload_ms=60000
python3 - "$WORK/requests.log" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
assert req == {"cmd": "litert.config", "args": {"set": {"idle_unload_ms": 60000}}}, req
PY
check "litert config --set idle_unload_ms=N sends the number" $?
for bad in "--set idle_unload_ms=abc" "--set idle_unload_ms=" "--set idle_unload_ms=-1" "--set foo=1" "--set" "--set idle_unload_ms=5 --set idle_unload_ms=6" "--force"; do
  : > "$WORK/requests.log"
  # shellcheck disable=SC2086
  run_cli litert config $bad
  check "litert config '$bad' is a usage error (rc 2) and sends nothing" "$([ "$RC" -eq 2 ] && [ ! -s "$WORK/requests.log" ] && echo 0 || echo 1)" "rc=$RC"
done
run_cli --help
grep -q "termux-ai litert config" "$WORK/out"; check "--help lists litert config" $?

printf '%s\n' '{"ok":true,"data":{"cancelled":true,"request_id":"r1"}}' > "$WORK/resp"
start_stub reply "$WORK/resp"
: > "$WORK/requests.log"
run_cli litert cancel --request-id r1
python3 - "$WORK/requests.log" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
assert req == {"cmd": "litert.cancel", "args": {"request_id": "r1"}}, req
PY
check "litert cancel --request-id sends that request id" $?
[ "$RC" -eq 0 ] && [ "$(broadcasts)" -eq 0 ]; check "litert cancel: rc 0, no broadcast" $?
: > "$WORK/requests.log"
run_cli litert cancel --all
python3 - "$WORK/requests.log" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
assert req == {"cmd": "litert.cancel", "args": {"all": True}}, req
PY
check "litert cancel --all asks for whatever runs" $?
for bad in "" "--all --request-id r1" "--request-id" "--request-id a/b" "--request-id ''" "--all extra" "--force" "r1"; do
  : > "$WORK/requests.log"
  # shellcheck disable=SC2086
  eval run_cli litert cancel $bad
  check "litert cancel '$bad' is a usage error (rc 2) and sends nothing" "$([ "$RC" -eq 2 ] && [ ! -s "$WORK/requests.log" ] && echo 0 || echo 1)" "rc=$RC"
done
run_cli --help
grep -q "termux-ai litert cancel" "$WORK/out"; check "--help lists litert cancel" $?
stop_stub; rm -f "$WORK/ai.sock"
run_cli litert cancel --all
check "litert cancel: socket absent is rc 3, nothing is broadcast" "$([ "$RC" -eq 3 ] && [ "$(broadcasts)" -eq 0 ] && echo 0 || echo 1)" "rc=$RC"
start_stub reply "$WORK/resp"

# A CLI that is interrupted while its generation runs asks for that generation to be cancelled: nobody waits for it.
signal_run() { # signal_run SIGNAL: a generate in its own process group, hung stub, then the signal to the group
  start_stub hang
  : > "$WORK/requests.log"
  PATH="$WORK/bin:$PATH" TERMUX_AI_SOCKET="$WORK/ai.sock" python3 -c 'import os,sys,signal; os.setsid(); signal.signal(signal.SIGINT, signal.SIG_DFL); os.execvp("bash", ["bash"] + sys.argv[1:])' "$CLI" litert generate --backend cpu --model m --request-id sig1 hello \
    > "$WORK/out" 2> "$WORK/err" < /dev/null &
  local pid=$! i
  for i in $(seq 1 100); do grep -q '"cmd":"litert.generate"' "$WORK/requests.log" && break; sleep 0.05; done
  sleep 0.2
  kill "-$1" -- "-$pid" 2>/dev/null
  wait "$pid"; RC=$?
  sleep 0.2
}
signal_run INT
check "an interrupted generate (SIGINT) exits 130" "$([ "$RC" -eq 130 ] && echo 0 || echo 1)" "rc=$RC"
grep -q '"cmd":"litert.cancel","args":{"request_id":"sig1"}' "$WORK/requests.log"; check "SIGINT: the CLI asks to cancel its own request id" $? "$(cat "$WORK/requests.log")"
signal_run TERM
check "a terminated generate (SIGTERM) exits 143" "$([ "$RC" -eq 143 ] && echo 0 || echo 1)" "rc=$RC"
grep -q '"cmd":"litert.cancel","args":{"request_id":"sig1"}' "$WORK/requests.log"; check "SIGTERM: the CLI asks to cancel its own request id" $?
# The same interrupt delivered to the bash process alone (not the group): the trap must fire at once, while
# nc is still connected — not after the exchange ends on its own. RC 999 = the bash was still alive after 6 s.
start_stub hang
: > "$WORK/requests.log"
PATH="$WORK/bin:$PATH" TERMUX_AI_SOCKET="$WORK/ai.sock" python3 -c 'import os,sys,signal; os.setsid(); signal.signal(signal.SIGINT, signal.SIG_DFL); os.execvp("bash", ["bash"] + sys.argv[1:])' "$CLI" litert generate --backend cpu --model m --request-id sig2 hello \
  > "$WORK/out" 2> "$WORK/err" < /dev/null &
pid=$!
for i in $(seq 1 100); do grep -q '"cmd":"litert.generate"' "$WORK/requests.log" && break; sleep 0.05; done
sleep 0.2
kill -INT "$pid" 2>/dev/null
RC=999
for i in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
if ! kill -0 "$pid" 2>/dev/null; then wait "$pid"; RC=$?; fi
kill -TERM -- "-$pid" 2>/dev/null
wait "$pid" 2>/dev/null
sleep 0.2
check "a SIGINT to the bash alone exits 130 without waiting for nc" "$([ "$RC" -eq 130 ] && echo 0 || echo 1)" "rc=$RC"
grep -q '"cmd":"litert.cancel","args":{"request_id":"sig2"}' "$WORK/requests.log"; check "SIGINT to the bash alone: the CLI asks to cancel its own request id" $? "$(cat "$WORK/requests.log")"
printf '%s\n' '{"ok":true,"data":{"text":"done","finish_reason":"stop"}}' > "$WORK/resp"
start_stub reply "$WORK/resp"
: > "$WORK/requests.log"
run_cli litert generate --backend cpu --model m --request-id fine hello
check "a generate that finishes sends no cancel" "$([ "$RC" -eq 0 ] && ! grep -q 'litert.cancel' "$WORK/requests.log" && echo 0 || echo 1)" "rc=$RC"

run_cli litert models extra
check "litert models takes no options" "$([ "$RC" -eq 2 ] && echo 0 || echo 1)" "rc=$RC"
run_cli litert download
check "litert has no download" "$([ "$RC" -ne 0 ] && echo 0 || echo 1)" "rc=$RC"

printf '%s\n' '{"ok":false,"error":"dispatch rejected the model","error_name":"BACKEND_INIT_FAILED","error_code":1009,"phase":"init","backend_requested":"npu"}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli litert generate --backend npu --model m hello
[ "$RC" -eq 1 ] && [ ! -s "$WORK/out" ] && grep -q "dispatch rejected the model" "$WORK/err" && grep -q "BACKEND_INIT_FAILED" "$WORK/err" && grep -q "backend npu" "$WORK/err" && grep -q "phase init" "$WORK/err"
check "a litert failure is rc 1 and names the code, the backend and the phase" $?
[ "$(lrequests)" -eq 1 ] && [ "$(broadcasts)" -eq 0 ]
check "a litert failure is not retried on another backend or by broadcast" $?

start_stub drop
run_cli litert generate --backend gpu --model m hello
check "litert: broken transport after send is rc 4, one request, zero broadcasts" "$([ "$RC" -eq 4 ] && [ "$(lrequests)" -eq 1 ] && [ "$(broadcasts)" -eq 0 ] && echo 0 || echo 1)" "rc=$RC requests=$(lrequests) broadcasts=$(broadcasts)"

stop_stub; rm -f "$WORK/ai.sock"; : > "$WORK/broadcast.log"
run_cli litert generate --backend cpu --model m hello
check "litert: socket absent, generate is rc 3 and does not broadcast" "$([ "$RC" -eq 3 ] && [ "$(broadcasts)" -eq 0 ] && echo 0 || echo 1)" "rc=$RC"
run_cli litert info
check "litert: socket absent, info is rc 3 too (there is no broadcast path)" "$([ "$RC" -eq 3 ] && [ "$(broadcasts)" -eq 0 ] && echo 0 || echo 1)" "rc=$RC broadcasts=$(broadcasts)"

# ------------------------------------------------- serve: OpenAI-compatible endpoint
run_cli --help
grep -q "termux-ai serve" "$WORK/out"; check "--help lists serve" $?

SRV_PORT=18123
SRV_PID=""
srv_stop() {
  if [ -n "$SRV_PID" ]; then kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=""; fi
}
srv_wait() {
  local i
  for i in $(seq 1 100); do
    (exec 3<>"/dev/tcp/127.0.0.1/$SRV_PORT") 2>/dev/null && { exec 3>&- 3<&-; return 0; }
    sleep 0.05
  done
  return 1
}
srv_start() { # srv_start: an app-socket stub must already be running
  TERMUX_AI_SOCKET="$WORK/ai.sock" bash "$CLI" serve --port "$SRV_PORT" > "$WORK/serve.log" 2>&1 &
  SRV_PID=$!
  if srv_wait; then return 0; fi
  bad "serve did not open 127.0.0.1:$SRV_PORT" "$(tail -3 "$WORK/serve.log")"
  return 1
}
srv_http() { # srv_http OUT METHOD PATH [BODY]
  local out="$1" method="$2" path="$3" body="${4:-}" len=0
  [ -n "$body" ] && len=$(printf '%s' "$body" | wc -c)
  {
    printf '%s %s HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n' "$method" "$path" "$len"
    [ -n "$body" ] && printf '%s' "$body"
    sleep 0.4
  } | nc 127.0.0.1 "$SRV_PORT" > "$out" 2>/dev/null
}
srv_body() { # srv_body HTTPFILE -> header-free body on stdout
  python3 -c 'import sys; d=open(sys.argv[1],"rb").read(); i=d.find(b"\r\n\r\n"); sys.stdout.write(d[i+4:].decode("utf-8","replace"))' "$1"
}
routes_models() {
  cat > "$ROUTES" <<'JSONEOF'
litert.models {"ok":true,"data":{"directory":"/d","directory_exists":true,"models":[{"id":"gemma-4-e2b","path":"/d/gemma-4-e2b.litertlm","size_bytes":9}],"problems":[]}}
aicore.models {"ok":true,"data":{"models":[{"stage":"stable","preference":"full","status":"AVAILABLE","available":true,"base_model_name":"gemini-nano-3"}],"note":""}}
JSONEOF
}
routes_gen() {
  cat > "$ROUTES" <<'JSONEOF'
litert.generate {"ok":true,"data":{"text":"ciao dal litert","finish_reason":"stop","backend_requested":"cpu","backend_effective":"cpu","backend_verified":true,"engine_reused":false}}
aicore.generate {"ok":true,"data":{"text":"ciao dal tpu","finish_reason":"stop"}}
JSONEOF
}

routes_models
start_stub routes "$ROUTES"
srv_start
srv_http "$WORK/http.out" GET /v1/models
check "serve: /v1/models answers 200" "$(grep -q '^HTTP/1.1 200' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
srv_body "$WORK/http.out" > "$WORK/body.json"
python3 - "$WORK/body.json" <<'PY'
import json, sys
body = json.load(open(sys.argv[1]))
assert body["object"] == "list", body
ids = {m["id"]: m for m in body["data"]}
assert "gemma-4-e2b" in ids and ids["gemma-4-e2b"].get("termux_backend") == "litert", ids
assert "nano-3" in ids and ids["nano-3"].get("termux_backend") == "aicore", ids
PY
check "serve: /v1/models maps gemma to litert and nano to aicore" $?
srv_stop

routes_gen
start_stub routes "$ROUTES"
srv_start
: > "$WORK/requests.log"
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","messages":[{"role":"system","content":"sei a bordo"},{"role":"user","content":"saluta"}],"max_completion_tokens":128}'
check "serve: chat completions answers 200" "$(grep -q '^HTTP/1.1 200' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
srv_body "$WORK/http.out" > "$WORK/body.json"
python3 - "$WORK/body.json" <<'PY'
import json, sys
body = json.load(open(sys.argv[1]))
assert body["object"] == "chat.completion", body
choice = body["choices"][0]
assert choice["message"]["role"] == "assistant", choice
assert choice["message"]["content"] == "ciao dal litert", choice
assert choice["finish_reason"] == "stop", choice
assert body["model"] == "gemma-4-e2b", body
PY
check "serve: non-stream chat answers the OpenAI shape with the answer text" $?
python3 - "$WORK/requests.log" <<'PY'
import json, sys, re
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
args = req["args"]
assert req["cmd"] == "litert.generate", req
assert args["model"] == "gemma-4-e2b", args
assert "sei a bordo" in args["prompt"] and "saluta" in args["prompt"], args
assert args["max_tokens"] == 128, args
assert re.fullmatch(r"[A-Za-z0-9._-]{1,64}", args["request_id"]), args
PY
check "serve: the chat reaches ai.sock as litert.generate (prompt joined, max_completion_tokens as max_tokens)" $?

: > "$WORK/requests.log"
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","stream":true,"messages":[{"role":"user","content":"saluta"}]}'
grep -q '^HTTP/1.1 200' "$WORK/http.out" && grep -qi '^content-type: text/event-stream' "$WORK/http.out"
check "serve: a stream request answers 200 text/event-stream" $?
srv_body "$WORK/http.out" > "$WORK/body.json"
python3 - "$WORK/body.json" <<'PY'
import json, sys
chunks = []
done = False
for line in open(sys.argv[1]):
    line = line.strip()
    if line == "data: [DONE]":
        done = True
    elif line.startswith("data: "):
        chunks.append(json.loads(line[6:]))
assert done, "no data: [DONE] seen"
assert chunks[0]["choices"][0]["delta"].get("role") == "assistant", chunks
text = "".join(c["choices"][0]["delta"].get("content", "") for c in chunks)
assert text == "ciao dal litert", text
assert chunks[-1]["choices"][0]["finish_reason"] == "stop", chunks
PY
check "serve: the SSE chunks carry the whole answer and end with a finish_reason" $?

: > "$WORK/requests.log"
# Tools: the coding-agent bridges send them on every request, so the default is to
# strip them (said in the x-termux-ai-tools header and the log), refuse is opt-in.
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","messages":[{"role":"user","content":"x"}],"tools":[{"type":"function","function":{"name":"f","parameters":{}}}],"tool_choice":"auto"}'
check "serve: tools are stripped by default (200, x-termux-ai-tools: stripped)" "$(grep -q '^HTTP/1.1 200' "$WORK/http.out" && grep -qi '^x-termux-ai-tools: stripped' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
[ "$(lrequests)" -eq 1 ]; check "serve: a stripped tools request still reaches ai.sock" $?
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","messages":[{"role":"user","content":"x"}],"tools":[]}'
grep -q '^HTTP/1.1 200' "$WORK/http.out" && ! grep -qi '^x-termux-ai-tools:' "$WORK/http.out"
check "serve: an empty tools array is left alone (200, no header)" $?
srv_stop
SRV_PORT=18124
TERMUX_AI_SOCKET="$WORK/ai.sock" bash "$CLI" serve --port "$SRV_PORT" --tools refuse > "$WORK/serve2.log" 2>&1 &
SRV_PID=$!
if ! srv_wait; then bad "serve (refuse) did not open" "$(tail -3 "$WORK/serve2.log")"; fi
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","messages":[{"role":"user","content":"x"}],"tools":[{"type":"function","function":{"name":"f"}}]}'
check "serve: --tools refuse answers 400 tools_not_supported" "$(grep -q '^HTTP/1.1 400' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
srv_body "$WORK/http.out" | python3 -c 'import json,sys; b=json.load(sys.stdin); assert b["error"]["code"]=="tools_not_supported", b'
check "serve: the tools refusal names tools_not_supported" $?
srv_stop
SRV_PORT=18123
start_stub routes "$ROUTES"
srv_start

: > "$WORK/requests.log"
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"nano-3","messages":[{"role":"user","content":"saluta"}]}'
grep -q '^HTTP/1.1 200' "$WORK/http.out"; check "serve: nano-3 answers 200" "$(grep -q '^HTTP/1.1 200' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
python3 - "$WORK/requests.log" <<'PY'
import json, sys
req = json.loads(open(sys.argv[1]).read().splitlines()[-1])
assert req["cmd"] == "aicore.generate", req
assert req["args"]["prompt"] == "saluta", req
PY
check "serve: nano-3 is translated to aicore.generate" $?

srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"zzz-other","messages":[{"role":"user","content":"x"}]}'
grep -q '^HTTP/1.1 400' "$WORK/http.out"; check "serve: an unmapped model is a 400" "$(grep -q '^HTTP/1.1 400' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
srv_body "$WORK/http.out" | python3 -c 'import json,sys; b=json.load(sys.stdin); assert b["error"]["code"]=="model_not_found", b'
check "serve: the unmapped refusal names model_not_found" $?

srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b"}'
grep -q '^HTTP/1.1 400' "$WORK/http.out"; check "serve: chat without messages is a 400" $?

: > "$WORK/requests.log"
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","messages":[{"role":"user","content":"x"}],"max_tokens":77}'
python3 - "$WORK/requests.log" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert args["max_tokens"] == 77, args
PY
check "serve: legacy max_tokens reaches the request too" $?

# Unknown fields never 400; the developer role and text parts flatten into the prompt.
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","store":false,"reasoning_effort":"low","prompt_cache_key":"k","messages":[{"role":"developer","content":"regole di bordo"},{"role":"user","content":[{"type":"text","text":"parte1"},{"type":"text","text":"parte2"}]}]}'
grep -q '^HTTP/1.1 200' "$WORK/http.out"; check "serve: unknown fields, developer role and text parts are accepted" "$(grep -q '^HTTP/1.1 200' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
python3 - "$WORK/requests.log" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert "regole di bordo" in args["prompt"], args
assert "parte1" in args["prompt"] and "parte2" in args["prompt"], args
PY
check "serve: developer content and text parts flatten into the prompt" $?

# stream_options.include_usage: the last chunk (before [DONE]) carries an estimated usage.
: > "$WORK/requests.log"
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","stream":true,"stream_options":{"include_usage":true},"store":false,"messages":[{"role":"user","content":"saluta"}]}'
grep -q '^HTTP/1.1 200' "$WORK/http.out"; check "serve: stream with unknown fields answers 200" "$(grep -q '^HTTP/1.1 200' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
srv_body "$WORK/http.out" > "$WORK/body.json"
python3 - "$WORK/body.json" <<'PY'
import json, sys
chunks, done = [], False
for line in open(sys.argv[1]):
    line = line.strip()
    if line == "data: [DONE]":
        done = True
    elif line.startswith("data: "):
        chunks.append(json.loads(line[6:]))
assert done, "no [DONE]"
last = chunks[-1]
assert last["choices"][0]["finish_reason"] is not None, last
assert last.get("usage", {}).get("prompt_tokens", 0) > 0, last
PY
check "serve: the last chunk has a finish_reason and the estimated usage" $?

# Typed backend failures, as the socket reports them.
routes_errors() {
  cat > "$ROUTES" <<'JSONEOF'
litert.generate {"ok":false,"error":"request x is still running","error_name":"BUSY","error_code":1011}
aicore.generate {"ok":false,"error":"request x is still running","error_name":"BUSY","error_code":1011}
JSONEOF
}
routes_errors
stop_stub
start_stub routes "$ROUTES"
srv_stop
srv_start
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","messages":[{"role":"user","content":"x"}]}'
check "serve: BUSY is a 503 with Retry-After" "$(grep -q '^HTTP/1.1 503' "$WORK/http.out" && grep -qi '^retry-after:' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
routes_context() {
  cat > "$ROUTES" <<'JSONEOF'
litert.generate {"ok":false,"error":"prompt exceeds the 4096 token context of this model","error_name":"CONTEXT_EXCEEDED","error_code":1012}
JSONEOF
}
routes_context
stop_stub
start_stub routes "$ROUTES"
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","messages":[{"role":"user","content":"x"}]}'
check "serve: CONTEXT_EXCEEDED is a 400" "$(grep -q '^HTTP/1.1 400' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
srv_body "$WORK/http.out" | python3 -c 'import json,sys; b=json.load(sys.stdin); assert b["error"]["code"]=="CONTEXT_EXCEEDED" and "4096" in b["error"]["message"], b'
check "serve: CONTEXT_EXCEEDED names the code and the context in the message" $?
routes_empty() {
  cat > "$ROUTES" <<'JSONEOF'
litert.generate {"ok":false,"error":"no answer text","error_name":"EMPTY_OUTPUT","error_code":1019}
JSONEOF
}
routes_empty
stop_stub
start_stub routes "$ROUTES"
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","messages":[{"role":"user","content":"x"}]}'
srv_body "$WORK/http.out" > "$WORK/body.json"
python3 - "$WORK/body.json" <<'PY'
import json, sys
body = json.load(open(sys.argv[1]))
assert body["choices"][0]["message"]["content"] == "", body
assert body["choices"][0]["finish_reason"] == "stop", body
PY
check "serve: EMPTY_OUTPUT is an empty completion with finish stop, not a 5xx" $?

# The serve-level context and backend reach the litert request.
srv_stop
stop_stub
routes_gen
start_stub routes "$ROUTES"
SRV_PORT=18125
TERMUX_AI_SOCKET="$WORK/ai.sock" bash "$CLI" serve --port "$SRV_PORT" --context 8192 --backend cpu > "$WORK/serve3.log" 2>&1 &
SRV_PID=$!
if ! srv_wait; then bad "serve (context) did not open" "$(tail -3 "$WORK/serve3.log")"; fi
: > "$WORK/requests.log"
srv_http "$WORK/http.out" POST /v1/chat/completions '{"model":"gemma-4-e2b","messages":[{"role":"user","content":"x"}]}'
check "serve (context): chat answers 200" "$(grep -q '^HTTP/1.1 200' "$WORK/http.out" && echo 0 || echo 1)" "$(head -1 "$WORK/http.out" | tr -d '\r')"
python3 - "$WORK/requests.log" <<'PY'
import json, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert args["context_tokens"] == 8192, args
assert args["backend"] == "cpu", args
PY
check "serve: --context and --backend reach the litert request" $?
srv_stop
SRV_PORT=18123

SRV_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
if [ -n "$SRV_IP" ]; then
  timeout 2 nc "$SRV_IP" "$SRV_PORT" < /dev/null > /dev/null 2>&1
  [ $? -ne 0 ]; check "serve: the endpoint is not reachable on $SRV_IP (loopback only)" $?
fi
srv_stop

# ------------------------------------------------------------- missing nc (6b)
# A PATH with every tool of the host except nc: the socket is there, the tool to talk to it is not.
mkdir -p "$WORK/nonc"
for tool in /usr/bin/* /bin/*; do
  case "$(basename "$tool")" in nc|ncat|netcat|nc.openbsd|nc.traditional) continue ;; esac
  [ -e "$WORK/nonc/$(basename "$tool")" ] || ln -s "$tool" "$WORK/nonc/$(basename "$tool")" 2>/dev/null
done
run_cli_nonc() {
  PATH="$WORK/nonc" TERMUX_AI_SOCKET="$WORK/ai.sock" "$BASH" "$CLI" "$@" > "$WORK/out" 2> "$WORK/err" < /dev/null
  RC=$?
}
PATH="$WORK/nonc" command -v nc >/dev/null 2>&1; check "the no-nc PATH really has no nc" "$([ $? -ne 0 ] && echo 0 || echo 1)"

printf '%s\n' '{"ok":true,"data":{"models":[]}}' > "$WORK/resp"
start_stub reply "$WORK/resp"
run_cli_nonc litert models
check "missing nc: rc 2 (not 3: the socket is there), nothing sent" "$([ "$RC" -eq 2 ] && [ ! -s "$WORK/requests.log" ] && echo 0 || echo 1)" "rc=$RC"
grep -q "pkg install netcat-openbsd" "$WORK/out"
check "missing nc: the message names the remedy" $? "$(cat "$WORK/out")"
run_cli_nonc litert generate --backend cpu --model m hello
check "missing nc: generate is rc 2 too and sends nothing" "$([ "$RC" -eq 2 ] && [ ! -s "$WORK/requests.log" ] && echo 0 || echo 1)" "rc=$RC"
run_cli --help
grep -q "netcat-openbsd" "$WORK/out"; check "--help lists nc among the requirements" $?
stop_stub; rm -f "$WORK/ai.sock"
run_cli_nonc litert info
check "regression guard: no socket and no nc is still rc 3 (the socket is checked first)" "$([ "$RC" -eq 3 ] && echo 0 || echo 1)" "rc=$RC"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
