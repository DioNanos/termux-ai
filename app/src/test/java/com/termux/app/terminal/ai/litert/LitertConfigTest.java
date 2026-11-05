package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LitertConfigTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file() { return new File(tmp.getRoot(), "conf/litert.conf"); }

    private File write(String text) throws Exception {
        File f = file();
        assertTrue(f.getParentFile().isDirectory() || f.getParentFile().mkdirs());
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test public void withoutAFileTheDefaultIsFiveMinutes() {
        LitertConfig.Values v = new LitertConfig(file()).load();
        assertEquals(300_000L, v.idleUnloadMs);
        assertFalse(v.fromFile);
        assertTrue(v.problems.isEmpty());
    }

    @Test public void theFileValueIsUsedAndCommentsAndSpacesAreIgnored() throws Exception {
        LitertConfig.Values v = new LitertConfig(write("# litert\n\n  idle_unload_ms = 60000  \n")).load();
        assertEquals(60_000L, v.idleUnloadMs);
        assertTrue(v.fromFile);
        assertTrue(v.problems.isEmpty());
    }

    @Test public void zeroTurnsTheIdleUnloadOff() throws Exception {
        assertEquals(0L, new LitertConfig(write("idle_unload_ms=0\n")).load().idleUnloadMs);
    }

    @Test public void aBadValueFallsBackToTheDefaultAndIsReportedWithItsLine() throws Exception {
        for (String bad : new String[] {"abc", "-5", "5000", "86400001", "1.5", ""}) {
            LitertConfig.Values v = new LitertConfig(write("idle_unload_ms=" + bad + "\n")).load();
            assertEquals("value '" + bad + "'", 300_000L, v.idleUnloadMs);
            assertEquals("value '" + bad + "'", 1, v.problems.size());
            assertTrue(v.problems.get(0), v.problems.get(0).contains("line 1"));
        }
    }

    @Test public void anUnknownKeyIsReportedAndIgnored() throws Exception {
        LitertConfig.Values v = new LitertConfig(write("activation=fp32\nidle_unload_ms=120000\n")).load();
        assertEquals(120_000L, v.idleUnloadMs);
        assertEquals(1, v.problems.size());
        assertTrue(v.problems.get(0), v.problems.get(0).contains("activation"));
    }

    @Test public void setWritesTheFileAndCreatesTheFolder() throws Exception {
        LitertConfig config = new LitertConfig(file());
        JSONObject out = config.set(new JSONObject().put("idle_unload_ms", 90_000));
        assertEquals(90_000L, out.getLong("idle_unload_ms"));
        assertEquals("file", out.getString("source"));
        assertEquals(90_000L, new LitertConfig(file()).load().idleUnloadMs);
    }

    @Test public void setRejectsBadValuesAndUnknownKeysAndLeavesTheFileAlone() throws Exception {
        File f = write("idle_unload_ms=60000\n");
        LitertConfig config = new LitertConfig(f);
        Object[][] bad = {{"idle_unload_ms", 5000}, {"idle_unload_ms", -1}, {"idle_unload_ms", "x"}, {"idle_unload_ms", 86_400_001L}, {"nope", 1}};
        for (Object[] change : bad) {
            try {
                config.set(new JSONObject().put((String) change[0], change[1]));
                fail("expected a rejection of " + change[0] + "=" + change[1]);
            } catch (LitertFailure e) {
                assertEquals(LitertErrorCode.INVALID_ARGUMENT, e.code);
            }
        }
        assertEquals("idle_unload_ms=60000\n", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void setZeroIsAcceptedAsOff() throws Exception {
        new LitertConfig(file()).set(new JSONObject().put("idle_unload_ms", 0));
        assertEquals(0L, new LitertConfig(file()).load().idleUnloadMs);
    }

    @Test public void describeSaysWhereTheFileIsWhatIsInForceAndWhenItApplies() throws Exception {
        JSONObject d = new LitertConfig(write("idle_unload_ms=60000\n")).describe();
        assertEquals(file().getPath(), d.getString("file"));
        assertTrue(d.getBoolean("file_exists"));
        assertEquals(60_000L, d.getLong("idle_unload_ms"));
        assertEquals("file", d.getString("source"));
        assertNotNull(d.getJSONArray("problems"));
        assertTrue(d.getString("applies").contains("restart"));
        JSONObject none = new LitertConfig(new File(tmp.getRoot(), "absent.conf")).describe();
        assertFalse(none.getBoolean("file_exists"));
        assertEquals("default", none.getString("source"));
        assertEquals(300_000L, none.getLong("idle_unload_ms"));
    }
}
