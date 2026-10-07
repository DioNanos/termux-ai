#!/usr/bin/env bash
# Shell tests for the termux-ai CLI: a stub unix socket stands in for the app.
# Run: bash app/src/test/shell/termux-ai-cli.test.sh   (needs bash 4+, python3, nc)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI="${TERMUX_AI_CLI:-$HERE/../../main/assets/termux-ai}"
STUB="$HERE/stub_socket_server.py"
WORK="$(mktemp -d)"
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
run_cli litert generate --backend cpu --model m hello
python3 - "$WORK/requests.log" <<'PY'
import json, re, sys
args = json.loads(open(sys.argv[1]).read().splitlines()[-1])["args"]
assert args["backend"] == "cpu" and args["model"] == "m", args
assert re.fullmatch(r"[A-Za-z0-9._-]{1,64}", args["request_id"]), args["request_id"]
assert "max_tokens" not in args and "temperature" not in args and "top_k" not in args, args
PY
check "without flags the request has a generated request id and no parameters" $?

for badflags in "--model m" "--backend CPU --model m" "--backend tpu --model m" "--backend --model m" "--backend cpu" "--backend cpu --model ../x" "--backend cpu --model a/b" "--backend cpu --model .hidden" "--backend cpu --model m --request-id a/b" "--backend cpu --model m --stage stable" "--backend cpu --model m --preference fast" "--backend cpu --model m --max-tokens 0" "--backend cpu --model m --temperature 1.5" "--backend cpu --model m --top-k 0"; do
  : > "$WORK/requests.log"
  # shellcheck disable=SC2086
  run_cli litert generate $badflags hello
  check "litert generate '$badflags' is a usage error (rc 2) and sends nothing" "$([ "$RC" -eq 2 ] && [ "$(lrequests)" -eq 0 ] && [ ! -s "$WORK/out" ] && echo 0 || echo 1)" "rc=$RC requests=$(lrequests)"
done

: > "$WORK/requests.log"
run_cli litert info
grep -q '"cmd":"litert.info"' "$WORK/requests.log" && [ "$(broadcasts)" -eq 0 ]
check "litert info goes through the socket" $?
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

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
