package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class LitertMemoryTest {
    private static final String STATUS = "Name:\tcom.termux:litert\nVmPeak:\t 9000000 kB\nVmHWM:\t  812340 kB\nVmRSS:\t  790120 kB\nVmSwap:\t       0 kB\nThreads:\t12\n";
    private static final String MEMINFO = "MemTotal:       11800000 kB\nMemFree:         300000 kB\nMemAvailable:   4430000 kB\nBuffers: 1 kB\n";

    private final Map<String, String> files = new HashMap<>();
    private final LitertMemory memory = new LitertMemory(path -> files.get(path), 4242);

    @Test public void processAndSystemMemoryAreReadFromProc() throws Exception {
        files.put("/proc/self/status", STATUS);
        files.put("/proc/meminfo", MEMINFO);
        JSONObject m = memory.snapshot();
        assertEquals(790120L, m.getLong("rss_kb"));
        assertEquals(812340L, m.getLong("hwm_kb"));
        assertEquals(0L, m.getLong("swap_kb"));
        assertEquals(4430000L, m.getLong("mem_available_kb"));
    }

    @Test public void theGpuFigureComesFromKgslWhenItIsReadable() throws Exception {
        files.put("/proc/self/status", STATUS);
        files.put("/proc/meminfo", MEMINFO);
        files.put("/sys/class/kgsl/kgsl/proc/4242/gpumem_mapped", "2000000000\n");
        JSONObject m = memory.snapshot();
        assertEquals(2_000_000_000L / 1024, m.getLong("gpu_kb"));
        assertEquals("kgsl", m.getString("gpu_source"));
    }

    @Test public void whenKgslIsNotReadableTheGpuFigureIsNullWithTheReason() throws Exception {
        files.put("/proc/self/status", STATUS);
        files.put("/proc/meminfo", MEMINFO);
        JSONObject m = memory.snapshot();
        assertTrue(m.isNull("gpu_kb"));
        assertTrue(m.getString("gpu_source"), m.getString("gpu_source").startsWith("unavailable"));
    }

    @Test public void anUnreadableProcFileGivesNullNotAGuess() throws Exception {
        JSONObject m = memory.snapshot();
        assertTrue(m.isNull("rss_kb"));
        assertTrue(m.isNull("mem_available_kb"));
    }

    @Test public void garbageIsNullNotAnException() throws Exception {
        files.put("/proc/self/status", "VmRSS:\tlots kB\n");
        files.put("/proc/meminfo", "MemAvailable: x\n");
        files.put("/sys/class/kgsl/kgsl/proc/4242/gpumem_mapped", "many");
        JSONObject m = memory.snapshot();
        assertTrue(m.isNull("rss_kb"));
        assertTrue(m.isNull("mem_available_kb"));
        assertTrue(m.isNull("gpu_kb"));
    }
}
