# AICore Tier 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a minimal on-device AICore (Gemini Nano / Gemma 4) backend to `termux-ai` exposed as new verbs of the existing `TermuxAiSocketServer`, with batch + streaming support and a CLI wrapper, without an HTTP server, without a Foreground Service, and keeping `targetSdkVersion 28`.

**Architecture:** Extend the in-process `TermuxAiSocketServer` (Unix socket `ai.sock`) with a new `aicore.*` namespace (`info`, `generate`, `stream`, `cancel`). A single new class `AICoreBackend` wraps Google ML Kit GenAI Prompt API. The asset wrapper `assets/termux-ai` learns three subcommands. Socket protocol is upgraded once to support multi-line streaming responses. AnthMorph is **not** ported to Kotlin; it remains an independent Termux binary that may consume this surface in future work.

**Tech Stack:** Kotlin/Java (existing), Gradle, ML Kit GenAI Prompt API (`com.google.ai.edge.aicore:aicore:0.0.1-exp02`), Android `LocalSocket` infrastructure already used in termux-ai, JSON via `org.json` (already used).

**SDK constraint discovered during Gate 0 prep:** the AICore AAR declares `minSdkVersion 31` and `targetSdkVersion 35`. termux-ai is `minSdkVersion 24`, `targetSdkVersion 28`. We resolve this with:
- `<uses-sdk tools:overrideLibrary="com.google.ai.edge.aicore" />` in the app manifest (allows install on API 24-30 devices, AICore classes simply unused there).
- Runtime guard: every callsite checks `Build.VERSION.SDK_INT >= 31 && BuildConfig.AICORE_ENABLED` before touching AICore classes. On older devices `aicore.info` cleanly reports `available:false, reason:"Android < 12"`.
- The app `targetSdkVersion 28` is preserved (mandatory for Termux binary execution); manifest merge keeps the app's value.

**Build environment:** VPS3 (Linux), Android SDK at `/home/dag/android-sdk`, JDK 17, NDK 29.0.14206865, signing key under `.signing/`. Runtime test target: Pixel 9 Pro (Android 15, AICore present).

**Non-goals (Tier 1):**
- No HTTP server in app
- No Foreground Service
- No Anthropic-compatible `/v1/messages` endpoint
- No tool-calling protocol (Gemini Nano does not support it natively)
- No `count_tokens` Anthropic-compat (placeholder only)
- No companion-app fallback (deferred until Gate 0 result is known)

---

## File Structure

| Path | Status | Responsibility |
|------|--------|----------------|
| `app/build.gradle` | Modify | Add ML Kit GenAI dependency, BuildConfig flag `AICORE_ENABLED` |
| `app/src/main/AndroidManifest.xml` | Modify | Add `INTERNET` permission if missing |
| `app/src/main/java/com/termux/app/terminal/ai/AICoreBackend.java` | Create | Wraps `GenerativeModel`, exposes `isAvailable`, `info`, `generate`, `stream`, `cancel`, lifecycle |
| `app/src/main/java/com/termux/app/terminal/ai/AICoreStreamCallback.java` | Create | Functional interface for stream chunks |
| `app/src/main/java/com/termux/app/terminal/ai/TermuxAiSocketServer.java` | Modify | New `aicore.*` cases in dispatch; refactor to support multi-line streaming responses |
| `app/src/main/assets/termux-ai` | Modify | Add `aicore` subcommand block (info/generate/stream + flags + stdin) |
| `app/src/main/java/com/termux/app/terminal/ai/TermuxAiCliInstaller.java` | Modify (small) | No structural change unless wrapper needs new helper symlink (likely none) |
| `docs/AICORE_TIER1.md` | Create | User-facing usage doc |

---

## Branching strategy

All work happens on a new branch `feature/aicore-tier1` based on `develop`. No commits land on `develop` until Gate 1 is green. No push to `origin` or `github` happens unless the user asks for it.

---

## Gate 0 — Proof of Concept (AICore reachability with targetSdk 28)

### Task 0.1: Create feature branch

**Files:** none (git operation)

- [ ] **Step 1: Create and switch to branch**

```bash
cd ~/Dev/05_termux/termux-ai
git checkout -b feature/aicore-tier1
git status -sb
```

Expected: `## feature/aicore-tier1` header, clean tree.

- [ ] **Step 2: Verify clean baseline build**

```bash
cd ~/Dev/05_termux/termux-ai
./gradlew --no-daemon :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. APK present at `app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk`.

### Task 0.2: Add ML Kit GenAI dependency behind a build flag

**Files:**
- Modify: `app/build.gradle`

- [ ] **Step 1: Add BuildConfig flag and dependency**

In `app/build.gradle`, inside `defaultConfig` add (after existing `buildConfigField` for `TERMUX_PACKAGE_VARIANT`):

```groovy
buildConfigField "boolean", "AICORE_ENABLED", "true"
```

In the existing `dependencies` block (inside the `android { ... dependencies { } }` block), add:

```groovy
implementation "com.google.ai.edge.aicore:aicore:0.10.0"
```

- [ ] **Step 2: Build to verify dependency resolves with targetSdk 28**

```bash
cd ~/Dev/05_termux/termux-ai
./gradlew --no-daemon :app:assembleDebug 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL. If the dependency rejects targetSdk 28 with a manifest merge error or a compile-time check, capture the exact error and STOP — Gate 0 has failed. The fallback (companion app `com.termux.aicore`) is out of scope for this plan; a separate decision is required before re-entering Gate 0.

- [ ] **Step 3: Verify APK still embeds Termux bootstrap**

```bash
unzip -l ~/Dev/05_termux/termux-ai/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk \
  | grep -E "(bootstrap|aicore|libtermux)" | head
```

Expected: bootstrap zip present, ML Kit AICore classes/jni present, libtermux-bootstrap native lib present.

### Task 0.3: Minimal AICorePing class

**Files:**
- Create: `app/src/main/java/com/termux/app/terminal/ai/AICorePing.java`

- [ ] **Step 1: Write the ping class**

```java
package com.termux.app.terminal.ai;

import android.content.Context;
import android.util.Log;

import com.google.ai.edge.aicore.GenerationConfig;
import com.google.ai.edge.aicore.GenerativeModel;

public final class AICorePing {

    private static final String TAG = "AICorePing";

    private AICorePing() {}

    /**
     * Synchronously checks whether ML Kit AICore can be instantiated on this device.
     * Returns a short status string for logging / debug surface.
     * Does NOT call generateContent (network/model download could block).
     */
    public static String probe(Context context) {
        try {
            GenerationConfig config = new GenerationConfig.Builder()
                .setTemperature(0.2f)
                .setMaxOutputTokens(8)
                .build();
            GenerativeModel model = new GenerativeModel(config, null);
            String name = model.getClass().getSimpleName();
            Log.i(TAG, "AICore class accessible: " + name);
            return "ok:class=" + name;
        } catch (Throwable t) {
            Log.w(TAG, "AICore probe failed", t);
            return "err:" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
        }
    }
}
```

Note: the exact `GenerativeModel` constructor signature in `com.google.ai.edge.aicore:0.10.0` may differ; if compilation fails on this constructor call, use the simplest constructor available and adjust. The point of Gate 0 is only to confirm class loading, not to do real inference.

- [ ] **Step 2: Build**

```bash
cd ~/Dev/05_termux/termux-ai
./gradlew --no-daemon :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. If constructor signature is wrong, fix using the IDE-provided signature or upstream docs (`com.google.ai.edge.aicore:0.10.0`) and re-build.

- [ ] **Step 3: Manual smoke test on Pixel 9 Pro**

Install APK on the device and from a Termux shell:

```bash
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
adb logcat -c
adb shell am force-stop com.termux
adb shell am start -n com.termux/.app.TermuxActivity
adb logcat -s AICorePing:* TermuxApplication:*
```

The probe is not yet wired to a callsite — Task 1.1 will wire it. For now, verify only that the APK installs and Termux still launches normally and the bootstrap proceeds.

Expected: Termux opens, `$PREFIX` is initialised, no crash, no visible regression vs 0.118.0-ai.7.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle app/src/main/java/com/termux/app/terminal/ai/AICorePing.java
git commit -m "feat(aicore): add ML Kit GenAI dependency and probe class"
```

### Task 0.4: Wire AICorePing into TermuxApplication for one-shot probe

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxApplication.java`

- [ ] **Step 1: Find the existing onCreate**

```bash
grep -n "onCreate" /home/dag/Dev/05_termux/termux-ai/app/src/main/java/com/termux/app/TermuxApplication.java
```

- [ ] **Step 2: Add a single probe call after socket server start**

In `TermuxApplication.onCreate()`, after the line that calls `TermuxAiSocketServer.setup(this)` (or whatever bootstrap call exists for the AI bridge), add:

```java
if (com.termux.BuildConfig.AICORE_ENABLED) {
    String aicoreStatus = com.termux.app.terminal.ai.AICorePing.probe(this);
    android.util.Log.i("TermuxApplication", "AICore probe: " + aicoreStatus);
}
```

If `TermuxAiSocketServer.setup(this)` is not currently called from `TermuxApplication`, instead call the probe at the end of `onCreate()`. This single log line is the Gate 0 success signal.

- [ ] **Step 3: Build, install, observe logs**

```bash
cd ~/Dev/05_termux/termux-ai
./gradlew --no-daemon :app:assembleDebug
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
adb logcat -c
adb shell am force-stop com.termux
adb shell am start -n com.termux/.app.TermuxActivity
adb logcat -s TermuxApplication:* AICorePing:* | head -20
```

Expected: log line `TermuxApplication: AICore probe: ok:class=GenerativeModel` (or similar). If `err:`, capture exact exception and STOP — record as Gate 0 result.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/termux/app/TermuxApplication.java
git commit -m "feat(aicore): probe AICore availability at app startup"
```

### Task 0.5: Gate 0 decision

- [ ] **Decide based on probe result**

If probe returns `ok:`: Gate 0 passes. Proceed to Gate 1.
If probe returns `err:` with `ClassNotFoundException` or `NoClassDefFoundError`: dependency packaging failed; investigate before continuing.
If probe returns `err:` with `SecurityException` or AICore service not reachable: device-level issue, not code; revisit `INTERNET` permission and AICore system app status. Stop the plan, surface to user.

---

## Gate 1 — `aicore.info` and `aicore.generate` (batch)

### Task 1.1: Create AICoreBackend skeleton

**Files:**
- Create: `app/src/main/java/com/termux/app/terminal/ai/AICoreBackend.java`

- [ ] **Step 1: Write the backend skeleton**

```java
package com.termux.app.terminal.ai;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.ai.edge.aicore.GenerateContentResponse;
import com.google.ai.edge.aicore.GenerationConfig;
import com.google.ai.edge.aicore.GenerativeModel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class AICoreBackend {

    private static final String TAG = "AICoreBackend";
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private static volatile AICoreBackend instance;

    private final Context appContext;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile GenerativeModel model;
    private volatile boolean initialised;
    private volatile String lastInitError;

    private AICoreBackend(Context appContext) {
        this.appContext = appContext;
    }

    public static synchronized AICoreBackend get(@NonNull Context context) {
        if (instance == null) {
            instance = new AICoreBackend(context.getApplicationContext());
        }
        return instance;
    }

    public synchronized boolean ensureInitialised() {
        if (initialised) return true;
        try {
            GenerationConfig config = new GenerationConfig.Builder()
                .setTemperature(0.2f)
                .setMaxOutputTokens(256)
                .build();
            this.model = new GenerativeModel(config, null);
            this.initialised = true;
            this.lastInitError = null;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "init failed", t);
            this.lastInitError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    public boolean isAvailable() {
        return ensureInitialised();
    }

    @Nullable
    public String lastInitError() {
        return lastInitError;
    }

    public String generate(String prompt, GenerationConfig config, long timeoutMs) throws Exception {
        if (!ensureInitialised()) {
            throw new IllegalStateException("AICore not available: " + lastInitError);
        }
        final GenerativeModel m = (config == null) ? this.model : new GenerativeModel(config, null);
        Future<String> task = worker.submit(() -> {
            GenerateContentResponse resp = m.generateContent(prompt);
            return resp.getText() == null ? "" : resp.getText();
        });
        long t = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        return task.get(t, TimeUnit.MILLISECONDS);
    }
}
```

Note: the API surface of `com.google.ai.edge.aicore:0.10.0` may have minor differences (`getText()` may be `getText().get()` or `text` property). Adapt to the actual signature returned by the IDE/compiler. If `generateContent` is suspend-only (Kotlin coroutine) and not exposed as blocking Java API, switch to using the `Futures.toListenableFuture(...)` helper that the SDK provides, or fall back to the streaming variant with a single-collect.

- [ ] **Step 2: Build**

```bash
./gradlew --no-daemon :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/termux/app/terminal/ai/AICoreBackend.java
git commit -m "feat(aicore): add AICoreBackend skeleton with init and batch generate"
```

### Task 1.2: Wire `aicore.info` and `aicore.generate` into the socket dispatcher

**Files:**
- Modify: `app/src/main/java/com/termux/app/terminal/ai/TermuxAiSocketServer.java`

- [ ] **Step 1: Bump bridge version**

Locate `public static final String VERSION = "0.2.0";` and change to:

```java
public static final String VERSION = "0.3.0";
```

- [ ] **Step 2: Add new cases to `dispatch()` switch**

Inside `Client.dispatch()` switch, before the `default:` clause add:

```java
case "aicore.info":
    return ok(aicoreInfo());
case "aicore.generate":
    return ok(aicoreGenerate(args));
```

- [ ] **Step 3: Add the implementations**

Add these methods inside `Client` class (after the storage methods or wherever consistent with existing layout):

```java
private JSONObject aicoreInfo() throws Exception {
    AICoreBackend backend = AICoreBackend.get(context);
    boolean available = backend.isAvailable();
    JSONObject json = new JSONObject()
        .put("available", available)
        .put("model_id", "gemini-nano")
        .put("backend", "mlkit-aicore")
        .put("supports_streaming", true)
        .put("supports_tools", false);
    String err = backend.lastInitError();
    if (!available && err != null) json.put("error", err);
    return json;
}

private JSONObject aicoreGenerate(JSONObject args) throws Exception {
    String prompt = args.optString("prompt", "").trim();
    if (prompt.isEmpty()) throw new IllegalArgumentException("prompt is required");
    int maxTokens = Math.max(1, Math.min(args.optInt("max_tokens", 256), 4096));
    float temperature = (float) args.optDouble("temperature", 0.2);
    long timeoutMs = args.optLong("timeout_ms", 120_000L);

    com.google.ai.edge.aicore.GenerationConfig config =
        new com.google.ai.edge.aicore.GenerationConfig.Builder()
            .setTemperature(temperature)
            .setMaxOutputTokens(maxTokens)
            .build();

    long start = System.currentTimeMillis();
    String text = AICoreBackend.get(context).generate(prompt, config, timeoutMs);
    long latencyMs = System.currentTimeMillis() - start;

    return new JSONObject()
        .put("text", text == null ? "" : text)
        .put("model", "gemini-nano")
        .put("finish_reason", "stop")
        .put("usage", new JSONObject()
            .put("input_tokens_approx", prompt.length() / 4)
            .put("output_tokens_approx", text == null ? 0 : text.length() / 4))
        .put("latency_ms", latencyMs);
}
```

- [ ] **Step 4: Add the new commands to `apiList()` JSONArray**

In the `apiList()` method, add to the `commands` JSONArray:

```java
.put("aicore.info")
.put("aicore.generate")
```

- [ ] **Step 5: Build**

```bash
./gradlew --no-daemon :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/terminal/ai/TermuxAiSocketServer.java
git commit -m "feat(aicore): expose aicore.info and aicore.generate via socket dispatcher"
```

### Task 1.3: Add CLI subcommand `aicore` to the wrapper script

**Files:**
- Modify: `app/src/main/assets/termux-ai`

- [ ] **Step 1: Inspect current wrapper structure**

```bash
head -80 /home/dag/Dev/05_termux/termux-ai/app/src/main/assets/termux-ai
```

Note where the existing subcommand dispatch happens (likely a `case "$1" in ... esac` near the top).

- [ ] **Step 2: Add `aicore` subcommand**

Inside the main subcommand `case` add an entry that handles `aicore`:

```sh
aicore)
    shift
    sub="${1:-info}"
    shift || true
    case "$sub" in
        info)
            send_request '{"cmd":"aicore.info"}'
            ;;
        generate)
            prompt=""
            max_tokens=256
            temperature=0.2
            json_mode=0
            while [ $# -gt 0 ]; do
                case "$1" in
                    --max-tokens) max_tokens="$2"; shift 2;;
                    --temperature) temperature="$2"; shift 2;;
                    --json) json_mode=1; shift;;
                    --) shift; break;;
                    -) prompt="$(cat -)"; shift;;
                    *) prompt="$1"; shift;;
                esac
            done
            [ -z "$prompt" ] && prompt="$(cat -)"
            req=$(printf '{"cmd":"aicore.generate","args":{"prompt":%s,"max_tokens":%s,"temperature":%s}}' \
                "$(json_string "$prompt")" "$max_tokens" "$temperature")
            resp=$(send_request "$req")
            if [ "$json_mode" = "1" ]; then
                printf '%s\n' "$resp"
            else
                printf '%s\n' "$resp" | extract_text_field
            fi
            ;;
        *)
            printf 'unknown aicore subcommand: %s\n' "$sub" >&2
            return 1
            ;;
    esac
    ;;
```

The helpers `send_request`, `json_string`, and `extract_text_field` should already exist in the wrapper — if their names differ, adapt to the actual helper names used by the existing wrapper (read it first, do not invent symbols).

If `extract_text_field` does not exist, add a minimal helper:

```sh
extract_text_field() {
    # Reads JSON from stdin, prints data.text without external deps
    sed -n 's/.*"text":"\([^"]*\)".*/\1/p'
}
```

- [ ] **Step 3: Build (re-bundles the asset)**

```bash
./gradlew --no-daemon :app:assembleDebug 2>&1 | tail -10
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/termux-ai
git commit -m "feat(aicore): add aicore info|generate subcommand to wrapper"
```

### Task 1.4: Smoke test Gate 1 on the device

**Files:** none (manual test)

- [ ] **Step 1: Install APK**

```bash
adb install -r ~/Dev/05_termux/termux-ai/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```

- [ ] **Step 2: From Termux shell on device, run**

```bash
termux-ai aicore info
```

Expected JSON: `{"ok":true,"data":{"available":true,"model_id":"gemini-nano",...}}` (or `available:false` with a clear error).

- [ ] **Step 3: Try a generate**

```bash
termux-ai aicore generate "say hello in italian, 5 words"
```

Expected: a short Italian greeting on stdout, exit code 0.

- [ ] **Step 4: Capture results**

If both steps pass, Gate 1 is green. If `available:false`, capture exact error from `aicore.info` and stop.

- [ ] **Step 5: Commit nothing, just tag the milestone**

```bash
git tag -a aicore-tier1-gate1 -m "AICore Tier 1 Gate 1: info + generate working on Pixel 9 Pro"
```

---

## Gate 2 — Streaming + cancel

### Task 2.1: Refactor socket dispatch to support streaming responses

**Files:**
- Modify: `app/src/main/java/com/termux/app/terminal/ai/TermuxAiSocketServer.java`

The current `dispatch(String) -> String` returns a single response string. For streaming we need to write multiple lines on the same socket. The cleanest minimal refactor: add a sibling method that takes the `LocalClientSocket` directly for streaming verbs.

- [ ] **Step 1: Add a streaming dispatch path in `onClientAccepted`**

Replace the relevant logic in `Client.onClientAccepted`:

```java
@Override
public void onClientAccepted(@NonNull LocalSocketManager manager,
                             @NonNull LocalClientSocket clientSocket) {
    try {
        StringBuilder request = new StringBuilder();
        Error readError = clientSocket.readDataOnInputStream(request, false);
        if (readError != null) {
            String err = error("Read failed: " + readError.getMinimalErrorString()) + "\n";
            clientSocket.sendDataToOutputStream(err, false);
            return;
        }
        String raw = request.toString().trim();
        String cmd = peekCmd(raw);
        if ("aicore.stream".equals(cmd)) {
            handleStream(clientSocket, raw);
        } else if ("aicore.cancel".equals(cmd)) {
            String resp = handleCancel(raw) + "\n";
            clientSocket.sendDataToOutputStream(resp, false);
        } else {
            String resp = dispatch(raw) + "\n";
            clientSocket.sendDataToOutputStream(resp, false);
        }
    } finally {
        clientSocket.closeClientSocket(false);
    }
}

private String peekCmd(String raw) {
    try {
        return new JSONObject(raw.isEmpty() ? "{}" : raw).optString("cmd", "");
    } catch (Exception e) { return ""; }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew --no-daemon :app:assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL (`handleStream` and `handleCancel` will be unresolved — fix in next task).

This step is intentionally split: the dispatcher refactor is committed once the new methods exist (Task 2.2). Do NOT commit yet.

### Task 2.2: Implement streaming generation

**Files:**
- Modify: `app/src/main/java/com/termux/app/terminal/ai/AICoreBackend.java`
- Modify: `app/src/main/java/com/termux/app/terminal/ai/TermuxAiSocketServer.java`
- Create: `app/src/main/java/com/termux/app/terminal/ai/AICoreStreamCallback.java`

- [ ] **Step 1: Define the callback**

Create `AICoreStreamCallback.java`:

```java
package com.termux.app.terminal.ai;

public interface AICoreStreamCallback {
    void onChunk(String text);
    void onComplete(String finishReason, int outputCharsApprox);
    void onError(Throwable error);
}
```

- [ ] **Step 2: Add stream + cancel to AICoreBackend**

In `AICoreBackend.java` add:

```java
import java.util.concurrent.ConcurrentHashMap;
import com.google.ai.edge.aicore.GenerateContentResponse;

private final ConcurrentHashMap<String, Future<?>> inflight = new ConcurrentHashMap<>();

public void stream(String requestId,
                   String prompt,
                   GenerationConfig config,
                   AICoreStreamCallback cb) {
    if (!ensureInitialised()) {
        cb.onError(new IllegalStateException("AICore not available: " + lastInitError));
        return;
    }
    final GenerativeModel m = (config == null) ? this.model : new GenerativeModel(config, null);
    Future<?> task = worker.submit(() -> {
        try {
            int totalChars = 0;
            // ML Kit streaming API: returns a Publisher / Flow of GenerateContentResponse.
            // Adapt this loop to the actual blocking-iterable form exposed by 0.10.0.
            for (GenerateContentResponse chunk : m.generateContentStream(prompt).blockingIterable()) {
                String t = chunk.getText();
                if (t != null && !t.isEmpty()) {
                    cb.onChunk(t);
                    totalChars += t.length();
                }
            }
            cb.onComplete("stop", totalChars);
        } catch (Throwable t) {
            cb.onError(t);
        } finally {
            inflight.remove(requestId);
        }
    });
    inflight.put(requestId, task);
}

public boolean cancel(String requestId) {
    Future<?> f = inflight.remove(requestId);
    if (f == null) return false;
    return f.cancel(true);
}
```

If `m.generateContentStream(prompt)` does not expose a `.blockingIterable()` adapter in `aicore:0.10.0`, use `kotlinx.coroutines.flow.FlowKt.asPublisher(...)` or wrap with a synchronous adapter. The exact signature must match `aicore:0.10.0` — adjust at compile time.

- [ ] **Step 3: Add stream and cancel handlers in the socket server**

In `TermuxAiSocketServer.java` (inside `Client`):

```java
private void handleStream(LocalClientSocket socket, String raw) {
    final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    final boolean[] failed = { false };
    try {
        JSONObject req = new JSONObject(raw);
        JSONObject args = req.optJSONObject("args");
        if (args == null) args = new JSONObject();
        String prompt = args.optString("prompt", "").trim();
        if (prompt.isEmpty()) {
            socket.sendDataToOutputStream(error("prompt is required") + "\n", false);
            return;
        }
        String requestId = args.optString("request_id",
            "rid-" + System.currentTimeMillis() + "-" + Math.abs(prompt.hashCode()));
        int maxTokens = Math.max(1, Math.min(args.optInt("max_tokens", 256), 4096));
        float temperature = (float) args.optDouble("temperature", 0.2);

        com.google.ai.edge.aicore.GenerationConfig config =
            new com.google.ai.edge.aicore.GenerationConfig.Builder()
                .setTemperature(temperature)
                .setMaxOutputTokens(maxTokens)
                .build();

        // Initial event with the request_id so the client can later cancel.
        socket.sendDataToOutputStream(streamEvent("start", new JSONObject()
            .put("request_id", requestId)
            .put("model", "gemini-nano")) + "\n", false);

        AICoreBackend.get(context).stream(requestId, prompt, config, new AICoreStreamCallback() {
            @Override public void onChunk(String text) {
                try {
                    socket.sendDataToOutputStream(streamEvent("delta",
                        new JSONObject().put("text", text)) + "\n", false);
                } catch (Throwable t) { /* client probably closed */ }
            }
            @Override public void onComplete(String finishReason, int outputCharsApprox) {
                try {
                    socket.sendDataToOutputStream(streamEvent("stop", new JSONObject()
                        .put("finish_reason", finishReason)
                        .put("output_tokens_approx", outputCharsApprox / 4)) + "\n", false);
                } catch (Throwable t) { /* ignore */ }
                finally { done.countDown(); }
            }
            @Override public void onError(Throwable err) {
                failed[0] = true;
                try {
                    socket.sendDataToOutputStream(streamEvent("error", new JSONObject()
                        .put("message", err.getClass().getSimpleName() + ": " + err.getMessage())) + "\n", false);
                } catch (Throwable t) { /* ignore */ }
                finally { done.countDown(); }
            }
        });

        done.await();
    } catch (Throwable t) {
        try {
            socket.sendDataToOutputStream(error("stream failed: " + t.getMessage()) + "\n", false);
        } catch (Throwable ignored) { /* ignore */ }
    }
}

private String handleCancel(String raw) {
    try {
        JSONObject req = new JSONObject(raw);
        JSONObject args = req.optJSONObject("args");
        if (args == null) args = new JSONObject();
        String rid = args.optString("request_id", "");
        boolean cancelled = AICoreBackend.get(context).cancel(rid);
        return ok(new JSONObject()
            .put("request_id", rid)
            .put("cancelled", cancelled));
    } catch (Throwable t) {
        return error("cancel failed: " + t.getMessage());
    }
}

private String streamEvent(String type, JSONObject payload) throws Exception {
    return new JSONObject()
        .put("ok", true)
        .put("type", type)
        .put("data", payload)
        .toString();
}
```

- [ ] **Step 4: Add `aicore.stream` and `aicore.cancel` to apiList**

In `apiList()` add:

```java
.put("aicore.stream")
.put("aicore.cancel")
```

- [ ] **Step 5: Build**

```bash
./gradlew --no-daemon :app:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/termux/app/terminal/ai/AICoreBackend.java \
        app/src/main/java/com/termux/app/terminal/ai/AICoreStreamCallback.java \
        app/src/main/java/com/termux/app/terminal/ai/TermuxAiSocketServer.java
git commit -m "feat(aicore): add streaming generate and cancel via socket"
```

### Task 2.3: Add `stream` and `cancel` to the wrapper script

**Files:**
- Modify: `app/src/main/assets/termux-ai`

- [ ] **Step 1: Add `stream` subcommand**

Inside the existing `aicore)` block, add to its inner `case "$sub"`:

```sh
stream)
    prompt=""
    max_tokens=256
    temperature=0.2
    while [ $# -gt 0 ]; do
        case "$1" in
            --max-tokens) max_tokens="$2"; shift 2;;
            --temperature) temperature="$2"; shift 2;;
            --) shift; break;;
            -) prompt="$(cat -)"; shift;;
            *) prompt="$1"; shift;;
        esac
    done
    [ -z "$prompt" ] && prompt="$(cat -)"
    req=$(printf '{"cmd":"aicore.stream","args":{"prompt":%s,"max_tokens":%s,"temperature":%s}}' \
        "$(json_string "$prompt")" "$max_tokens" "$temperature")
    # Pipe socket output line-by-line, extract delta text fields, write to stdout.
    send_request_stream "$req" | awk '
        /"type":"delta"/ {
            match($0, /"text":"([^"]*)"/, m)
            if (m[1] != "") printf "%s", m[1]
        }
        /"type":"stop"/ { printf "\n"; exit 0 }
        /"type":"error"/ { print > "/dev/stderr"; exit 1 }
    '
    ;;
cancel)
    rid="$1"; shift || true
    [ -z "$rid" ] && { echo "request_id required" >&2; return 1; }
    send_request "$(printf '{"cmd":"aicore.cancel","args":{"request_id":%s}}' "$(json_string "$rid")")"
    ;;
```

`send_request_stream` is a helper that connects to the socket and pipes raw lines to stdout (does not collect into a single string). If the wrapper currently only has `send_request` that buffers, add a streaming variant that uses `nc -U "$SOCKET"` or equivalent already used elsewhere; reuse the existing socket-talk helper, just bypass the buffering step. Read the wrapper carefully before adding code.

- [ ] **Step 2: Build**

```bash
./gradlew --no-daemon :app:assembleDebug 2>&1 | tail -10
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/termux-ai
git commit -m "feat(aicore): add stream and cancel subcommands to CLI wrapper"
```

### Task 2.4: Smoke test Gate 2 on device

**Files:** none

- [ ] **Step 1: Install + run streaming**

```bash
adb install -r ~/Dev/05_termux/termux-ai/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
# In Termux on device:
termux-ai aicore stream "count from one to five, one number per line"
```

Expected: numbers appear progressively on stdout (visible delay between each), final newline, exit 0.

- [ ] **Step 2: Test cancel**

```bash
# In one Termux session
termux-ai aicore stream "write a long story" &
PID=$!
sleep 2
# In another session, find the request_id from the socket dispatcher logs and:
termux-ai aicore cancel "<rid>"
wait $PID || true
```

Expected: stream stops shortly after cancel, exit code may be non-zero (cancelled).

- [ ] **Step 3: Tag**

```bash
git tag -a aicore-tier1-gate2 -m "AICore Tier 1 Gate 2: streaming + cancel working"
```

---

## Gate 3 — Documentation and version bump

### Task 3.1: Bump app version

**Files:**
- Modify: `app/build.gradle`

- [ ] **Step 1: Bump versionName + versionCode**

In `app/build.gradle` change:

```groovy
versionCode 122
versionName "0.118.0-ai.7"
```

to:

```groovy
versionCode 123
versionName "0.118.0-ai.8-aicore-tier1"
```

- [ ] **Step 2: Build release**

```bash
./gradlew --no-daemon :app:assembleRelease 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, release APK present and signed.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle
git commit -m "chore: bump version to 0.118.0-ai.8-aicore-tier1"
```

### Task 3.2: Add user-facing documentation

**Files:**
- Create: `docs/AICORE_TIER1.md`

- [ ] **Step 1: Write the doc**

```markdown
# AICore Tier 1 (Gemini Nano on-device)

Termux AI exposes a minimal on-device generative AI surface backed by Google ML Kit AICore (Gemini Nano on Pixel 8/9 series, Gemma 4 when available).

## Requirements

- Pixel 8 Pro / Pixel 9 / Pixel 9 Pro (or Samsung S24+) with AICore system app installed.
- Android 14+ recommended; this build targets classic SDK 28 for Termux binary compatibility.
- First use may trigger model download (~hundreds of MB) managed by AICore itself.

## CLI usage

```sh
termux-ai aicore info
termux-ai aicore generate "explain this log line briefly: ..."
termux-ai aicore stream "write a haiku about offline AI"
termux-ai aicore cancel <request_id>
```

Flags: `--max-tokens N`, `--temperature F`, `--json` (full response on `generate`).

## Realistic use cases

- Personal Telegram/Signal bot replies (1-3 sentences)
- Notification / mail classification on-device
- Voice assistant with TTS-while-generating (combine with `termux-ai tts speak`)
- Privacy pre-processing before calling cloud
- Offline scripting helper

Not suitable for: full Claude Code / codex agentic sessions (latency, context length, no native tool calling).

## Socket protocol (programmatic)

Commands available on `ai.sock`:

- `{"cmd":"aicore.info"}` → discovery
- `{"cmd":"aicore.generate","args":{"prompt":"...","max_tokens":256,"temperature":0.2}}` → batch
- `{"cmd":"aicore.stream","args":{"prompt":"...","request_id":"..."}}` → multi-line: `start`, `delta`*, `stop` (or `error`)
- `{"cmd":"aicore.cancel","args":{"request_id":"..."}}` → cancel an in-flight stream

Each socket line is a JSON object. Streaming writes one event per line; the client reads until a `stop` or `error` event.
```

- [ ] **Step 2: Commit**

```bash
git add docs/AICORE_TIER1.md
git commit -m "docs: add AICore Tier 1 user guide"
```

### Task 3.3: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a single short section**

Append after the "Termux:API Compatibility" section:

```markdown
## On-device AICore (Tier 1)

When installed on a Pixel 8/9 series device with AICore, `termux-ai aicore` provides a minimal generative AI surface (Gemini Nano / Gemma 4) for bots, scripts, and voice assistants. See `docs/AICORE_TIER1.md`.

This is intentionally a small surface: no Anthropic-compatible HTTP server in the app, no Foreground Service, classic Termux SDK 28 preserved. Heavy coding-agent workloads (Claude Code, codex-vl) still belong on cloud backends — the on-device path targets short, frequent requests.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mention AICore Tier 1 in README"
```

### Task 3.4: Final verification + branch readiness

- [ ] **Step 1: Re-run debug + release builds**

```bash
cd ~/Dev/05_termux/termux-ai
./gradlew --no-daemon clean :app:assembleDebug :app:assembleRelease 2>&1 | tail -20
```

Expected: both BUILD SUCCESSFUL. Release APK signed (verify with `apksigner verify` if available).

- [ ] **Step 2: Show commit log of the branch**

```bash
git log --oneline develop..feature/aicore-tier1
```

Expected: ~9 commits in clear feat/docs/chore order.

- [ ] **Step 3: Tag**

```bash
git tag -a aicore-tier1-complete -m "AICore Tier 1 complete"
```

- [ ] **Step 4: Stop**

The branch is ready for review. Do **not** push to `origin` or `github`, do **not** merge to `develop`. Ask the user before any action that affects the remote.

---

## Risk register (Tier 1-specific)

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| ML Kit AICore dependency rejects targetSdk 28 | Medium | Gate 0 catches before any other work |
| `aicore:0.10.0` API surface differs from this plan's snippets | High | Compile-time fix; signatures shown here are best-effort, adjust to actual SDK |
| Streaming iterator API requires Kotlin coroutines | Medium | Add `kotlinx-coroutines-core` if needed; keep Java callsite via `runBlocking` adapter |
| Socket multi-write breaks existing single-shot clients | Low | Streaming is opt-in via verb name; non-stream verbs unchanged |
| AICore unavailable on test device | Medium | `aicore.info` reports `available:false` cleanly; CLI prints the error string |
| Battery drain from long generations | Low (Tier 1) | Single-thread worker, no Foreground Service, generations are short-lived |

---

## Out of scope (deferred to Tier 2 if ever)

- Anthropic-compatible HTTP server (`/v1/messages` etc.)
- AnthMorph Kotlin port (not happening; AnthMorph stays Rust)
- AnthMorph "aicore-termux-socket" backend profile (would belong in AnthMorph repo, not termux-ai)
- Tool calling protocol
- `count_tokens` Anthropic-compat (placeholder uses `len/4`)
- Foreground Service / background generation
- `targetSdkVersion` upgrade (incompatible with Termux binary execution)
