package com.termux.shared.crash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.termux.app.UncaughtExceptionRecorder;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The default crash handler logs, hands the crash once to the handler it replaced (or shows the stack when there was
 * none), survives a failing log, and is installed once however many times it is asked and whatever wraps it.
 *
 * <p>Every test starts from a known default handler and gives the global state back, handler registration included.
 */
@RunWith(RobolectricTestRunner.class)
public class CrashHandlerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Context context;
    private final Counting previous = new Counting();
    private final Client client = new Client();
    private Thread.UncaughtExceptionHandler original;
    private ExecutorService pool;

    /** A handler that records what it was given. */
    private static final class Counting implements Thread.UncaughtExceptionHandler {
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        @Override public void uncaughtException(Thread thread, Throwable error) {
            threads.add(thread);
            errors.add(error);
        }
    }

    /** A log client that handles the crash itself (no file is written) and counts how often it was asked. */
    private static final class Client implements CrashHandler.CrashHandlerClient {
        final AtomicInteger preLogs = new AtomicInteger();
        final AtomicInteger postLogs = new AtomicInteger();
        volatile RuntimeException failure;

        @Override public boolean onPreLogCrash(Context context, Thread thread, Throwable throwable) {
            preLogs.incrementAndGet();
            if (failure != null) throw failure;
            return true;
        }

        @Override public void onPostLogCrash(Context context, Thread thread, Throwable throwable) {
            postLogs.incrementAndGet();
        }

        @Override public String getCrashLogFilePath(Context context) { return ""; }

        @Override public String getAppInfoMarkdownString(Context context) { return null; }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        original = Thread.getDefaultUncaughtExceptionHandler();
        CrashHandler.removeDefaultCrashHandler();
        Thread.setDefaultUncaughtExceptionHandler(previous);
        pool = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "crash-handler-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    @After public void tearDown() throws Exception {
        pool.shutdownNow();
        assertTrue("a thread of the test is still running", pool.awaitTermination(5, TimeUnit.SECONDS));
        CrashHandler.removeDefaultCrashHandler();
        Thread.setDefaultUncaughtExceptionHandler(original);
    }

    private void install() {
        CrashHandler.setDefaultCrashHandler(context, client);
    }

    /** The crash of a thread, delivered the way the JVM delivers it: to the default handler. */
    private static void crash(Throwable error) {
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), error);
    }

    /** Runs {@code action} with standard error captured; the captured text is returned. */
    private static String capturingStandardError(Runnable action) {
        PrintStream saved = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            action.run();
        } finally {
            System.setErr(saved);
        }
        return captured.toString();
    }

    // ---- delegation

    @Test public void thePreviousHandlerGetsTheSameThreadAndThrowableExactlyOnce() {
        install();
        IllegalStateException boom = new IllegalStateException("boom-unique");
        crash(boom);
        assertEquals(1, client.preLogs.get());
        assertEquals(1, previous.errors.size());
        assertSame(boom, previous.errors.get(0));
        assertSame(Thread.currentThread(), previous.threads.get(0));
    }

    @Test public void aRealThreadThatDiesIsLoggedAndPassedOn() throws Exception {
        install();
        IllegalStateException boom = new IllegalStateException("dies-unique");
        Thread dying = new Thread(() -> { throw boom; }, "dying-thread");
        dying.start();
        dying.join(5000);
        assertFalse(dying.isAlive());
        assertEquals(1, client.preLogs.get());
        assertEquals(1, previous.errors.size());
        assertSame(boom, previous.errors.get(0));
        assertSame(dying, previous.threads.get(0));
    }

    @Test public void aLogThatFailsStillPassesTheOriginalOnceAndRaisesNothing() {
        client.failure = new IllegalArgumentException("the log failed");
        install();
        IllegalStateException boom = new IllegalStateException("original-unique");
        String err = capturingStandardError(() -> crash(boom));
        assertEquals(1, client.preLogs.get());
        assertEquals("the crash is passed on once", 1, previous.errors.size());
        assertSame("the cause is the original, not the failure of the log", boom, previous.errors.get(0));
        assertTrue("the failure of the log is reported on the side: " + err, err.contains("the log failed"));
    }

    @Test public void aPreviousHandlerThatThrowsKeepsItsExceptionAndIsCalledOnce() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            previous.errors.add(error);
            throw new IllegalStateException("the previous handler threw");
        });
        install();
        IllegalStateException boom = new IllegalStateException("original-unique");
        try {
            crash(boom);
            org.junit.Assert.fail("what the previous handler throws is not swallowed");
        } catch (IllegalStateException e) {
            assertEquals("the previous handler threw", e.getMessage());
        }
        assertEquals(1, previous.errors.size());
        assertSame(boom, previous.errors.get(0));
    }

    @Test public void withoutAPreviousHandlerTheOriginalStackIsShownAndNothingIsCalledOnNull() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        install();
        IllegalStateException boom = new IllegalStateException("original-unique");
        String err = capturingStandardError(() -> crash(boom));
        assertEquals(1, client.preLogs.get());
        assertTrue(err, err.contains("Exception in thread \"" + Thread.currentThread().getName() + "\""));
        assertTrue(err, err.contains("IllegalStateException: original-unique"));
        assertTrue("the stack is the one of the original", err.contains("CrashHandlerTest"));
    }

    @Test public void withoutAPreviousHandlerAFailingLogStillShowsTheOriginal() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        client.failure = new IllegalArgumentException("the log failed");
        install();
        String err = capturingStandardError(() -> crash(new IllegalStateException("original-unique")));
        assertTrue(err, err.contains("IllegalStateException: original-unique"));
    }

    @Test public void aHandlerThatCallsBackIntoTheCrashHandlerDoesNotLoop() {
        // A previous handler that delegates to whatever is the default now, which is the crash handler itself.
        AtomicInteger calls = new AtomicInteger();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            calls.incrementAndGet();
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(thread, error);
        });
        install();
        String err = capturingStandardError(() -> crash(new IllegalStateException("original-unique")));
        assertEquals("logged once", 1, client.preLogs.get());
        assertEquals("the cycle is cut after one round", 1, calls.get());
        assertTrue("the original stack is still shown: " + err, err.contains("original-unique"));
    }

    @Test public void aPerThreadHandlerLogsButDoesNotPassTheCrashOn() {
        CrashHandler perThread = CrashHandler.getCrashHandler(context, client);
        perThread.uncaughtException(Thread.currentThread(), new IllegalStateException("thread-only"));
        assertEquals(1, client.preLogs.get());
        assertEquals("the policy of a non-default handler is unchanged", 0, previous.errors.size());
        assertSame("it is not installed as the default", previous, Thread.getDefaultUncaughtExceptionHandler());
    }

    // ---- installation

    @Test public void installingTwiceAddsNoSecondHandler() {
        install();
        Thread.UncaughtExceptionHandler first = Thread.getDefaultUncaughtExceptionHandler();
        assertTrue(first instanceof CrashHandler);
        install();
        install();
        assertSame(first, Thread.getDefaultUncaughtExceptionHandler());
        crash(new IllegalStateException("once"));
        assertEquals("the chain has one crash handler: one log", 1, client.preLogs.get());
        assertEquals(1, previous.errors.size());
    }

    @Test public void installingAgainOverAWrapperAddsNoHandlerToTheChain() throws Exception {
        install();
        File diagnostic = new File(tmp.newFolder(), "uncaught.log");
        try (UncaughtExceptionRecorder.Handle wrapper = UncaughtExceptionRecorder.install(diagnostic)) {
            Thread.UncaughtExceptionHandler wrapped = Thread.getDefaultUncaughtExceptionHandler();
            assertTrue("the recorder wraps the crash handler", wrapped instanceof UncaughtExceptionRecorder);
            install();
            assertSame("the wrapper stays the default, nothing is put on top of it", wrapped, Thread.getDefaultUncaughtExceptionHandler());
            crash(new IllegalStateException("through-the-wrapper"));
            assertEquals("one crash handler in the chain: one log", 1, client.preLogs.get());
            assertEquals(1, previous.errors.size());
        }
        assertTrue("closing the wrapper gives the crash handler back", Thread.getDefaultUncaughtExceptionHandler() instanceof CrashHandler);
    }

    @Test public void concurrentInstallsInstallOneHandler() throws Exception {
        int callers = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> installs = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            installs.add(pool.submit(() -> {
                assertTrue(go.await(5, TimeUnit.SECONDS));
                install();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> install : installs) install.get(5, TimeUnit.SECONDS);
        crash(new IllegalStateException("once"));
        assertEquals("one handler was installed: one log", 1, client.preLogs.get());
        assertEquals(1, previous.errors.size());
    }

    @Test public void anInstallationIsNotMistakenForOneOfAnotherClassLoaderByItsName() {
        // A default handler that merely looks like ours is somebody else's: the handler is installed on top of it.
        Thread.UncaughtExceptionHandler lookalike = new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) { previous.uncaughtException(thread, error); }
            @Override public String toString() { return CrashHandler.class.getName(); }
        };
        Thread.setDefaultUncaughtExceptionHandler(lookalike);
        install();
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() instanceof CrashHandler);
        crash(new IllegalStateException("once"));
        assertEquals(1, client.preLogs.get());
        assertEquals("the lookalike was kept below and called once", 1, previous.errors.size());
    }

    // ---- registration and reset

    @Test public void removingTheHandlerGivesTheReplacedOneBackAndAllowsANewInstall() {
        install();
        CrashHandler.removeDefaultCrashHandler();
        assertSame(previous, Thread.getDefaultUncaughtExceptionHandler());
        CrashHandler.removeDefaultCrashHandler();
        assertSame("a second removal changes nothing", previous, Thread.getDefaultUncaughtExceptionHandler());
        install();
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() instanceof CrashHandler);
    }

    @Test public void aRemovedHandlerThatIsStillWrappedOnlyPassesTheCrashOn() throws Exception {
        install();
        File diagnostic = new File(tmp.newFolder(), "uncaught.log");
        try (UncaughtExceptionRecorder.Handle wrapper = UncaughtExceptionRecorder.install(diagnostic)) {
            Thread.UncaughtExceptionHandler wrapped = Thread.getDefaultUncaughtExceptionHandler();
            CrashHandler.removeDefaultCrashHandler();
            assertSame("somebody else's handler is left alone", wrapped, Thread.getDefaultUncaughtExceptionHandler());
            crash(new IllegalStateException("after-removal"));
            assertEquals("it no longer logs", 0, client.preLogs.get());
            assertEquals("but the crash still reaches the handler below", 1, previous.errors.size());
        }
    }
}
