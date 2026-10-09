package com.termux.app.terminal.ai.litert;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the process and the device report about memory, read from {@code /proc} and, for the GPU, from kgsl where the
 * app may read it. A figure that cannot be read is null with the reason: it is never guessed.
 */
public final class LitertMemory {
    private static final String KGSL = "/sys/class/kgsl/kgsl/proc/%d/gpumem_mapped";

    private final Function<String, String> reader;
    private final int pid;

    /** @param reader the text of a file, or null when it cannot be read */
    public LitertMemory(Function<String, String> reader, int pid) {
        this.reader = reader;
        this.pid = pid;
    }

    /** A reader of this process, for the {@code :litert} service. */
    public static LitertMemory ofThisProcess(int pid) {
        return new LitertMemory(path -> {
            try {
                return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }, pid);
    }

    public JSONObject snapshot() throws JSONException {
        String status = reader.apply("/proc/self/status");
        String meminfo = reader.apply("/proc/meminfo");
        JSONObject out = new JSONObject()
            .put("rss_kb", kb(status, "VmRSS"))
            .put("hwm_kb", kb(status, "VmHWM"))
            .put("swap_kb", kb(status, "VmSwap"))
            .put("mem_available_kb", kb(meminfo, "MemAvailable"));
        String gpu = reader.apply(String.format(KGSL, pid));
        Long gpuBytes = gpu == null ? null : number(gpu.trim());
        if (gpuBytes != null) return out.put("gpu_kb", gpuBytes / 1024).put("gpu_source", "kgsl");
        return out.put("gpu_kb", JSONObject.NULL).put("gpu_source",
            gpu == null ? "unavailable: kgsl is not readable here" : "unavailable: kgsl gave a value that is not a number");
    }

    private static Object kb(String text, String key) {
        if (text == null) return JSONObject.NULL;
        Matcher m = Pattern.compile("(?m)^" + key + ":\\s*([0-9]+)\\s*kB\\s*$").matcher(text);
        return m.find() ? (Object) Long.parseLong(m.group(1)) : JSONObject.NULL;
    }

    private static Long number(String text) {
        return text.matches("[0-9]{1,15}") ? Long.parseLong(text) : null;
    }
}
