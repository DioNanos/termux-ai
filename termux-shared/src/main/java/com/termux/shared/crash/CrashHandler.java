package com.termux.shared.crash;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.AndroidUtils;

import java.nio.charset.Charset;

/**
 * Catches uncaught exceptions and logs them.
 *
 * <p>The default handler logs the crash and then hands the same thread and throwable, once, to the handler that was
 * the default when it was created (on Android the one of the platform). A failure of the logging never replaces the
 * original exception and never prevents that delegation. When there was no previous handler the stack of the original
 * exception is printed the way the JVM does, without going through the thread group, which would call the default
 * handler again.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context mContext;
    private final CrashHandlerClient mCrashHandlerClient;
    private final Thread.UncaughtExceptionHandler mDefaultUEH;
    private final boolean mIsDefaultHandler;

    /** Whether this handler still logs; false after {@link #removeDefaultCrashHandler()}, then it only passes on. */
    private volatile boolean mActive = true;
    /** The threads that are inside this handler: a delegate that calls it again must not run it a second time. */
    private final ThreadLocal<Boolean> mHandling = new ThreadLocal<>();

    /** The default handler this class installed and still owns. Guarded by {@code CrashHandler.class}. */
    private static CrashHandler sInstalled;

    private static final String LOG_TAG = "CrashUtils";

    private CrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient,
                         boolean isDefaultHandler) {
        mContext = context;
        mCrashHandlerClient = crashHandlerClient;
        mDefaultUEH = Thread.getDefaultUncaughtExceptionHandler();
        mIsDefaultHandler = isDefaultHandler;
    }

    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        if (mHandling.get() != null) {
            // A handler further down the chain came back to this one: the crash was already handled here.
            printToStandardError(thread, throwable);
            return;
        }
        mHandling.set(Boolean.TRUE);
        try {
            if (mActive) {
                try {
                    Logger.logInfo(LOG_TAG, "uncaughtException() for " + thread +  ": " + throwable.getMessage());
                    logCrash(thread, throwable);
                } catch (Throwable loggingFailure) {
                    // The crash that is being handled is what matters: the failure of its logging is reported on the
                    // side and never replaces it or stops it from being passed on.
                    reportLoggingFailure(loggingFailure);
                }
            }

            // Don't stop the app if not on the main thread
            if (mIsDefaultHandler)
                passOn(thread, throwable);
        } finally {
            mHandling.remove();
        }
    }

    /** Hands the crash, once, to the handler that was the default before this one; see the class comment. */
    private void passOn(@NonNull Thread thread, @NonNull Throwable throwable) {
        Thread.UncaughtExceptionHandler previous = mDefaultUEH;
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
        } else {
            printToStandardError(thread, throwable);
        }
    }

    private static void printToStandardError(@NonNull Thread thread, @NonNull Throwable throwable) {
        System.err.print("Exception in thread \"" + thread.getName() + "\" ");
        throwable.printStackTrace(System.err);
    }

    private static void reportLoggingFailure(@NonNull Throwable loggingFailure) {
        try {
            System.err.println(LOG_TAG + ": the crash could not be logged: " + loggingFailure);
        } catch (Throwable ignored) {
            // Nothing is left to report to.
        }
    }

    /**
     * Set default uncaught crash handler for the app to {@link CrashHandler}.
     *
     * <p>Installation is serialized and idempotent: while the handler this call installed is still registered,
     * calling it again changes nothing, whatever other handlers wrapped it in the meantime. Ownership is the
     * registration of the instance itself, never the class name of whatever is currently the default. The
     * registration belongs to the class loader that loaded this class: a crash handler created by another class
     * loader is a handler of somebody else, it is neither recognised nor touched, and it stays below this one as the
     * previous handler.
     * {@link #removeDefaultCrashHandler()} ends the registration.
     */
    public static void setDefaultCrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        synchronized (CrashHandler.class) {
            if (sInstalled != null) return;
            CrashHandler handler = new CrashHandler(context, crashHandlerClient, true);
            sInstalled = handler;
            Thread.setDefaultUncaughtExceptionHandler(handler);
        }
    }

    /**
     * Ends the registration of the default handler installed by {@link #setDefaultCrashHandler}, so that a new
     * one can be installed. If it is still the default, the handler it replaced is put back; if somebody else wrapped
     * it since, that handler is left alone and this one stays in its chain as a pass-through that no longer logs.
     * Does nothing when nothing is registered.
     */
    public static void removeDefaultCrashHandler() {
        synchronized (CrashHandler.class) {
            CrashHandler handler = sInstalled;
            if (handler == null) return;
            sInstalled = null;
            handler.mActive = false;
            if (Thread.getDefaultUncaughtExceptionHandler() == handler) {
                Thread.setDefaultUncaughtExceptionHandler(handler.mDefaultUEH);
            }
        }
    }

    /**
     * Set uncaught crash handler of current non-main thread to {@link CrashHandler}.
     */
    public static void setCrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        Thread.currentThread().setUncaughtExceptionHandler(new CrashHandler(context, crashHandlerClient, false));
    }

    /**
     * Get {@link CrashHandler} instance that can be set as uncaught crash handler of a non-main thread.
     */
    public static CrashHandler getCrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        return new CrashHandler(context, crashHandlerClient, false);
    }

    /**
     * Log a crash in the crash log file at path returned by {@link CrashHandlerClient#getCrashLogFilePath(Context)}.
     *
     * @param context The {@link Context} for operations.
     * @param crashHandlerClient The {@link CrashHandlerClient} implementation.
     * @param thread The {@link Thread} in which the crash happened.
     * @param throwable The {@link Throwable} thrown for the crash.
     */
    public static void logCrash(@NonNull Context context,
                                @NonNull CrashHandlerClient crashHandlerClient,
                                @NonNull Thread thread,  @NonNull Throwable throwable) {
        Logger.logInfo(LOG_TAG, "logCrash() for " + thread +  ": " + throwable.getMessage());
        new CrashHandler(context, crashHandlerClient, false).logCrash(thread, throwable);
    }

    public void logCrash(@NonNull Thread thread, @NonNull Throwable throwable) {
        if (!mCrashHandlerClient.onPreLogCrash(mContext, thread, throwable)) {
            logCrashToFile(mContext, mCrashHandlerClient, thread, throwable);
            mCrashHandlerClient.onPostLogCrash(mContext, thread, throwable);
        }
    }

    public void logCrashToFile(@NonNull Context context,
                               @NonNull CrashHandlerClient crashHandlerClient,
                               @NonNull Thread thread, @NonNull Throwable throwable) {
        StringBuilder reportString = new StringBuilder();

        reportString.append("## Crash Details\n");
        reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Thread", thread.toString(), "-"));
        reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Timestamp", AndroidUtils.getCurrentMilliSecondUTCTimeStamp(), "-"));
        reportString.append("\n\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Crash Message", throwable.getMessage(), "-"));
        reportString.append("\n\n").append(Logger.getStackTracesMarkdownString("Stacktrace", Logger.getStackTracesStringArray(throwable)));

        String appInfoMarkdownString = crashHandlerClient.getAppInfoMarkdownString(context);
        if (appInfoMarkdownString != null && !appInfoMarkdownString.isEmpty())
            reportString.append("\n\n").append(appInfoMarkdownString);

        reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context));

        // Log report string to logcat
        Logger.logError(reportString.toString());

        // Write report string to crash log file
        Error error = FileUtils.writeTextToFile("crash log", crashHandlerClient.getCrashLogFilePath(context),
                        Charset.defaultCharset(), reportString.toString(), false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    public interface CrashHandlerClient {

        /**
         * Called before {@link #logCrashToFile(Context, CrashHandlerClient, Thread, Throwable)} is called.
         *
         * @param context The {@link Context} passed to {@link CrashHandler#CrashHandler(Context, CrashHandlerClient, boolean)}.
         * @param thread The {@link Thread} in which the crash happened.
         * @param throwable The {@link Throwable} thrown for the crash.
         * @return Should return {@code true} if crash has been handled and should not be logged,
         * otherwise {@code false}.
         */
        boolean onPreLogCrash(Context context, Thread thread, Throwable throwable);

        /**
         * Called after {@link #logCrashToFile(Context, CrashHandlerClient, Thread, Throwable)} is called.
         *
         * @param context The {@link Context} passed to {@link CrashHandler#CrashHandler(Context, CrashHandlerClient, boolean)}.
         * @param thread The {@link Thread} in which the crash happened.
         * @param throwable The {@link Throwable} thrown for the crash.
         */
        void onPostLogCrash(Context context, Thread thread, Throwable throwable);

        /**
         * Get crash log file path.
         *
         * @param context The {@link Context} passed to {@link CrashHandler#CrashHandler(Context, CrashHandlerClient, boolean)}.
         * @return Should return the crash log file path.
         */
        @NonNull
        String getCrashLogFilePath(Context context);

        /**
         * Get app info markdown string to add to crash log.
         *
         * @param context The {@link Context} passed to {@link CrashHandler#CrashHandler(Context, CrashHandlerClient, boolean)}.
         * @return Should return app info markdown string.
         */
        String getAppInfoMarkdownString(Context context);

    }

}
