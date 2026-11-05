# Golden contract — what each fixture asserts, and where it comes from

Every golden under `fixtures/golden/` is **synthetic**: invented values, no
real SMS, contact, call log, notification, network or device data. What is
normative is the **schema** (key names, casing, types, mandatory fields, value
domains) and the **serialization** (indentation, trailing newline), both read
from the pinned sources, never invented:

* app-side output contracts: upstream `termux-api` at `760c1777`
  (see `UPSTREAM_PIN.md`);
* the native envelope family: this repo's app source at the contract baseline.

The harness (`harness/termux-api-compat.test.sh`, section "golden contract")
and `AiCompatContractTest` (JVM, written-not-executed locally) both enforce
the schemas below: they fail on a wrong key, wrong casing, wrong type or a
missing mandatory field — not merely on "is valid JSON".

## 1. Serialization rules read at the pin

`ResultReturner.ResultJsonWriter.writeResult`
(`termux-api/util/ResultReturner.java:164-174`) wraps every JSON payload in
`android.util.JsonWriter` with **`setIndent("  ")`** and appends one newline
via `out.println()` after `writeJson` returns. Therefore every JSON payload
of the compat family is **2-space indented**. The trailing newline lands only
when the handler does not close the writer inside `writeJson`:

| Payload | Trailing `\n` | Reason (anchor at the pin) |
|---|---|---|
| BatteryStatus, AudioInfo, CameraInfo, WifiConnectionInfo | yes | `writeJson` never closes; `println()` at `ResultReturner.java:170` runs on an open writer |
| TTS engines list (LIST_AVAILABLE) | **no** | try-with-resources closes the `JsonWriter` (`TextToSpeechAPI.java:125-140`); `JsonWriter.close()` closes the underlying `PrintWriter`, so the `out.println()` at `:138` is swallowed |
| Volume list | **no** | handler calls `out.close()` on the `JsonWriter` (`VolumeAPI.java:87`); same swallow of `ResultReturner.java:170` |
| NotificationChannel create/delete | yes | plain text via `out.println(...)` (`NotificationAPI.java:99,109`), no JsonWriter involved |
| Clipboard get (v1 and v2) | no | `out.print(text)` only (`ClipboardAPI.java:51,75`) |

The no-newline rows are source-derived (JsonWriter/PrintWriter close
semantics). Real-device byte qualification of `am`/`termux-am` output stays
device-CI material, exactly like `MATRIX.md` §8 says for the transport line.

## 2. Compat goldens ((the target payloads))

### AudioInfo.json — `AudioAPI.java:68-88`

Represented branch: base properties only — the optional
`*_LOW_LATENCY` / `*_POWER_SAVING` pairs are emitted "all or nothing" when
the measured track differs (`AudioAPI.java:75-82`), so they are absent here.

Mandatory keys and types: `PROPERTY_OUTPUT_SAMPLE_RATE` (string —
`AudioManager.getProperty` returns String), `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`
(string), `AUDIOTRACK_SAMPLE_RATE` (int), `AUDIOTRACK_BUFFER_SIZE_IN_FRAMES`
(int), `BLUETOOTH_A2DP_IS_ON` (bool), `WIREDHEADSET_IS_CONNECTED` (bool).

### BatteryStatus.json — `BatteryStatusAPI.java:149-168`

Represented branch: a healthy charging battery where every optional property
resolves. Always-emitted keys (non-null at this branch):
`present` (bool), `health` (string), `plugged` (string), `status` (string),
`temperature` (double, 1 decimal), `voltage` (int), `level` (int), `scale`
(int). Optional keys included here: `technology` (string), `current` (int),
`current_average` (int), `percentage` (int), `charge_counter` (int).
Optional keys **not** represented: `energy` (long, requires
BATTERY_PROPERTY_ENERGY_COUNTER), `cycle` (int, SDK >= 34 only,
`BatteryStatusAPI.java:164-167`). Enum domains: `health` in
{COLD, DEAD, GOOD, OVERHEAT, OVER_VOLTAGE, UNKNOWN, UNSPECIFIED_FAILURE},
`plugged` in {UNPLUGGED, PLUGGED_AC, PLUGGED_DOCK, PLUGGED_USB,
PLUGGED_WIRELESS}, `status` in {CHARGING, DISCHARGING, FULL, NOT_CHARGING,
UNKNOWN} (`BatteryStatusAPI.java:42-119`).

### CameraInfo.json — `CameraInfoAPI.java:31-145`

Represented branch: two cameras, back then front, with every per-camera field
the handler always emits. Root is an **array**. Per camera, in emit order:
`id` (string), `facing` (**lowercase** `"back"`/`"front"`, `:42-45` — an int
only for a non-front/back lens), `jpeg_output_sizes` (array of
{`width` int, `height` int}), `focal_lengths` (array of numbers),
`auto_exposure_modes` (array of `CONTROL_AE_MODE_*` strings, `:66-87`),
`physical_size` ({`width`, `height`} numbers, `:91-93`), `capabilities`
(array of the lowercase token strings enumerated at `:97-139`). Synthetic
floats are exactly representable (halves/quarters) so the file equals the
`JsonWriter` serialization of the values.

### TextToSpeech-LIST_AVAILABLE.json — `TextToSpeechAPI.java:124-140`

Represented branch: `"LIST_AVAILABLE".equals(engine)` on a device with one
engine. Array of {`name` string, `label` string, `default` bool}; indent "  "
set at `:126`; **no trailing newline** (§1).

### Volume-list.json — `VolumeAPI.java:95-116`

Represented branch: the list action (no `set-volume` intent action). Array in
the fixed `streamMap` order: alarm, music, notification, ring, system, call
(`:20-27`). Per stream: `stream` (string), `volume` (int), `max_volume`
(int). **No trailing newline** (§1).

### WifiConnectionInfo.json — `WifiAPI.java:31-48`

Represented branch: `getConnectionInfo()` non-null (the connected case).
Object in emit order: `bssid` (string), `frequency_mhz` (int), `ip` (string,
IPv4 formatted), `link_speed_mbps` (int), `mac_address` (string),
`network_id` (int), `rssi` (int), `ssid` (string, quotes stripped),
`ssid_hidden` (bool), `supplicant_state` (string, e.g. COMPLETED).

### WifiConnectionInfo-unconnected.json — `WifiAPI.java:34-35`

Optional branch example: the `info == null` case, whose whole object is
`{"API_ERROR": "No current connection"}`. Not a success payload: a follow-up
adapter must map this branch, not drop it.

### NotificationChannel-create.txt / NotificationChannel-delete.txt — `NotificationAPI.java:83-117`

Upstream stdout for the channel method is **not empty**: create prints
`Created channel with id "<id>" and name "<name>".`, delete prints
`Deleted channel with id "<id>".` (plus the error/SDK<26 lines at
`:93,104,112,115` — not represented). Plain text + one trailing newline.

### Empty-stdout action targets

`Brightness-empty.txt`, `Clipboard-set-empty.txt`, `Notification-empty.txt`,
`NotificationRemove-empty.txt`, `TextToSpeech-speak-empty.txt`,
`Toast-empty.txt`, `Torch-empty.txt`, `Vibrate-empty.txt`,
`Volume-set-empty.txt` are **zero bytes** and that is the contract:
`BrightnessAPI.java:33`, `TorchAPI.java:26`, `VibrateAPI.java:56` and the
notification-remove path (`NotificationAPI.java:332`) use `noteDone`
(no writer output); notification show and toast write results without
printing; clipboard set writes nothing on the set path
(`ClipboardAPI.java:37-39,64-69`); the TTS speak success path prints nothing
(`TextToSpeechAPI.java:142-199`).

### Clipboard-get.txt

Raw clip text, exactly what `out.print(text)` emits: inner newlines allowed,
**no trailing newline** added by the handler (§1).

## 3. Native goldens (this app's own envelope family)

Shape source: `app/src/main/java/com/termux/app/terminal/ai/TermuxAiSocketServer.java`
at the contract baseline; the envelope wrapper is `{"ok":true,"data":…}`
(`TermuxAiSocketServer.java:699`). One JSON line + one `\n` (printed by
`run_broadcast`, `assets/termux-ai:343`). The harness stub replays these
files as canned bridge answers; that certifies the **client-side** contract
(dispatch, envelope framing, exit code) — the payload shapes themselves are
read from the app source, not measured on Android.

| Golden | Payload anchor | Fixed by the compat layer |
|---|---|---|
| battery.json | `battery()` `:290-297` {level,charging,current_ua,charge_uah} | — |
| clipboard-get/set | `:299-313` {text} / {status:"ok"} | — |
| vibrate.json | `:315-328` {duration_ms} | — |
| notification-show/channel/remove | `:330-371` | — |
| toast.json | `:373-378` {status:"shown",text} | — |
| audio-info.json | `:380-390` | — |
| volume-set.json, volume-list.json | `audioVolumeInfo()` `:642-647`: {stream, **volume, max_volume**} | yes — the fixture previously carried {stream,max,index}, which the app never emits |
| brightness.json | `:412-427` {mode[,brightness]} | — |
| camera-info.json | `cameraInfo()` `:429-442` {cameras:[{id,facing,flash_available}]} | — |
| torch.json | `:444-456` | — |
| wifi-connection.json | `:458-468` | — |
| tts-engines.json | `ttsEngines()` `:470-480` {engines:[{name,label,default}]} | — |
| tts-speak.json | `ttsSpeak()` `:482-503`: {**queued** bool, **chars** int} | yes — the fixture previously carried {status:"spoken"}, which the app never emits |
| error-*.json | `fail_json` envelope shape `assets/termux-ai:43-48` | — |

**compat-client note (not measured on device here):** the compat layer added *additive*
keys to four native backends — battery (sticky ACTION_BATTERY_CHANGED:
present/health/plugged/status/temperature/voltage/technology/raw_level/
scale/percentage/current_average), audioInfo (measured AudioTrack:
audiotrack_sample_rate, audiotrack_buffer_size_in_frames), cameraInfo
(facing_id, jpeg_output_sizes, focal_lengths, auto_exposure_modes,
physical_size, capabilities), wifiConnection (frequency_mhz, ip,
mac_address, ssid_hidden). The legacy keys and the envelope are unchanged
and the golden files above still pin the pre-compat baseline shapes as
regression anchors; the enriched shapes are asserted synthetically by the
compat client JVM suite (`app/src/test/.../compat/TermuxApiCompatClientTest`) and
must be byte-qualified on a device in CI before activation (MATRIX §9).

### s1b1-native — the paired native inputs of the compat goldens

`fixtures/golden/s1b1-native/<Name>.json` holds, for EVERY compat golden
`fixtures/golden/compat/<Name>.{json,txt}`, the `data` object of an
`ok:true` envelope that must drive the real client to that golden's exact
bytes. One file per golden, same basename, no orphans on either side
(enforced by `GoldenOracleCompatTest`). The Baseline `native/*.json` files
stay untouched as pre-compat regression anchors; these inputs are the
enriched counterparts. A golden file itself may only change with a proof
at the upstream pin — the oracle asserts its bytes literally (newline and
format included), and the harness mutation counter-proof corrupts one
golden byte in a scratch copy to prove the comparison is live.

### s1b1-native producer sources

Every input below is a **COMPLETE object of the branch it represents** —
fields, types and relations are exactly what the named producer method
emits on that branch; there are **no undeclared projections**. (Previously, Torch omitted the `camera` and Notification the `id`/`channel` the
producers always emit, and CameraInfo carried a facing/facing_id pair no
single LENS_FACING value can produce: all three are now aligned.) The
pure producer relations are asserted per file by
`app/src/test/.../compat/NativeFixtureContractTest` (run by
`harness/compat-client.test.sh`), and the producer side of the
facing/facing_id relation plus the torch camera choice are exercised with
controlled synthetic measurements by `harness/camera-producer.test.sh`.

| Input | Producer anchor (TermuxAiSocketServer.java) | Emission the input mirrors |
|---|---|---|
| AudioInfo.json | `audioInfo()` | `getProperty` strings + 4 manager booleans + the SDK≥M AudioTrack pair |
| BatteryStatus.json | `battery()` | always-measured {level,charging,current_ua,charge_uah} + the sticky extras (charging is always a boolean) |
| Brightness-empty.json | `brightness()` | manual branch: {mode:"manual", brightness} |
| CameraInfo.json | `cameraInfo()` + `cameraFacingName()` | per camera: id/facing/flash_available always; facing_id iff LENS_FACING was read, the SAME value the facing name maps to (0=front, 1=back, 2=external — the `CameraCharacteristics.LENS_FACING_*` constants); additive arrays as emitted |
| Clipboard-get.json | `clipboardGet()` | {text} |
| Clipboard-set-empty.json | `clipboardSet()` | {status:"ok"} |
| Notification-empty.json | `notify()` | {status:"posted", id, channel} — id/channel are ALWAYS posted |
| NotificationChannel-create.json | `notificationChannel()` | created branch: {status, id, name} |
| NotificationChannel-delete.json | `notificationChannel()` | deleted branch: {status, id} |
| NotificationRemove-empty.json | `notificationRemove()` | {status:"removed", id} |
| TextToSpeech-LIST_AVAILABLE.json | `ttsEngines()` | {engines:[{name,label,default}]} |
| TextToSpeech-speak-empty.json | `ttsSpeak()` | {queued bool, chars int} |
| Toast-empty.json | `toast()` | {status:"shown", text} |
| Torch-empty.json | `torch()` | ok branch: {status:"on"/"off", camera} — camera is ALWAYS the first FLASH_INFO_AVAILABLE id |
| Vibrate-empty.json | `vibrate()` | {duration_ms} clamped to [1, 5000] |
| Volume-list.json | `audioVolumeList()` | streams in the fixed order alarm, music, notification, ring, system, call; volume clamped to [0, max] |
| Volume-set-empty.json | `audioVolumeInfo()` | {stream, volume, max_volume} |
| WifiConnectionInfo.json | `wifiConnection()` | measured-info branch: always ssid/rssi/link_speed/network_id/supplicant_state + frequency/ip/mac/hidden |
| WifiConnectionInfo-unconnected.json | `wifiConnection()` | null-info branch: EXACTLY wifi_info_present/ssid/bssid/rssi/link_speed_mbps/network_id/supplicant_state |

These are synthetic values that respect the emission contract; they are
not device captures, and real-device qualification stays CI material.

## 4. Verification

* Harness section "golden contract" validates every compat JSON golden
  against the schemas above (keys, casing, types, domains, volume order,
  serialization form) and every native envelope against the app-source
  shapes; it fails on any wrong key/casing/type/missing mandatory field.
* The same schemas are asserted by `AiCompatContractTest` (JVM) so CI keeps
  them honest even without the shell harness.
* Byte oracle: `GoldenOracleCompatTest` (run by
  `harness/compat-client.test.sh`) replays every compat golden against its
  `s1b1-native` pair and demands byte-identical stdout, empty stderr,
  exit 0. The harness mutation counter-proofs extend to a wrong expected
  exit code, a wrong expected stderr line and one corrupted golden byte.
