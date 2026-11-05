package com.termux.app.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The answers the shim waits for are the answers the REAL McpDispatch gives: every non-blank line
 * gets one response line except the dispatcher's single no-reply method. A reply to an earlier input
 * can therefore never stand in for a later request that is still unanswered.
 */
public class McpDrainAccountingTest {

    private static final String TOKEN = "f00dfeedf00dfeedf00dfeedf00dfeedf00dfeedf00dfeedf00dfeedf00dfeed";
    private static final String LIST2 = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

    /** Every way of sending something that this dispatcher answers although it is not a plain request. */
    private static final String[][] ANSWERED_ODDITIES = {
        {"malformed json", "{broken"},
        {"truncated object", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/lis"},
        {"bare text", "hello"},
        {"array, not an object", "[1,2,3]"},
        {"number", "42"},
        {"unknown notification with an id", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"notifications/custom\"}"},
        {"unknown notification without an id", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}"},
        {"numeric method", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":17}"},
        {"method missing", "{\"jsonrpc\":\"2.0\",\"id\":1}"},
        {"method null", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":null}"},
        {"method an object", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":{\"a\":1}}"},
        {"method an array", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":[\"notifications/initialized\"]}"},
        {"no-reply name with a trailing space", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized \"}"},
        {"no-reply name in other case", "{\"jsonrpc\":\"2.0\",\"method\":\"Notifications/Initialized\"}"},
        {"unknown method", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}"},
        {"empty object", "{}"},
        {"tools/call without params", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}"},
    };

    private static McpDispatch dispatcher() {
        return new McpDispatch((n, a) -> "{\"ok\":true}", new McpDispatch.AuthCheck() {
            @Override public boolean ok(String t) { return false; }
            @Override public boolean live() { return true; }
        });
    }

    private static File dirWithToken() throws Exception {
        File dir = Files.createTempDirectory("drainacct").toFile();
        Files.write(new File(dir, McpStdioShim.TOKEN_NAME).toPath(), TOKEN.getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    /** The shim against a server whose first N answers come from the real dispatcher and the rest never come. */
    private static final class Outcome {
        int code;
        String stdout;
        String stderr;
        McpStdioShimTest.FakeLink link;
    }

    private static Outcome run(String[] frames, int answeredFrames, long drainMs) throws Exception {
        final McpDispatch d = dispatcher();
        final AtomicInteger seen = new AtomicInteger();
        final int limit = answeredFrames;
        McpStdioShimTest.FakeLink link = new McpStdioShimTest.FakeLink();
        link.cooperative = false; // the server stays connected: only answers can complete the drain
        link.responder = line -> {
            if (seen.incrementAndGet() > limit) return null;
            String r = d.handle(line);
            return r.isEmpty() ? null : (r + "\n").getBytes(StandardCharsets.UTF_8);
        };
        StringBuilder in = new StringBuilder();
        for (String f : frames) in.append(f).append('\n');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Outcome o = new Outcome();
        o.link = link;
        o.code = McpStdioShim.run(new ByteArrayInputStream(in.toString().getBytes(StandardCharsets.UTF_8)), out,
            new PrintStream(err, true, "UTF-8"), dirWithToken().getPath(), path -> link, c -> { }, drainMs);
        o.stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
        o.stderr = new String(err.toByteArray(), StandardCharsets.UTF_8);
        return o;
    }

    private static int lines(String s) {
        return s.isEmpty() ? 0 : s.split("\n").length;
    }

    @Test
    public void theShimOwesExactlyTheAnswersTheRealDispatcherGives() throws Exception {
        List<String[]> table = new ArrayList<>(Arrays.asList(ANSWERED_ODDITIES));
        table.add(new String[] {"initialize", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"});
        table.add(new String[] {"initialize, params not an object", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":\"x\"}"});
        table.add(new String[] {"tools/list", LIST2});
        table.add(new String[] {"the no-reply method", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"});
        table.add(new String[] {"the no-reply method with an id", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"notifications/initialized\"}"});
        for (String[] row : table) {
            String reply = dispatcher().handle(row[1]);
            McpStdioShim.Prepared p = McpStdioShim.prepare(row[1].getBytes(StandardCharsets.UTF_8), TOKEN);
            assertNotNull(row[0], p);
            assertEquals("the shim and the dispatcher agree on '" + row[0] + "'", !reply.isEmpty(), p.request);
        }
        assertFalse("the no-reply method is the only silent one", dispatcher().handle(table.get(table.size() - 2)[1]).length() > 0);
    }

    @Test
    public void blankLinesAreIgnoredByBothSides() throws Exception {
        for (String blank : new String[] {"", " ", "\t", " \t ", "\u000c", "\u0001", "\u001f  "}) {
            assertEquals("the dispatcher side (trim) ignores it", true, blank.trim().isEmpty());
            assertEquals("the shim skips it: " + blank.length(), null, McpStdioShim.prepare(blank.getBytes(StandardCharsets.UTF_8), TOKEN));
        }
        // a non-blank that trim() keeps (non-breaking space) is a frame for both
        assertNotNull(McpStdioShim.prepare(" ".getBytes(StandardCharsets.UTF_8), TOKEN));
        assertFalse(" ".trim().isEmpty());
    }

    @Test
    public void anAnswerToAnEarlierOddInputNeverCompletesALaterSilentRequest() throws Exception {
        for (String[] odd : ANSWERED_ODDITIES) {
            // first frame: answered by the real dispatcher; second frame: a tools/list that never gets its reply
            Outcome o = run(new String[] {odd[1], LIST2}, 1, 200);
            assertEquals(odd[0] + ": the unresolved request is not hidden", McpStdioShim.EXIT_DRAIN, o.code);
            assertEquals(odd[0], "android-control: E_DRAIN\n", o.stderr);
            assertEquals(odd[0] + ": exactly the first reply was delivered", 1, lines(o.stdout));
            assertFalse(odd[0] + ": no reply for id 2", o.stdout.contains("\"id\":2"));
            assertEquals(odd[0] + ": both frames sent once", 2, o.link.lines().size());
        }
    }

    @Test
    public void bothDeliveredWithinTheBudgetIsASuccess() throws Exception {
        for (String[] odd : ANSWERED_ODDITIES) {
            Outcome o = run(new String[] {odd[1], LIST2}, 2, 3000);
            assertEquals(odd[0], 0, o.code);
            assertEquals(odd[0], "", o.stderr);
            assertEquals(odd[0], 2, lines(o.stdout));
            assertTrue(odd[0], o.stdout.contains("\"id\":2"));
        }
    }

    @Test
    public void aTrueNotificationFirstStillOwesNothing() throws Exception {
        String note = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        // control case: the notification is silent, the silent request after it is E_DRAIN
        Outcome silent = run(new String[] {note, LIST2}, 1, 200);
        assertEquals(McpStdioShim.EXIT_DRAIN, silent.code);
        assertEquals("", silent.stdout);
        // and when the request is answered the run is a success: the notification was not counted
        Outcome answered = run(new String[] {note, LIST2}, 2, 3000);
        assertEquals(0, answered.code);
        assertEquals(1, lines(answered.stdout));
        // a notification alone owes nothing
        Outcome alone = run(new String[] {note}, 1, 3000);
        assertEquals(0, alone.code);
    }

    @Test
    public void severalOddInputsInARowEachOweTheirOwnAnswer() throws Exception {
        String[] frames = {"{broken", "[1]", "{\"id\":1,\"method\":17}", LIST2};
        Outcome allButLast = run(frames, 3, 200);
        assertEquals("three replies are not four", McpStdioShim.EXIT_DRAIN, allButLast.code);
        assertEquals(3, lines(allButLast.stdout));
        Outcome all = run(frames, 4, 3000);
        assertEquals(0, all.code);
        assertEquals(4, lines(all.stdout));
    }
}
