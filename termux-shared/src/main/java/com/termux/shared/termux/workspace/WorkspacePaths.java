package com.termux.shared.termux.workspace;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/** Pure path/name logic for multi-user workspaces. No Android dependencies. */
public final class WorkspacePaths {

    /** The historic Termux home; maps to TERMUX_HOME_DIR_PATH. */
    public static final String DEFAULT_WORKSPACE = "default";

    /** Allowed workspace names: lowercase, digits, dash, underscore; 1-32 chars; must start alnum. */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

    private WorkspacePaths() {}

    public static boolean isDefault(@Nullable String name) {
        return DEFAULT_WORKSPACE.equals(name);
    }

    public static boolean isValidName(@Nullable String name) {
        if (name == null) return false;
        if (DEFAULT_WORKSPACE.equals(name)) return false; // reserved, not user-creatable
        return NAME.matcher(name).matches();
    }

    /**
     * Resolve the HOME path for a workspace.
     * @param name workspace name ("default" or a valid name)
     * @param defaultHomePath absolute path of the historic home (TERMUX_HOME_DIR_PATH)
     * @param workspacesRootPath absolute path of the workspaces root ($FILES/workspaces)
     */
    @NonNull
    public static String homePathFor(@NonNull String name, @NonNull String defaultHomePath,
                                     @NonNull String workspacesRootPath) {
        if (isDefault(name)) return defaultHomePath;
        return workspacesRootPath + "/" + name;
    }
}
