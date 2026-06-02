package com.termux.shared.termux.workspace;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Orchestrates multi-user workspaces (global active workspace). */
public final class WorkspaceManager {

    public static final String WORKSPACES_ROOT_PATH = TermuxConstants.TERMUX_FILES_DIR_PATH + "/workspaces";

    private WorkspaceManager() {}

    /** Active workspace name from preferences, falling back to DEFAULT. */
    @NonNull
    public static String getActiveWorkspaceName(@NonNull Context context) {
        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context);
        if (prefs == null) return WorkspacePaths.DEFAULT_WORKSPACE;
        String name = prefs.getActiveWorkspaceName();
        return (name == null || name.isEmpty()) ? WorkspacePaths.DEFAULT_WORKSPACE : name;
    }

    /** Absolute HOME path of the active workspace. Single source consumed by the shell env. */
    @NonNull
    public static String getActiveHomePath(@NonNull Context context) {
        String name = getActiveWorkspaceName(context);
        String home = WorkspacePaths.homePathFor(name, TermuxConstants.TERMUX_HOME_DIR_PATH, WORKSPACES_ROOT_PATH);
        // Defensive: if a named workspace dir went missing, fall back to default home.
        if (!WorkspacePaths.isDefault(name) && !new File(home).isDirectory())
            return TermuxConstants.TERMUX_HOME_DIR_PATH;
        return home;
    }

    /** List workspaces: always includes "default", plus existing valid dirs under WORKSPACES_ROOT_PATH, sorted. */
    @NonNull
    public static List<String> listWorkspaces() {
        List<String> result = new ArrayList<>();
        result.add(WorkspacePaths.DEFAULT_WORKSPACE);
        File root = new File(WORKSPACES_ROOT_PATH);
        File[] children = root.listFiles();
        if (children != null) {
            List<String> names = new ArrayList<>();
            for (File child : children) {
                if (child.isDirectory() && WorkspacePaths.isValidName(child.getName()))
                    names.add(child.getName());
            }
            Collections.sort(names);
            result.addAll(names);
        }
        return result;
    }

    /** Create a workspace dir. Returns null on success, error message otherwise. */
    public static String createWorkspace(@NonNull String name) {
        if (!WorkspacePaths.isValidName(name))
            return "Invalid workspace name: " + name;
        String path = WorkspacePaths.homePathFor(name, TermuxConstants.TERMUX_HOME_DIR_PATH, WORKSPACES_ROOT_PATH);
        if (new File(path).exists())
            return "Workspace already exists: " + name;
        Error error = FileUtils.createDirectoryFile("workspace home", path);
        return error == null ? null : error.getMessage();
    }

    /** Delete a workspace dir (recursive). default and the active workspace cannot be deleted. */
    public static String deleteWorkspace(@NonNull Context context, @NonNull String name) {
        if (WorkspacePaths.isDefault(name))
            return "Cannot delete the default workspace";
        if (name.equals(getActiveWorkspaceName(context)))
            return "Cannot delete the active workspace; switch away first";
        if (!WorkspacePaths.isValidName(name))
            return "Invalid workspace name: " + name;
        String path = WorkspacePaths.homePathFor(name, TermuxConstants.TERMUX_HOME_DIR_PATH, WORKSPACES_ROOT_PATH);
        Error error = FileUtils.deleteDirectoryFile("workspace home", path, true);
        return error == null ? null : error.getMessage();
    }

    /** Set active workspace (must be "default" or an existing valid workspace). Returns null on success. */
    public static String setActiveWorkspace(@NonNull Context context, @NonNull String name) {
        if (!WorkspacePaths.isDefault(name)) {
            if (!WorkspacePaths.isValidName(name))
                return "Invalid workspace name: " + name;
            String path = WorkspacePaths.homePathFor(name, TermuxConstants.TERMUX_HOME_DIR_PATH, WORKSPACES_ROOT_PATH);
            if (!new File(path).isDirectory())
                return "Workspace does not exist: " + name;
        }
        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(context);
        if (prefs == null) return "Cannot access preferences";
        prefs.setActiveWorkspaceName(name);
        return null;
    }
}
