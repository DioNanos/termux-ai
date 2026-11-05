package com.termux.app.terminal.ai.compat;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The 14-method allowlist (the client exposes only these,
 * never an arbitrary cmd toward the whole bridge) and the per-method request
 * adapter: compat request → native cmd/args. The native CLI format never
 * changes; this class only *builds* native requests.
 *
 * Option policy: only the extras the native bridge actually consumes are
 * accepted. An upstream option the bridge would silently ignore is rejected
 * with {@link CompatError#E_UNSUPPORTED_OPTION} before dispatch — a silently
 * dropped {@code --action} or {@code -f} would be a false compatibility.
 * The gaps are recorded per method in the mapping table (see the
 * layer report), not hidden here.
 */
public enum CompatMethod {
    AUDIO_INFO("AudioInfo", false, setOf()),
    BATTERY_STATUS("BatteryStatus", false, setOf()),
    BRIGHTNESS("Brightness", false, setOf("--ei brightness", "--ez auto")),
    CAMERA_INFO("CameraInfo", false, setOf()),
    CLIPBOARD("Clipboard", true, setOf("--es api_version", "--ez set", "--es text")),
    NOTIFICATION("Notification", true, setOf("--es title", "--es id", "--es channel", "--ez ongoing", "--es text")),
    NOTIFICATION_CHANNEL("NotificationChannel", false, setOf("--ez delete", "--es id", "--es name")),
    NOTIFICATION_REMOVE("NotificationRemove", false, setOf("--es id")),
    TEXT_TO_SPEECH("TextToSpeech", true, setOf("--es engine", "--es text", "--es language",
        "--es region", "--es variant", "--ef pitch", "--ef rate", "--es stream")),
    TOAST("Toast", true, setOf("--ez short", "--es text")),
    TORCH("Torch", false, setOf("--ez enabled")),
    VIBRATE("Vibrate", false, setOf("--ei duration_ms")),
    VOLUME("Volume", false, setOf("--es stream", "--ei volume")),
    WIFI_CONNECTION_INFO("WifiConnectionInfo", false, setOf());

    public final String upstreamName;
    public final boolean readsStdin;
    private final Set<String> allowedFlags;

    CompatMethod(String upstreamName, boolean readsStdin, Set<String> allowedFlags) {
        this.upstreamName = upstreamName;
        this.readsStdin = readsStdin;
        this.allowedFlags = allowedFlags;
    }

    private static Set<String> setOf(String... flags) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(flags)));
    }

    public static CompatMethod byName(String name) {
        for (CompatMethod m : values()) {
            if (m.upstreamName.equals(name)) return m;
        }
        return null;
    }

    public static final class BuildException extends Exception {
        public final CompatError code;
        public BuildException(CompatError code, String detail) {
            super(detail);
            this.code = code;
        }
    }

    /**
     * The validated branch of one request: the result adapter
     * validates against THIS branch's schema instead of guessing the
     * operation from the payload shape.
     */
    public enum Branch {
        BATTERY_STATUS, AUDIO_INFO, CAMERA_INFO, WIFI_CONNECTION_INFO,
        CLIPBOARD_GET, CLIPBOARD_SET, BRIGHTNESS_SET, NOTIFICATION_SHOW,
        CHANNEL_CREATE, CHANNEL_DELETE, NOTIFICATION_REMOVE,
        TTS_ENGINES, TTS_SPEAK, TOAST_SHOW, TORCH_SET, VIBRATE,
        VOLUME_LIST, VOLUME_SET
    }

    /** A native request: bridge cmd plus its args object. */
    public static final class NativeRequest {
        public final String cmd;
        public final JSONObject args;
        public NativeRequest(String cmd, JSONObject args) {
            this.cmd = cmd;
            this.args = args;
        }
    }

    /**
     * The validated request after the first phase: everything about argv,
     * options and the branch is decided here, including whether stdin is
     * needed at all. Stdin is only read afterwards, and only when
     * {@code readsStdin} is true — Clipboard get and the TTS engines list
     * never touch it, and an explicit content extra never conditions it on
     * a pipe that may never produce input or close.
     */
    public static final class Plan {
        public final Branch branch;
        public final String cmd;
        public final JSONObject args;
        public final boolean readsStdin;
        /** The args key the stdin text fills when read; null when unused. */
        final String stdinKey;
        Plan(Branch branch, String cmd, JSONObject args, boolean readsStdin, String stdinKey) {
            this.branch = branch;
            this.cmd = cmd;
            this.args = args;
            this.readsStdin = readsStdin;
            this.stdinKey = stdinKey;
        }
    }

    /**
     * Phase 1: validate method, options, action and branch without touching
     * stdin, and decide whether stdin is actually needed for this request.
     */
    public Plan plan(CompatAmArgs amArgs) throws BuildException, org.json.JSONException {
        // Option allowlist: reject anything the pinned wrappers do not emit
        // for this method before touching the transport or stdin.
        for (String flag : amArgs.extras.keySet()) {
            String kind = flagKindFor(amArgs, flag);
            if (!allowedFlags.contains(kind + " " + flag)) {
                throw new BuildException(CompatError.E_UNSUPPORTED_OPTION, flag);
            }
        }
        switch (this) {
            case AUDIO_INFO:
                requireNoAction(amArgs);
                return new Plan(Branch.AUDIO_INFO, "audio.info", new JSONObject(), false, null);
            case BATTERY_STATUS:
                requireNoAction(amArgs);
                return new Plan(Branch.BATTERY_STATUS, "sys.battery", new JSONObject(), false, null);
            case CAMERA_INFO:
                requireNoAction(amArgs);
                return new Plan(Branch.CAMERA_INFO, "camera.info", new JSONObject(), false, null);
            case WIFI_CONNECTION_INFO:
                requireNoAction(amArgs);
                return new Plan(Branch.WIFI_CONNECTION_INFO, "wifi.connection", new JSONObject(), false, null);
            case BRIGHTNESS: {
                requireNoAction(amArgs);
                JSONObject args = new JSONObject();
                Boolean auto = (Boolean) amArgs.extras.get("auto");
                if (auto != null) args.put("auto", auto);
                Integer level = (Integer) amArgs.extras.get("brightness");
                if (level != null) args.put("brightness", level);
                if (args.length() == 0) throw new BuildException(CompatError.E_BAD_USAGE,
                    "Brightness needs --ei brightness N or --ez auto true|false");
                return new Plan(Branch.BRIGHTNESS_SET, "display.brightness", args, false, null);
            }
            case CLIPBOARD: {
                requireNoAction(amArgs);
                Boolean set = (Boolean) amArgs.extras.get("set");
                String apiVersion = (String) amArgs.extras.get("api_version");
                if (apiVersion != null && !apiVersion.trim().equals("2")) {
                    throw new BuildException(CompatError.E_BAD_VALUE, "api_version " + apiVersion);
                }
                if (set == null || !set) return new Plan(Branch.CLIPBOARD_GET, "sys.clipboard.get", new JSONObject(), false, null);
                String explicit = (String) amArgs.extras.get("text");
                if (explicit != null) {
                    // explicit beats stdin; stdin is not read at all
                    return new Plan(Branch.CLIPBOARD_SET, "sys.clipboard.set", new JSONObject().put("text", explicit), false, null);
                }
                // the pinned wrapper pipes the content: read stdin byte-exact
                return new Plan(Branch.CLIPBOARD_SET, "sys.clipboard.set", new JSONObject(), true, "text");
            }
            case NOTIFICATION: {
                requireNoAction(amArgs);
                JSONObject args = amArgs.toJsonArgs();
                if (args.has("text")) return new Plan(Branch.NOTIFICATION_SHOW, "notification.show", args, false, null);
                return new Plan(Branch.NOTIFICATION_SHOW, "notification.show", args, true, "text");
            }
            case NOTIFICATION_CHANNEL: {
                requireNoAction(amArgs);
                JSONObject args = amArgs.toJsonArgs();
                if (!args.has("id")) throw new BuildException(CompatError.E_BAD_USAGE,
                    "NotificationChannel needs --es id");
                Boolean delete = (Boolean) args.opt("delete");
                if (delete != null && delete && args.has("name")) {
                    throw new BuildException(CompatError.E_BAD_USAGE, "delete takes no name");
                }
                Boolean deleted = (Boolean) args.opt("delete");
                return new Plan(deleted != null && deleted ? Branch.CHANNEL_DELETE : Branch.CHANNEL_CREATE,
                    "notification.channel", args, false, null);
            }
            case NOTIFICATION_REMOVE: {
                requireNoAction(amArgs);
                if (!amArgs.extras.containsKey("id")) {
                    throw new BuildException(CompatError.E_BAD_USAGE, "NotificationRemove needs --es id");
                }
                return new Plan(Branch.NOTIFICATION_REMOVE, "notification.remove", amArgs.toJsonArgs(), false, null);
            }
            case TEXT_TO_SPEECH: {
                requireNoAction(amArgs);
                JSONObject args = amArgs.toJsonArgs();
                String engine = args.optString("engine", "");
                if ("LIST_AVAILABLE".equals(engine)) {
                    // The pinned engines invocation (termux-tts-engines.in:24):
                    // no stdin, no text.
                    return new Plan(Branch.TTS_ENGINES, "tts.engines", new JSONObject(), false, null);
                }
                if (args.has("text")) return new Plan(Branch.TTS_SPEAK, "tts.speak", args, false, null);
                return new Plan(Branch.TTS_SPEAK, "tts.speak", args, true, "text");
            }
            case TOAST: {
                requireNoAction(amArgs);
                JSONObject args = amArgs.toJsonArgs();
                if (args.has("text")) return new Plan(Branch.TOAST_SHOW, "ui.toast", args, false, null);
                return new Plan(Branch.TOAST_SHOW, "ui.toast", args, true, "text");
            }
            case TORCH: {
                requireNoAction(amArgs);
                Boolean enabled = (Boolean) amArgs.extras.get("enabled");
                if (enabled == null) throw new BuildException(CompatError.E_BAD_USAGE,
                    "Torch needs --ez enabled true|false");
                return new Plan(Branch.TORCH_SET, "camera.torch", new JSONObject().put("enabled", enabled), false, null);
            }
            case VIBRATE: {
                requireNoAction(amArgs);
                return new Plan(Branch.VIBRATE, "sys.vibrate", amArgs.toJsonArgs(), false, null);
            }
            case VOLUME: {
                if (amArgs.action == null) {
                    if (!amArgs.extras.isEmpty()) throw new BuildException(CompatError.E_BAD_USAGE,
                        "Volume list takes no extras");
                    return new Plan(Branch.VOLUME_LIST, "audio.volume.list", new JSONObject(), false, null);
                }
                if (!amArgs.action.equals("set-volume")) {
                    throw new BuildException(CompatError.E_UNSUPPORTED_OPTION, "-a " + amArgs.action);
                }
                String stream = (String) amArgs.extras.get("stream");
                Integer volume = (Integer) amArgs.extras.get("volume");
                if (stream == null || volume == null) {
                    throw new BuildException(CompatError.E_BAD_USAGE,
                        "set-volume needs --es stream S --ei volume V");
                }
                if (!stream.equals("alarm") && !stream.equals("music") && !stream.equals("notification")
                    && !stream.equals("ring") && !stream.equals("system") && !stream.equals("call")) {
                    throw new BuildException(CompatError.E_BAD_VALUE, "stream " + stream);
                }
                if (volume < 0) throw new BuildException(CompatError.E_BAD_VALUE, "volume " + volume);
                return new Plan(Branch.VOLUME_SET, "audio.volume",
                    new JSONObject().put("stream", stream).put("volume", volume), false, null);
            }
            default:
                throw new BuildException(CompatError.E_UNKNOWN_METHOD, upstreamName);
        }
    }

    /**
     * Phase 2: assemble the native request, injecting the stdin text that
     * was actually read (null when the plan did not need it — explicit empty
     * stays distinct from absent).
     */
    public NativeRequest finish(Plan plan, String stdinText)
            throws BuildException, org.json.JSONException {
        if (plan.stdinKey != null && plan.readsStdin && stdinText != null && !plan.args.has(plan.stdinKey)) {
            plan.args.put(plan.stdinKey, stdinText);
        }
        return new NativeRequest(plan.cmd, plan.args);
    }

    private static void requireNoAction(CompatAmArgs amArgs) throws BuildException {
        if (amArgs.action != null) {
            throw new BuildException(CompatError.E_UNSUPPORTED_OPTION, "-a " + amArgs.action);
        }
    }

    /** Reconstructs the "flag key" form used by {@link #allowedFlags}. */
    private static String flagKindFor(CompatAmArgs amArgs, String key) {
        Object v = amArgs.extras.get(key);
        if (v instanceof String) return "--es";
        if (v instanceof Integer) return "--ei";
        if (v instanceof Boolean) return "--ez";
        if (v instanceof Double) return "--ef";
        if (v instanceof long[]) return "--ela";
        return "--eln";
    }
}
