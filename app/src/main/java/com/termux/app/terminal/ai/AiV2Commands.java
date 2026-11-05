package com.termux.app.terminal.ai;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The commands the protocol 2 validator accepts, in the {@code api.list}
 * order. A protocol 2 request whose {@code cmd} is not in this set is
 * answered with {@link AiV2Error#UNKNOWN_COMMAND} before any dispatch.
 *
 * <p>Fail closed: a command added to the legacy dispatch switch but not
 * here stays unreachable for protocol 2 clients (it keeps working for
 * protocol 1), so extending the surface is a deliberate act.
 */
public final class AiV2Commands {

    /** Every command the shared legacy dispatcher knows, in api.list order. */
    public static final Set<String> KNOWN = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "ping",
        "bridge.version",
        "api.list",
        "sys.info",
        "sys.battery",
        "sys.clipboard.get",
        "sys.clipboard.set",
        "sys.vibrate",
        "sys.notify",
        "notification.show",
        "notification.channel",
        "notification.remove",
        "ui.toast",
        "audio.info",
        "audio.volume",
        "audio.volume.list",
        "display.brightness",
        "camera.info",
        "camera.torch",
        "wifi.connection",
        "tts.engines",
        "tts.speak",
        "sensor.list",
        "sensor.read",
        "storage.info",
        "storage.list",
        "storage.read",
        "storage.write",
        "aicore.info",
        "aicore.models",
        "aicore.generate",
        "aicore.download",
        "litert.info",
        "litert.models",
        "litert.unload",
        "litert.restart",
        "litert.cancel",
        "litert.config",
        "litert.generate")));

    private AiV2Commands() {}
}
