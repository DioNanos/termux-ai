package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;

public class LitertCommandsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final FakeWorker worker = new FakeWorker();

    private LitertCommands commands() throws Exception {
        File dir = tmp.newFolder("models");
        LitertGuards guards = new LitertGuards(34, Collections.singletonList("arm64-v8a"), name -> true);
        return new LitertCommands(new LitertEngine(worker, new LitertModelCatalog(dir), guards, 34, "/native", "/cache", 1000));
    }

    @Test public void onlyLitertCommandsAreHandled() {
        assertTrue(LitertCommands.handles("litert.generate"));
        assertFalse(LitertCommands.handles("aicore.generate"));
        assertFalse(LitertCommands.handles(null));
    }

    @Test public void invalidArgumentsNeverReachTheWorker() throws Exception {
        LitertCommands commands = commands();
        JSONObject[] bad = {
            new JSONObject(),
            new JSONObject().put("request_id", "r").put("model", "m").put("prompt", "x"),
            new JSONObject().put("request_id", "r").put("model", "m").put("prompt", "x").put("backend", "tpu"),
            new JSONObject().put("request_id", "r").put("model", "m").put("prompt", "x").put("backend", "cpu").put("stage", "stable"),
            new JSONObject().put("request_id", "r").put("model", "m").put("prompt", "x").put("backend", "cpu").put("max_tokens", 0),
        };
        for (JSONObject args : bad) {
            try {
                commands.handle("litert.generate", args);
                fail("expected a rejection of " + args);
            } catch (LitertFailure f) {
                assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
            }
        }
        assertEquals(0, worker.calls.size());
    }

    @Test public void unknownCommandsAreRejected() throws Exception {
        try {
            commands().handle("litert.download", new JSONObject());
            fail("litert has no download");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
        }
    }

    @Test public void infoReportsAWorkerThatIsNotStartedWithoutStartingIt() throws Exception {
        worker.connected = false;
        JSONObject info = commands().handle("litert.info", new JSONObject());
        assertEquals("not_started", info.getJSONObject("worker").getString("state"));
        assertEquals(0, worker.calls.size());
    }

    @Test public void infoSurvivesAWorkerThatCannotAnswer() throws Exception {
        worker.failure = new LitertFailure(LitertErrorCode.MODEL_WORKER_DIED, null, "worker", null, "gone", null);
        JSONObject info = commands().handle("litert.info", new JSONObject());
        assertEquals("unreachable", info.getJSONObject("worker").getString("state"));
        assertEquals("MODEL_WORKER_DIED", info.getJSONObject("worker").getString("error_name"));
    }

    @Test public void modelsListsAnEmptyDirectory() throws Exception {
        JSONObject out = commands().handle("litert.models", new JSONObject());
        assertEquals(0, out.getJSONArray("models").length());
        assertTrue(out.getBoolean("directory_exists"));
    }

    @Test public void unloadAndRestartAreCommandsThatTakeNoArguments() throws Exception {
        LitertCommands commands = commands();
        worker.reply = "{\"ok\":true,\"data\":{\"unloaded\":false,\"via\":\"none\"}}";
        assertFalse(commands.handle("litert.unload", new JSONObject()).getBoolean("unloaded"));
        worker.reply = "{\"ok\":true,\"data\":{\"restarting\":true}}";
        assertTrue(commands.handle("litert.restart", new JSONObject()).getBoolean("restarted"));
        for (String cmd : new String[] {"litert.unload", "litert.restart"}) {
            try {
                commands.handle(cmd, new JSONObject().put("force", true));
                fail(cmd + " must refuse arguments");
            } catch (LitertFailure f) {
                assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
            }
        }
    }

    @Test public void configShowsAndSetsThroughTheCommandAndNeverTouchesTheWorker() throws Exception {
        File conf = new File(tmp.newFolder("cfg"), "litert.conf");
        LitertGuards guards = new LitertGuards(34, Collections.singletonList("arm64-v8a"), name -> true);
        LitertCommands commands = new LitertCommands(
            new LitertEngine(worker, new LitertModelCatalog(tmp.newFolder("m2")), guards, 34, "/native", "/cache", 1000),
            new LitertConfig(conf));
        assertEquals(300_000L, commands.handle("litert.config", new JSONObject()).getLong("idle_unload_ms"));
        JSONObject set = commands.handle("litert.config", new JSONObject().put("set", new JSONObject().put("idle_unload_ms", 120_000)));
        assertEquals(120_000L, set.getLong("idle_unload_ms"));
        assertEquals(0, worker.calls.size());
        try {
            commands.handle("litert.config", new JSONObject().put("set", new JSONObject().put("idle_unload_ms", 7)));
            fail("a bad value must be refused");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
        }
        try {
            commands.handle("litert.config", new JSONObject().put("force", 1));
            fail("unknown arguments must be refused");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
        }
    }

    @Test public void cancelTakesARequestIdOrAllAndNothingElse() throws Exception {
        LitertCommands commands = commands();
        worker.reply = "{\"ok\":true,\"data\":{\"cancelled\":false}}";
        assertFalse(commands.handle("litert.cancel", new JSONObject().put("request_id", "r1")).getBoolean("cancelled"));
        assertFalse(commands.handle("litert.cancel", new JSONObject().put("all", true)).getBoolean("cancelled"));
        JSONObject[] bad = {
            new JSONObject(),
            new JSONObject().put("all", false),
            new JSONObject().put("all", true).put("request_id", "r1"),
            new JSONObject().put("request_id", "r1").put("force", true),
            new JSONObject().put("request_id", 5),
            new JSONObject().put("all", "yes"),
        };
        int calls = worker.calls.size();
        for (JSONObject args : bad) {
            try {
                commands.handle("litert.cancel", args);
                fail("must refuse " + args);
            } catch (LitertFailure f) {
                assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
            }
        }
        assertEquals("a refused cancel never reaches the worker", calls, worker.calls.size());
    }
}
