# Contract matrix — 14 methods × 3 observable entries

Baseline: product `526b0422` (tag `v0.118.0-ai.21`). Wrapper
contracts pinned to upstream `termux-api-package` `0e3f9222` and app side
`termux-api` `760c1777` (see `UPSTREAM_PIN.md`). All fixtures are synthetic;
no real SMS, contact, call log or notification data exists in this tree.

This matrix records the **baseline behaviour** of the three observable
entries against the **target contract**. FAIL here means "the baseline does
not meet the target contract", which is expected and factual: the compat
adapter that closes the gap is follow-up work. Nothing in this layer changes behaviour.

Source tags on every cell (42 = 14 methods × 3 entries):

* **[measured: shim+stub]** — executed by
  `harness/termux-api-compat.test.sh` against the shipped asset with the
  stub `termux-am`. This certifies the **client side only**: dispatch,
  extras, stdout/stderr/exit as observed at the CLI. It does NOT prove the
  Android handler produced the payload: the stub answers from the synthetic
  canned goldens.
* **[source: …]** — read from a source file at the pin (or, for the native
  payload shapes, from this app's `TermuxAiSocketServer.java`), with
  file:line anchors. Golden schemas and serialization are normative in
  `GOLDEN_CONTRACT.md`.

Cases marked **[baseline-defect]** are characterization checks: they pin
the current (wrong) behaviour — exactly, including the measured rc/stderr —
and must be updated by the layer that fixes them; they are not silent
greens. `harness/mutation_selftest.sh` proves an uncharacterized deviation
(any unexpected exit, stderr, empty/malformed stdout, wrong payload or a
command never executed) turns the harness red.

## 1. The two contract families

### Native family (`termux-ai ...`)

* stdout: one JSON envelope per run, `{"ok":true,"data":…}` or
  `{"ok":false,"error":"…"}` (+ typed fields like `error_name`), followed by
  exactly one `\n` (`run_broadcast` prints `data` then newline,
  `assets/termux-ai:343`; socket path `handle_response :507-516`).
* stderr: empty on success. On `ok:false` in text mode the message goes to
  stderr (`emit_error :444-453`); on the broadcast path errors are printed as
  a JSON line on **stdout** (`fail_json :43-48`).
* exit: 0 on success; 1 application error; 2 usage (native verbs);
  3 socket absent; 4 transport after send; 5/6 typed AICore foreground
  (`:15-22`).
* stdin: only read by verbs that say so (`read_stdin_or_args :519-525`).

### Compat family (upstream profile, pin `0e3f9222` / `760c1777`)

Target for `$PREFIX/libexec/termux-api <Method>` and for the app-provided
`termux-*` aliases (plan decision A):

* stdout: the naked payload the upstream API prints — a 2-space-indented
  JSON object (BatteryStatus, AudioInfo, WifiConnectionInfo), JSON array
  (CameraInfo, Volume list, TTS engines), the raw clipboard text
  (Clipboard get), a **plain text line** (NotificationChannel create/delete,
  `NotificationAPI.java:99,109`), or nothing (action methods: clipboard set,
  torch, toast, vibrate, notification show, brightness, notification
  remove, volume set, tts speak). Indentation and trailing-newline rules
  per payload are fixed in `GOLDEN_CONTRACT.md` §1
  (`ResultReturner.java:164-174`).
* stderr: empty on success; error message on stderr on failure.
* exit: 0 on success, nonzero on failure (C client `termux-api.c:200-233`:
  first byte `0` from the socket means success; any other payload is an
  error string printed to stderr).
* stdin: consumed only by the wrappers that document it
  (clipboard-set, notification content, toast, tts-speak), byte-exact,
  with the wrapper's own trailing-newline semantics.

## 2. The three observable entries

The same shipped script (`app/src/main/assets/termux-ai`, installed 18 times
by `TermuxAiCliInstaller`) dispatches on its own name (`SELF`, `:7`):

| Entry | Invoked as | Reaches | Code |
|---|---|---|---|
| native | `termux-ai <verb>` | `main()` verbs → `run_broadcast`/socket | `:729-1112` |
| libexec compat | `$PREFIX/libexec/termux-api <Method> [am-args]` | `api_compat()` | `:527-553` |
| alias | `$PREFIX/bin/termux-<name> [options] [text]` | `compat_main()` alias handlers | `:555-676` |

All three broadcast entries share one transport: `run_broadcast`
(`:311-355`) shells out to `termux-am`/`/system/bin/am`, captures stdout+stderr
merged (`:326`), scrapes `Broadcast completed: result=…, data="…"` and prints
`data`. Consequences measured below: am's stderr can leak into the error
message, and the printed envelope always ends in exactly one `\n`. If
`compat_main` returns nonzero, `main` (`:730-732`) falls through to the
native verb dispatch with the original arguments — observable in the
mutation counter-proof.

## 3. Matrix — summary

Status per entry vs the entry's own target contract (native → native
contract; compat and alias → upstream compat profile). **N/A = the entry
does not exist on this baseline**: NotificationChannel, NotificationRemove
and Vibrate have no native verb (`api_list`, `TermuxAiSocketServer.java:241-258`
registers them only as bridge cmds; the native CLI surface was not widened
in this layer), so those cells cannot be PASS — they are absent, not conforming.

| # | Method (upstream) | native | compat (libexec) | alias (termux-*) | Alias name(s) |
|---|---|---|---|---|---|
| 1 | AudioInfo | PASS | FAIL (envelope) | FAIL (envelope) | termux-audio-info |
| 2 | BatteryStatus | PASS | FAIL (envelope) | FAIL (envelope) | termux-battery-status |
| 3 | Brightness | PASS | FAIL (envelope) | FAIL (envelope + option validation) | termux-brightness |
| 4 | CameraInfo | PASS | FAIL (envelope) | FAIL (envelope) | termux-camera-info |
| 5 | Clipboard (get/set) | PASS | **FAIL (argv1 eaten as cmd — defect `:535`)** | FAIL (envelope; stdin ✔, byte-keep ✘) | termux-clipboard-get / termux-clipboard-set |
| 6 | Notification | PASS | FAIL (envelope) | FAIL (envelope; stdin ✔ `:592-594`; option subset) | termux-notification |
| 7 | NotificationChannel | **N/A (no native verb)** | FAIL (envelope; upstream prints a text line) | FAIL (envelope; upstream prints a text line) | termux-notification-channel |
| 8 | NotificationRemove | **N/A (no native verb)** | FAIL (envelope) | FAIL (envelope) | termux-notification-remove |
| 9 | TextToSpeech | PASS | FAIL (envelope; engines invocation lands on tts.speak) | FAIL (envelope; options ✔, argv newline ✘) | termux-tts-speak / termux-tts-engines |
| 10 | Toast | PASS | FAIL (envelope) | FAIL (envelope; options subset) | termux-toast |
| 11 | Torch | PASS | FAIL (envelope) | FAIL (envelope) | termux-torch |
| 12 | Vibrate | **N/A (no native verb)** | FAIL (envelope) | FAIL (envelope; `-f` missing) | termux-vibrate |
| 13 | Volume | PASS | **FAIL (argv1 eaten as cmd — defect `:549`)** | FAIL (envelope; stream/volume ✔) | termux-volume |
| 14 | WifiConnectionInfo | PASS | FAIL (envelope) | FAIL (envelope) | termux-wifi-connectioninfo |

"FAIL (envelope)" is the family defect of both non-native entries on this
baseline: the entry prints the native envelope instead of the upstream naked
payload, prints errors on stdout instead of stderr, and exits 1 with a JSON
error body where upstream expects an empty stdout. It is closed by the compat
adapter, not by this layer.

## 4. Matrix — per-method cells

Every cell: stdout / stderr / exit / options·stdin / bytes / status, then
the source tag. For native cells the payload key sets are **[source:]** from
`TermuxAiSocketServer.java` — the canned stub proves the client side, not
the Android handler. For compat/alias target shapes the source is the pin.

### 1. AudioInfo

* native `termux-ai audio info`: stdout — envelope, `data={sample_rate
  (string), frames_per_buffer (string), bluetooth_a2dp_on, wired_headset_on,
  speakerphone_on, music_active}` (`TermuxAiSocketServer.audioInfo :380-390`);
  stderr — empty; exit — 0; options/stdin — none; bytes — envelope + one
  `\n`. **PASS** [measured: shim+stub for the client side; payload keys
  source: `:380-390`].
* compat `termux-api AudioInfo`: dispatches `audio.info` (`:531`); stdout —
  envelope, not the upstream indented object
  `{PROPERTY_OUTPUT_SAMPLE_RATE (string), PROPERTY_OUTPUT_FRAMES_PER_BUFFER
  (string), AUDIOTRACK_SAMPLE_RATE, AUDIOTRACK_BUFFER_SIZE_IN_FRAMES,
  [LOW_LATENCY pair], [POWER_SAVING pair], BLUETOOTH_A2DP_IS_ON,
  WIREDHEADSET_IS_CONNECTED}` (`AudioAPI.java:68-88`); stderr — empty on
  success, errors on stdout; exit — 0; options/stdin — am-args forwarded;
  bytes — envelope + `\n`. **FAIL (envelope)** [measured: shim+stub; target
  source: `AudioAPI.java`].
* alias `termux-audio-info`: no options, rejects extra args (`:560-562` →
  run_broadcast audio.info; wrapper parity: upstream also takes none).
  Same envelope defect. **FAIL (envelope)** [measured: shim+stub]; usage
  parity PASS.

### 2. BatteryStatus

* native `termux-ai battery`: envelope `data={level, charging, current_ua,
  charge_uah}` (`:290-297`); stderr empty; exit 0; no stdin. **PASS**
  [measured: shim+stub; payload keys source: `:290-297`].
* compat `termux-api BatteryStatus` → `sys.battery` (`:532`): envelope, not
  the upstream indented object with mandatory `present, health, plugged,
  status, temperature (double), voltage, level, scale` and optional
  `technology, current, current_average, percentage, charge_counter,
  energy, cycle` (`BatteryStatusAPI.java:149-168`). **FAIL (envelope)**
  [measured: shim+stub; target source: `BatteryStatusAPI.java`].
* alias `termux-battery-status` (`:563-565`): same dispatch; upstream
  wrapper takes no arguments, ours accepts and forwards anything. **FAIL
  (envelope)** [measured: shim+stub]; arg rejection is a divergence to
  record (upstream errors on extra args, ours forwards them).

### 3. Brightness

* native `termux-ai brightness LEVEL|auto`: 1 arg required (`:801-804`);
  `--ez auto true` or `--ei brightness N`; envelope `{mode[,brightness]}`
  (`:412-427`); stderr empty; exit 0. **PASS** [measured: shim+stub;
  payload keys source: `:412-427`].
* compat `termux-api Brightness --ei brightness 128`: am-args forwarded
  verbatim (`:533`); stdout envelope, not empty as upstream (upstream
  `Brightness` prints nothing on success, `BrightnessAPI.java:33`).
  **FAIL (envelope)** [measured: shim+stub; target source:
  `BrightnessAPI.java`].
* alias `termux-brightness 128|auto` (`:566-569`): upstream validates
  `^([0-9]+)|auto$` before sending (`termux-brightness.in:15-18`); ours
  forwards any string (am then fails on a non-integer `--ei`), and upstream
  also sends `--ez auto false` on the numeric path while ours does not.
  **FAIL (envelope + option validation)** [measured: shim+stub; target
  source: `termux-brightness.in`].

### 4. CameraInfo

* native `termux-ai camera info`: envelope `data={cameras:[{id, facing
  (back|front|unknown), flash_available}]}` (`:429-442`); stderr empty;
  exit 0; no stdin; stdout byte-exact the golden envelope. **PASS**
  [measured: shim+stub, byte-exact; payload keys source: `:429-442`].
* compat `termux-api CameraInfo`: envelope, not the upstream **array** —
  per camera `id (string), facing (lowercase back|front),
  jpeg_output_sizes[], focal_lengths[], auto_exposure_modes[],
  physical_size{width,height}, capabilities[]`
  (`CameraInfoAPI.java:31-145`; full schema in `GOLDEN_CONTRACT.md`).
  **FAIL (envelope)** [measured: shim+stub; target source:
  `CameraInfoAPI.java`].
* alias `termux-camera-info` (`:570-572`): same; stdout is byte-exact the
  envelope golden (harness-checked). **FAIL (envelope)** [measured:
  shim+stub, byte-exact].

### 5. Clipboard — the confirmed compat-only dispatch defect (`:535`)

* native `termux-ai clipboard get|set TEXT` (`:764-777`): get → envelope
  `{text}`; set → `--es text "$*"` (args joined by spaces, trailing-newline
  semantics of `$*`), envelope `{status:"ok"}`; stderr empty; exit 0.
  **PASS** [measured: shim+stub; payload keys source: `:299-313`].
* compat `termux-api Clipboard [-e api_version 2 --ez set true]`:
  `run_broadcast "${1:-sys.clipboard.get}" "${@:2}"` (`:535`) — the **first
  extra argument is consumed as the bridge command**. Bare `Clipboard`
  works (defaults to `sys.clipboard.get`); the upstream clipboard-set
  invocation `Clipboard -e api_version 2 --ez set true` sends `cmd=-e`,
  prints the unknown-command envelope and exits 1 with empty stderr.
  Stdin is never read on this entry (the C client at the pin streams stdin
  to the app). **FAIL (argv1 eaten as cmd; envelope; no stdin)**
  **[baseline-defect]** [measured: shim+stub; rc 1, stdout error envelope,
  stderr empty — exact].
* alias `termux-clipboard-get` (`:573-575`): dispatch ✔; envelope defect
  (upstream: raw clip text with no trailing newline, `ClipboardAPI.java:51,75`).
  alias `termux-clipboard-set` (`:576-578`): reads stdin or args via
  `read_stdin_or_args` ✔ (upstream parity in *reading* stdin), sends
  `--es text <content>`.
  Byte preservation vs upstream stdin path: command substitution strips **all
  trailing newlines** and **every NUL byte** (bash also prints exactly one
  `command substitution: ignored null byte in input` warning line on stderr
  for NUL input — measured, characterized); UTF-8 is preserved
  (`LC_ALL=C`, binary-safe bash strings). Upstream passes stdin through the C
  client byte-exact (no trim on the stdin path; `echo -n "$@"` on the argv
  path space-joins without a trailing newline — parity there). **FAIL
  (envelope; trailing-newline/NUL loss on stdin)** [measured: shim+stub] —
  the stdin-presence half is PASS, no forced red on it.

### 6. Notification — stdin on the alias is already correct (`:592-594`)

* native `termux-ai notify TITLE [BODY…]` (`:817-821`): `--es title --es
  text "$*"`; envelope `{status:"posted",id,channel}` (`:330-350`); stderr
  empty; exit 0. **PASS** [measured: shim+stub; payload keys source:
  `:330-350`].
* compat `termux-api Notification [am-args]` (`:536`): forwards extras;
  envelope, not empty as upstream (`NotificationAPI.java:57-81` writes no
  output). **FAIL (envelope)** [measured: shim+stub; target source:
  `NotificationAPI.java`].
* alias `termux-notification` (`:579-594`): options `-t/--title`,
  `-c/--content`, `-i/--id`, `--channel`, `--ongoing`, `-h`; when no `-c` is
  given the content **is read from stdin** (`:592` → `read_stdin_or_args`) —
  baseline **PASS** on stdin presence (no forced red; this was already
  fixed before ai.21). Defaults `title=termux-ai`, `id=1773`,
  `channel=termux_ai_bridge`, `ongoing=false`. Option subset vs the pinned
  wrapper: upstream supports ~30 options (action, buttons, led-*, vibrate,
  priority, sound, type, media-*, image-path, group, icon, alert-once…);
  ours rejects them with `Unsupported termux-notification option`
  (`:589`, exit 1, stdout JSON, stderr empty) — recorded divergence, not
  silently ignored (good) but a compatibility gap (documented).
  Trailing-newline handling matches the upstream *intent* (upstream trims
  content, `termux-notification.in` "Trim trailing newlines") though the
  exact bytes differ (upstream re-adds one `\n` via `echo`; ours strips all
  trailing newlines via command substitution). **FAIL (envelope; option
  subset; trailing-newline byte diff)** [measured: shim+stub].

### 7. NotificationChannel

* native: **N/A — no native verb.** The bridge cmd `notification.channel`
  exists (`TermuxAiSocketServer.java:246`, handler `:352-364`) but no
  `termux-ai` verb dispatches to it, and this layer does not widen the native
  surface. Nothing to measure; not PASS.
* compat `termux-api NotificationChannel --es id X --es name Y | --ez delete
  true --es id X` (`:537`): forwards extras; envelope
  `{status:created|deleted|ignored,id[,name]}` (`:352-364`) vs upstream
  **plain text on stdout**: `Created channel with id "X" and name "Y".` /
  `Deleted channel with id "X".` (+ error lines) via `out.println`
  (`NotificationAPI.java:93-115`). **FAIL (envelope JSON vs text line)**
  [measured: shim+stub; target source: `NotificationAPI.java`].
* alias `termux-notification-channel [-d] id [name]` (`:595-603`): usage
  parity with the pinned wrapper (create 2 args / delete `-d` + 1 arg);
  upstream reads `$1` before checking arg count
  (`termux-notification-channel.in:22` `if [ "$1" = "-d" ]` under `set -u`),
  ours checks counts first — equal in practice. Same envelope-vs-text
  defect. **FAIL (envelope JSON vs text line)** [measured: shim+stub].

### 8. NotificationRemove

* native: **N/A — no native verb.** Bridge cmd `notification.remove`
  (`TermuxAiSocketServer.java:248`, handler `:366-371`); no `termux-ai`
  verb reaches it in this layer. Nothing to measure; not PASS.
* compat `termux-api NotificationRemove --es id N` (`:538`): envelope
  `{status:"removed",id}` vs upstream empty stdout
  (`NotificationAPI.java:329-338` noteDone). **FAIL (envelope)**
  [measured: shim+stub; target source: `NotificationAPI.java`].
* alias `termux-notification-remove N` (`:604-607`): exactly 1 arg, same
  extras as upstream. **FAIL (envelope)** [measured: shim+stub].

### 9. TextToSpeech

* native `termux-ai tts engines|speak TEXT` (`:778-784`): speak reads
  args/stdin; envelope `{engines:[{name,label,default}]}` /
  `{queued (bool), chars (int)}` (`ttsEngines :470-480`, `ttsSpeak
  :482-503`); stderr empty; exit 0. **PASS** [measured: shim+stub; payload
  keys source: `:470-503`].
* compat `termux-api TextToSpeech [am-args]` (`:539-545`): the mapping
  keys on the **bare argv1 literal** — `termux-api TextToSpeech
  LIST_AVAILABLE` → `tts.engines`. The **upstream engines invocation**
  `termux-api TextToSpeech --es engine LIST_AVAILABLE`
  (`termux-tts-engines.in:24`) has argv1 `--es`, so it lands on
  `tts.speak --es engine LIST_AVAILABLE` and speaks instead of listing:
  measured `CMD == "tts.speak"`, exit 0, envelope on stdout. Envelope vs
  upstream indented array `[{name,label,default}]` either way
  (`TextToSpeechAPI.java:124-140`). **FAIL (envelope; engines invocation
  dispatched to tts.speak)** [measured: shim+stub — both invocations are
  pinned by the harness; target source: `TextToSpeechAPI.java`,
  `termux-tts-engines.in`].
* alias `termux-tts-engines` (`:628-630`): → `tts.engines` ✔.
  alias `termux-tts-speak` (`:631-648`): options `-e -l -n -v -p -r -s` and
  positional text with stdin fallback — option parity with the pinned
  wrapper. Byte diff: upstream pipes `echo "$@"` (adds one trailing `\n`) or
  raw stdin (byte-exact) to the client; ours strips trailing newlines from
  both paths (command substitution). **FAIL (envelope; trailing-newline byte
  diff on argv)** [measured: shim+stub].

### 10. Toast

* native `termux-ai toast [TEXT]` (`:785-787`): stdin or args → `--es text`;
  envelope `{status:"shown",text}` (`:373-378`); stderr empty; exit 0.
  **PASS** [measured: shim+stub; payload keys source: `:373-378`].
* compat `termux-api Toast [am-args]` (`:546`): forwards; envelope vs
  upstream empty (`ToastAPI.java:32-46` writes no output). **FAIL
  (envelope)** [measured: shim+stub; target source: `ToastAPI.java`].
* alias `termux-toast [-s] [text]` (`:608-619`): `-s` ✔; upstream also has
  `-b/-c/-g` (colors/gravity) and reads stdin with a **3 s timeout**
  (`read -t 3 -d ''`, `termux-toast.in`) then trims trailing newlines; ours
  reads stdin to EOF with no timeout and trims via command substitution;
  `-b` exits 1 with the JSON usage error on stdout (measured). **FAIL
  (envelope; option subset; stdin timeout semantics)** [measured:
  shim+stub].

### 11. Torch

* native `termux-ai torch on|off` (`:805-812`): strict arg; envelope
  `{status,camera}` (`:444-456`) — the native contract keeps the envelope
  (by design). **PASS** [measured: shim+stub; payload keys source:
  `:444-456`].
* compat `termux-api Torch --ez enabled true` (`:547`): forwards; envelope
  vs empty (`TorchAPI.java:26` noteDone). **FAIL (envelope)** [measured:
  shim+stub; target source: `TorchAPI.java`].
* alias `termux-torch on|off` (`:620-627`): same flags as upstream, strict
  usage (upstream exits 1 on bad params; parity, measured `Usage` error
  exit 1 stderr empty). **FAIL (envelope)** [measured: shim+stub].

### 12. Vibrate

* native: **N/A — no native verb.** Bridge cmd `sys.vibrate`
  (`TermuxAiSocketServer.java:244`, handler `:315-328`); the native surface
  was intentionally not widened in this layer. Nothing to measure; not PASS.
* compat `termux-api Vibrate --ei duration_ms N [--ez force true]`
  (`:548`): forwards; envelope `{duration_ms}` vs upstream empty
  (`VibrateAPI.java:56` noteDone). **FAIL (envelope)** [measured:
  shim+stub; target source: `VibrateAPI.java`].
* alias `termux-vibrate [-d N]` (`:649-659`): default 1000 (upstream
  default is also 1000 in the wrapper; the app clamps 1-5000 ms,
  `:316-318`); upstream `-f` (force in silent mode) is rejected by ours
  (`Illegal option`, exit 1, stdout JSON, stderr empty — measured).
  **FAIL (envelope; `-f` missing)** [measured: shim+stub].

### 13. Volume — the confirmed compat-only dispatch defect (`:549`)

* native `termux-ai volume | volume STREAM V` (`:788-796`): 0 args →
  `audio.volume.list`, 2 args → `--es stream --ei volume`; envelope
  `{streams:[{stream, volume, max_volume} × 6 in streamMap order]}` /
  `{stream, volume, max_volume}` (`audioVolumeList :404-410`,
  `audioVolumeInfo :642-647`). **PASS** [measured: shim+stub; payload keys
  source: `:404-410,642-647`].
* compat `termux-api Volume [-a set-volume --es stream S --ei volume V]`:
  `run_broadcast "${1:-audio.volume.list}" "${@:2}"` (`:549`) — bare
  `Volume` works, but the upstream set invocation sends `cmd=-a`, prints
  the unknown-command envelope and exits 1 with empty stderr (exact,
  characterized). Extra defect layer: upstream `-a set-volume` is the
  **broadcast intent action** the VolumeAPI dispatches on
  (`VolumeAPI.java:31-37`), while this shim always sends action
  `com.termux.AI_COMMAND` (`:326`, `:5`), so even a correctly-shifted `-a`
  would need adapter work. Target stdout: list = indented array
  `[{stream, volume, max_volume} × 6]`, set = empty (`VolumeAPI.java:87`,
  `:46-47`). **FAIL (argv1 eaten as cmd; envelope; action semantics)**
  **[baseline-defect]** [measured: shim+stub].
* alias `termux-volume | termux-volume S V` (`:660-668`): stream/volume
  already correct (`--es stream S --ei volume V`, list with no args) — the
  pre-ai.21 defect on this path is gone; only the family envelope defect
  remains. Upstream validates the volume is numeric in the wrapper; ours
  does not (measured: `notanumber` forwarded, envelope rc 0). **FAIL
  (envelope; numeric validation)** [measured: shim+stub].

### 14. WifiConnectionInfo

* native `termux-ai wifi connection` (`:822-825`): envelope
  `{ssid,bssid,rssi,link_speed_mbps,network_id,supplicant_state}`
  (`:458-468`). **PASS** [measured: shim+stub; payload keys source:
  `:458-468`].
* compat `termux-api WifiConnectionInfo` (`:550`): envelope, not the
  upstream indented object whose non-null branch always serializes
  `bssid, frequency_mhz, ip, link_speed_mbps, mac_address, network_id,
  rssi, ssid, ssid_hidden, supplicant_state` in that order (null-info
  branch: `{"API_ERROR":"No current connection"}`, `WifiAPI.java:31-48`).
  **FAIL (envelope)** [measured: shim+stub; target source: `WifiAPI.java`].
* alias `termux-wifi-connectioninfo` (`:669-671`): same. **FAIL (envelope)**
  [measured: shim+stub].

## 5. Wrapper ownership (the fourth dimension)

Upstream reality at the pin: the 16 wrapper scripts are files of the
`termux-api` **package** in `$PREFIX/bin`, and `$PREFIX/libexec/termux-api`
is the package's C client (`CMakeLists.txt:90-127`). Target contract: the
app's installer updates **only files it owns**, never overwrites
package-owned wrappers, and replaces its own files atomically.

Baseline (`TermuxAiCliInstaller.java`):

* `MANAGED_HELPERS` (`:18-37`) lists exactly the 16 upstream wrapper names
  plus `termux-ai` and `termux-api`; `installCli` (`:85-98`) copies the shim
  over each name **unconditionally** — no dpkg ownership check, no hash
  comparison, no atomic replace (direct `FileOutputStream`).
* `installLibexecApi` (`:100-104`) overwrites `$PREFIX/libexec/termux-api`
  — the package's C client — with the shim, on every install.
* `writeManifest` (`:120-135`) records the managed names in
  `api-tools.json`, but nothing reads it to decide ownership.
* `deleteOldHelpers` (`:137-144`) removes the pre-ai helper names.

Status: **FAIL (ownership)** — 16 name collisions in `$PREFIX/bin` + 1 in
`$PREFIX/libexec` are overwritten without any ownership gate. Measured
statically by the harness (installer source × vendored pin list). The fix
(ownership-aware, atomic installer) is follow-up scope; this layer only records it.

## 6. Byte preservation — measured summary

Measured with byte-exact comparisons (`cmp`) on the alias entry, stdin path,
against what reaches the broadcast:

| Input | Reaches the bridge | Upstream pin behaviour | Status |
|---|---|---|---|
| `a\n\n` (trailing newlines) | `a` | clipboard-set stdin: `a\n\n` byte-exact | FAIL (stripped) |
| `a\0b` (NUL) | `ab` (NUL cannot survive an argv round-trip; one bash warning line on stderr) | clipboard-set stdin: byte-exact incl. NUL | FAIL |
| `àè 😀` UTF-8 | preserved | preserved | PASS |
| notification `-c` vs stdin | identical text either way | identical (content precedence) | PASS |
| toast/tts argv text | trailing `\n` stripped | tts argv: `echo "$@"` **adds** one `\n` | FAIL (byte diff) |

No byte-preservation claim on this baseline is derived from command
substitution alone: the harness writes the observed broadcast argv with a
byte-safe logger and compares files with `cmp`.

## 7. Regressions that stay

`AiRequestFramingTest` and `AiSocketHandlerTest` (`app/src/test`) are the
framing regression baseline and are untouched by this layer. The JVM contract
test added by this layer (`app/src/test/java/com/termux/app/terminal/ai/AiCompatContractTest.java`)
is written-but-not-executed in this environment (no Gradle/Android here); CI runs it.

## 8. Harness assumptions (declared)

* The stub `termux-am` emulates the am↔shim interface line
  `Broadcast completed: result=<n>, data="<payload>"` with a single-line
  payload and no escaping, matching what `run_broadcast`'s sed (`:335`)
  expects. Real-device byte behaviour of `am`/`termux-am` output is
  device-CI qualification material.
* The stub answers per bridge `cmd` from the synthetic golden table; an
  unknown `cmd` (e.g. `-e` from the Clipboard compat defect) gets
  `result=1` + the unknown-command envelope, which is exactly how the
  dispatch defects are made observable.
* The native golden payloads replayed by the stub are synthetic samples
  shaped after `TermuxAiSocketServer.java`: a green native cell certifies
  the client-side contract, not that the Android handler produced that
  payload or applied the extras.
* Golden schemas and serialization (2-space indent, trailing newline,
  represented branches) are normative in `GOLDEN_CONTRACT.md` and enforced
  by the harness "golden contract" section and by `AiCompatContractTest`.

## 9. Compat client (internal foundation, NOT activated) — measured

The compat layer added `app/src/main/java/.../terminal/ai/compat/`: a transient
v1 client (`TermuxApiCompatClient`, app_process main, standard Android deps
only), a strict am-args parser, and per-method request/result adapters as
pure classes behind a transport seam (`CompatTransport` +
`LocalSocketCompatTransport`). Nothing installs, links or activates it
(installer/launcher/alias wiring is a follow-up layer); `$PREFIX` is untouched.

**Measurement entry (new, distinct from the three of §2):**
`tests/compat/harness/compat-client.test.sh` compiles the real production
compat classes with functional org.json/JUnit stubs and drives them through
the controlled seam (`FakeCompatTransport`) with the real parser and
adapter — the stub `termux-am` of §8 never exercises a Java LocalSocket
client. Suite: 70 tests, 0 failures, plus 4 mutation counter-proofs inside
the harness — one wrong payload byte in an expectation, one wrong expected
exit code, one wrong expected stderr line, and one corrupted byte of a
frozen golden each turn it red. The frozen compat goldens are the ONLY
oracle of bytes, newline and format: `GoldenOracleCompatTest` replays every
golden in `fixtures/golden/compat` against its paired native input in
`fixtures/golden/s1b1-native` (same basename, the data object of an ok:true
envelope) and demands byte-identical stdout. This certifies the client-side
adapter logic only; the platform org.json, the real socket boundary and the
enriched backend payloads remain CI material.

Status below is **this entry vs the upstream compat contract, resized to
what is actually verified**: a method is GOLDEN only for the
branch(es) its frozen golden represents — byte-exact, newlines included.
Branches with no golden are covered by suite-synthetic expectations only,
and the golden values themselves are synthetic: no device measurement
happens here. The §4 baseline cells and the [baseline-defect] rows
stay as they are — the shipped asset and its entries are unchanged by the compat layer.

| # | Method | Golden-verified branch(es) | Suite-synthetic only (no golden) / gaps |
|---|---|---|---|
| 1 | AudioInfo | base-properties branch, 2-space indent + `\n` (`AudioInfo.json`) | branch without the additive AUDIOTRACK_* keys (optional at the adapter); `*_LOW_LATENCY`/`*_POWER_SAVING` pairs not represented upstream at the pin |
| 2 | BatteryStatus | the every-optional-measured branch in the upstream writer order, temperature double, trailing `\n` (`BatteryStatus.json`) | optional-key-absent branches (unmeasured key stays absent); `energy`/`cycle` never represented |
| 3 | Brightness | success = empty stdout (`Brightness-empty.txt`) | auto mode branch; nested WRITE_SETTINGS error → `E_BRIDGE_ERROR` exit 1 (suite-synthetic) |
| 4 | CameraInfo | two-camera back+front branch, full per-camera schema incl. jpeg/focals/AE/physical/capabilities (`CameraInfo.json`) | external-lens (int facing) branch — no golden, suite-synthetic; unknown AE/capability numbers — suite-synthetic (pin tables in `CameraInfoNamesTest`) |
| 5 | Clipboard | get = raw text no trailing newline (`Clipboard-get.txt`); set success = empty (`Clipboard-set-empty.txt`) | api_version=2 envelope, explicit-text>stdin precedence, empty-stdin-delivered (suite-synthetic); the `:535` defect stays closed on this entry |
| 6 | Notification | success = empty (`Notification-empty.txt`) | content precedence explicit>stdin>empty (suite-synthetic); wrapper newline loss NOT reconstructed (documented decision: the client preserves the newlines it receives and does not rebuild those the upstream wrapper already trimmed) |
| 7 | NotificationChannel | create + delete text lines byte-exact (`NotificationChannel-*.txt`) | the `ignored` (SDK<26) branch is an explicit error, exact upstream wording not pinned — no golden and no invented text |
| 8 | NotificationRemove | success = empty (`NotificationRemove-empty.txt`) | — |
| 9 | TextToSpeech | engines array without trailing newline (`TextToSpeech-LIST_AVAILABLE.json`); speak success = empty (`TextToSpeech-speak-empty.txt`) | `Missing text` nested error explicit (suite-synthetic); the `:539-545` defect stays closed here |
| 10 | Toast | success = empty (`Toast-empty.txt`) | `-b/-c/-g` unsupported options rejected (gap recorded, upstream color/gravity not implemented by the bridge) |
| 11 | Torch | success = empty (`Torch-empty.txt`) | nested no-torch error explicit (suite-synthetic) |
| 12 | Vibrate | success = empty (`Vibrate-empty.txt`) | `-f/--ez force` rejected: the bridge cannot honour it, refusing beats silently dropping (recorded divergence) |
| 13 | Volume | list = 6-stream array without trailing newline (`Volume-list.json`); set success = empty (`Volume-set-empty.txt`) | set-volume typing/validation and the `:549` closure (suite-synthetic) |
| 14 | WifiConnectionInfo | connected branch, all keys, quotes stripped (`WifiConnectionInfo.json`); unconnected → `{"API_ERROR": …}` (`WifiConnectionInfo-unconnected.json`) | redacted-ssid-as-measured branch (suite-synthetic); no golden for a measured-but-redacted field set |

Client-family properties measured: unknown method/option/type rejected with
exit 2 before any dispatch (0 exchanges); bridge/nested errors → empty
stdout, enumerated stderr, exit 1; malformed/oversize/truncated response →
exit 4; connect/EOF/timeout → exit 3; exactly one exchange after send (no
retry); stdin read incrementally and refused past 256 KiB; request line
capped at 256 KiB UTF-8 including escaping; the wire line never carries
`protocol`, `requestId` or `input` (v1-only, gate v2 untouched).

Native backend additive fields (`TermuxAiSocketServer.java`):
battery sticky-intent fields, measured AudioTrack, CameraCharacteristics
detail, WifiInfo detail — additive keys only, no new API family/grant/
permission, legacy native CLI shape unchanged. The baseline native golden files
still pin the pre-compat baseline shapes on purpose (regression anchors); the
enriched shapes feed the compat goldens through the paired
`fixtures/golden/s1b1-native` inputs of the byte oracle, and everything
here must still be re-measured on device in CI before any activation.
