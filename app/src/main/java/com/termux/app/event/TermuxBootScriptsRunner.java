package com.termux.app.event;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.termux.workspace.WorkspaceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Runs Termux boot scripts inside the main app, without requiring a companion application. */
final class TermuxBootScriptsRunner {

    private static final String LOG_TAG = "TermuxBootScriptsRunner";
    private static final String[] BOOT_DIRECTORY_SUFFIXES = {
        "/.config/termux/boot",
        "/.termux/boot"
    };

    interface ServiceLauncher {
        void start(@NonNull Context context, @NonNull Intent intent);
    }

    private TermuxBootScriptsRunner() {}

    static int run(@NonNull Context context) {
        File activeHome = new File(WorkspaceManager.getActiveHomePath(context));
        return run(context, activeHome, (serviceContext, intent) ->
            ContextCompat.startForegroundService(serviceContext, intent));
    }

    static int run(@NonNull Context context, @NonNull File homeDirectory,
                   @NonNull ServiceLauncher serviceLauncher) {
        List<File> scripts = collectBootScripts(homeDirectory);
        if (scripts.isEmpty()) {
            Logger.logInfo(LOG_TAG, "No boot scripts found for active home");
            return 0;
        }

        int started = 0;
        for (File script : scripts) {
            if (!script.canRead() && !script.setReadable(true, true)) {
                Logger.logError(LOG_TAG, "Cannot make boot script readable: " + script.getAbsolutePath());
                continue;
            }
            if (!script.canExecute() && !script.setExecutable(true, true)) {
                Logger.logError(LOG_TAG, "Cannot make boot script executable: " + script.getAbsolutePath());
                continue;
            }

            Intent executeIntent = buildExecutionIntent(script, homeDirectory);
            try {
                serviceLauncher.start(context, executeIntent);
                started++;
            } catch (RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Failed to start boot script: " + script.getAbsolutePath(), e);
            }
        }

        Logger.logInfo(LOG_TAG, "Started " + started + " boot script(s)");
        return started;
    }

    @NonNull
    static List<File> collectBootScripts(@NonNull File homeDirectory) {
        List<File> scripts = new ArrayList<>();
        for (String suffix : BOOT_DIRECTORY_SUFFIXES) {
            File directory = new File(homeDirectory.getAbsolutePath() + suffix);
            File[] entries = directory.listFiles(File::isFile);
            if (entries == null) continue;

            Arrays.sort(entries, Comparator.comparing(File::getName));
            scripts.addAll(Arrays.asList(entries));
        }
        return scripts;
    }

    @NonNull
    static Intent buildExecutionIntent(@NonNull File script, @NonNull File homeDirectory) {
        Uri executableUri = new Uri.Builder()
            .scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
            .path(script.getAbsolutePath())
            .build();

        return new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, executableUri)
            .setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxService.class.getName())
            .putExtra(TERMUX_SERVICE.EXTRA_RUNNER, Runner.APP_SHELL.getName())
            .putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true)
            .putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, homeDirectory.getAbsolutePath())
            .putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Boot script: " + script.getName());
    }
}
