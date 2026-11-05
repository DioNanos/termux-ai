package com.termux.app;

import org.junit.rules.ExternalResource;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Diagnostic for test threads that die with an uncaught exception.
 *
 * <p>The JVM reports an exception thrown from an uncaught-exception handler as a bare one-line message
 * without a stack. This default handler records, with the full cause chain and stack, both the original
 * throwable of every dying thread and, separately, any throwable raised by the handler that was installed
 * before it, in {@code build/test-results/uncaught-exceptions.log} and on stderr. It then hands the original
 * throwable to the previous handler (or, when there was none, prints it the way the JVM does) and lets
 * whatever that handler throws propagate unchanged: nothing is swallowed and no outcome changes. A failure
 * of the diagnostic itself is reported on stderr and never prevents the delegation.
 *
 * <p>The recorder has a bounded life: {@link #install()} returns a {@link Handle} whose {@code close()}
 * puts the previous default handler back, unless somebody else replaced the default handler in the
 * meantime (then the recorder only stays in that chain as a pass-through). Overlapping installs share one
 * recorder and the previous handler is restored when the last handle is closed. {@link Rule} ties this to
 * the life of a test class.
 */
public final class UncaughtExceptionRecorder implements Thread.UncaughtExceptionHandler {
    private static final String DEFAULT_LOG = "build/test-results/uncaught-exceptions.log";

    private final Thread.UncaughtExceptionHandler previous;
    private final File log;
    private volatile boolean recording = true;
    private int holders;

    private UncaughtExceptionRecorder(Thread.UncaughtExceptionHandler previous, File log) {
        this.previous = previous;
        this.log = log;
    }

    /** Puts a recorder in front of the current default handler, writing to the standard log file. */
    public static Handle install() {
        return install(new File(DEFAULT_LOG));
    }

    /** As {@link #install()}, with the log file chosen by the caller (used by the recorder's own test). */
    public static synchronized Handle install(File log) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        UncaughtExceptionRecorder recorder;
        if (current instanceof UncaughtExceptionRecorder && ((UncaughtExceptionRecorder) current).recording) {
            recorder = (UncaughtExceptionRecorder) current;
        } else {
            recorder = new UncaughtExceptionRecorder(current, log);
            Thread.setDefaultUncaughtExceptionHandler(recorder);
        }
        recorder.holders++;
        return new Handle(recorder);
    }

    /** The end of one install. Closing twice is a no-op. */
    public static final class Handle implements AutoCloseable {
        private final UncaughtExceptionRecorder recorder;
        private boolean closed;

        private Handle(UncaughtExceptionRecorder recorder) {
            this.recorder = recorder;
        }

        /** The recorder this handle belongs to (for the recorder's own test). */
        UncaughtExceptionRecorder recorder() {
            return recorder;
        }

        @Override
        public void close() {
            synchronized (UncaughtExceptionRecorder.class) {
                if (closed) return;
                closed = true;
                if (--recorder.holders > 0) return;
                recorder.recording = false;
                if (Thread.getDefaultUncaughtExceptionHandler() == recorder) {
                    Thread.setDefaultUncaughtExceptionHandler(recorder.previous);
                }
            }
        }
    }

    /** Installs for the life of a test class: {@code @ClassRule public static final Rule UNCAUGHT = new Rule();} */
    public static final class Rule extends ExternalResource {
        private Handle handle;

        @Override
        protected void before() {
            handle = install();
        }

        @Override
        protected void after() {
            if (handle != null) handle.close();
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        if (recording) {
            diagnostic(() -> write(describe("UNCAUGHT in thread", thread) + stack(error)));
        }
        try {
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                // No handler before this one: do what the JVM does by default. Going through the thread group
                // would call the default handler, which is this one.
                System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                error.printStackTrace(System.err);
            }
        } catch (RuntimeException | Error thrown) {
            if (recording) {
                diagnostic(() -> write(describe("HANDLER FAILURE: the previous uncaught-exception handler ("
                    + previous + ") threw while handling the exception of thread", thread)
                    + stack(thrown) + "(the exception it was handling: " + error + ")\n"));
            }
            throw thrown;
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    /** Runs the diagnostic; if it fails, says so on stderr and goes on, so that the delegation always happens. */
    private static void diagnostic(Action action) {
        try {
            action.run();
        } catch (Exception | Error failure) {
            System.err.println("UncaughtExceptionRecorder: the diagnostic failed: " + failure);
            failure.printStackTrace();
        }
    }

    private static String describe(String what, Thread thread) {
        return what + " \"" + thread.getName() + "\" (id " + thread.getId() + ", daemon " + thread.isDaemon()
            + ", group " + (thread.getThreadGroup() == null ? "none" : thread.getThreadGroup().getName()) + ")\n";
    }

    private static String stack(Throwable error) {
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        error.printStackTrace(out);
        out.flush();
        return text.toString();
    }

    private void write(String text) throws IOException {
        System.err.print(text);
        File dir = log.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        try (FileWriter writer = new FileWriter(log, true)) {
            writer.write(text);
        }
    }
}
