package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** The diagnostic handler records, delegates, propagates what the delegate throws, and gives the handler back. */
public class UncaughtExceptionRecorderTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Thread.UncaughtExceptionHandler original;
    private File log;

    @Before public void setUp() throws Exception {
        original = Thread.getDefaultUncaughtExceptionHandler();
        log = new File(tmp.newFolder(), "uncaught.log");
    }

    @After public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original);
    }

    private String logText() throws Exception {
        return log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
    }

    /** Raises the exception on a real thread and waits for it to die. */
    private static void dieWith(Throwable error, String name) throws Exception {
        Thread t = new Thread(() -> { sneak(error); }, name);
        t.start();
        t.join(5000);
        assertFalse(t.isAlive());
    }

    private static void sneak(Throwable error) {
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        throw (Error) error;
    }

    @Test public void aDelegateThatReturnsSeesTheSameThrowableAndTheOriginalIsRecorded() throws Exception {
        List<Throwable> seen = new ArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> seen.add(e));
        try (UncaughtExceptionRecorder.Handle h = UncaughtExceptionRecorder.install(log)) {
            IllegalStateException boom = new IllegalStateException("original-unique", new RuntimeException("cause-unique"));
            dieWith(boom, "recorder-test-returns");
            assertEquals(1, seen.size());
            assertSame(boom, seen.get(0));
        }
        String text = logText();
        assertTrue(text.contains("UNCAUGHT in thread \"recorder-test-returns\""));
        assertTrue(text.contains("original-unique"));
        assertTrue("the cause chain is recorded", text.contains("cause-unique"));
        assertFalse(text.contains("HANDLER FAILURE"));
    }

    @Test public void withoutAPreviousHandlerTheOriginalIsRecordedAndNothingIsRaised() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(null);
        try (UncaughtExceptionRecorder.Handle h = UncaughtExceptionRecorder.install(log)) {
            dieWith(new IllegalStateException("original-unique"), "recorder-test-null");
        }
        assertTrue(logText().contains("original-unique"));
        assertEquals("the absence of a handler is restored as such", null, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test public void aDelegateThatThrowsIsRecordedWithItsStackAndRaisedUnchanged() throws Exception {
        NullPointerException fromHandler = new NullPointerException("delegate-NPE-unique");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { throw fromHandler; });
        try (UncaughtExceptionRecorder.Handle h = UncaughtExceptionRecorder.install(log)) {
            IllegalStateException boom = new IllegalStateException("original-unique");
            // Directly, to see what propagates out of the recorder...
            try {
                h.recorder().uncaughtException(Thread.currentThread(), boom);
                fail("the delegate's exception must propagate");
            } catch (NullPointerException propagated) {
                assertSame("the very same throwable, not a copy or a wrapper", fromHandler, propagated);
            }
            // ... and on a real thread, the case of the one-line JVM message without stack.
            dieWith(new IllegalStateException("original-on-thread-unique"), "recorder-test-throws");
        }
        String text = logText();
        assertTrue("the original is recorded", text.contains("original-unique"));
        assertTrue("the handler's own exception is recorded", text.contains("delegate-NPE-unique"));
        assertTrue("with its own stack, which starts in the test that created it",
            text.contains("java.lang.NullPointerException: delegate-NPE-unique\n\tat com.termux.app."
                + "UncaughtExceptionRecorderTest.aDelegateThatThrowsIsRecordedWithItsStackAndRaisedUnchanged"));
        assertTrue("and told apart from the original", text.contains("HANDLER FAILURE"));
        assertTrue(text.contains("original-on-thread-unique"));
    }

    @Test public void aFailingLogDoesNotStopTheDelegation() throws Exception {
        File notADirectory = tmp.newFile();
        File impossibleLog = new File(notADirectory, "sub/uncaught.log");
        List<Throwable> seen = new ArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> seen.add(e));
        try (UncaughtExceptionRecorder.Handle h = UncaughtExceptionRecorder.install(impossibleLog)) {
            IllegalStateException boom = new IllegalStateException("original-unique");
            h.recorder().uncaughtException(Thread.currentThread(), boom);
            assertEquals("the delegate was called although the log could not be written", 1, seen.size());
            assertSame(boom, seen.get(0));
        }
        assertFalse(impossibleLog.exists());
    }

    @Test public void aFailingLogDoesNotHideTheDelegateException() throws Exception {
        File notADirectory = tmp.newFile();
        NullPointerException fromHandler = new NullPointerException("delegate-NPE-unique");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { throw fromHandler; });
        try (UncaughtExceptionRecorder.Handle h = UncaughtExceptionRecorder.install(new File(notADirectory, "x/y.log"))) {
            try {
                h.recorder().uncaughtException(Thread.currentThread(), new IllegalStateException("original-unique"));
                fail("the delegate's exception must propagate");
            } catch (NullPointerException propagated) {
                assertSame(fromHandler, propagated);
            }
        }
    }

    @Test public void closingPutsThePreviousHandlerBack() {
        Thread.UncaughtExceptionHandler mine = (t, e) -> { };
        Thread.setDefaultUncaughtExceptionHandler(mine);
        UncaughtExceptionRecorder.Handle h = UncaughtExceptionRecorder.install(log);
        assertNotNull(Thread.getDefaultUncaughtExceptionHandler());
        assertFalse(mine == Thread.getDefaultUncaughtExceptionHandler());
        h.close();
        assertSame("the handler in force before the install is back", mine, Thread.getDefaultUncaughtExceptionHandler());
        h.close(); // a second close changes nothing
        assertSame(mine, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test public void theHandlerIsRestoredEvenWhenTheBodyFails() {
        Thread.UncaughtExceptionHandler mine = (t, e) -> { };
        Thread.setDefaultUncaughtExceptionHandler(mine);
        try (UncaughtExceptionRecorder.Handle h = UncaughtExceptionRecorder.install(log)) {
            throw new IllegalStateException("the test body failed");
        } catch (IllegalStateException expected) {
            // the failure of the body is the one that propagates
        }
        assertSame(mine, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test public void overlappingInstallsShareOneRecorderAndRestoreOnTheLastClose() {
        Thread.UncaughtExceptionHandler mine = (t, e) -> { };
        Thread.setDefaultUncaughtExceptionHandler(mine);
        UncaughtExceptionRecorder.Handle first = UncaughtExceptionRecorder.install(log);
        Thread.UncaughtExceptionHandler recorder = Thread.getDefaultUncaughtExceptionHandler();
        UncaughtExceptionRecorder.Handle second = UncaughtExceptionRecorder.install(log);
        assertSame("no second recorder is stacked on the first", recorder, Thread.getDefaultUncaughtExceptionHandler());
        first.close();
        assertSame("still recording for the second holder", recorder, Thread.getDefaultUncaughtExceptionHandler());
        second.close();
        assertSame(mine, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test public void aHandlerInstalledByAThirdPartyInTheMeantimeIsNotOverwritten() throws Exception {
        Thread.UncaughtExceptionHandler mine = (t, e) -> { };
        Thread.setDefaultUncaughtExceptionHandler(mine);
        UncaughtExceptionRecorder.Handle h = UncaughtExceptionRecorder.install(log);
        UncaughtExceptionRecorder recorder = h.recorder();
        List<Throwable> seen = new ArrayList<>();
        Thread.UncaughtExceptionHandler thirdParty = (t, e) -> { seen.add(e); recorder.uncaughtException(t, e); };
        Thread.setDefaultUncaughtExceptionHandler(thirdParty);
        h.close();
        assertSame("the third party's handler stays in force", thirdParty, Thread.getDefaultUncaughtExceptionHandler());
        // The closed recorder left in that chain only passes the exception on.
        recorder.uncaughtException(Thread.currentThread(), new IllegalStateException("after-close-unique"));
        assertFalse("a closed recorder no longer writes", logText().contains("after-close-unique"));
    }
}
