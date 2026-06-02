package com.termux.shared.termux.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorkspacePathsTest {

    private static final String FILES = "/data/data/com.termux/files";
    private static final String HOME = FILES + "/home";
    private static final String WS_ROOT = FILES + "/workspaces";

    @Test
    public void defaultWorkspaceMapsToHistoricHome() {
        assertEquals(HOME, WorkspacePaths.homePathFor(WorkspacePaths.DEFAULT_WORKSPACE, HOME, WS_ROOT));
    }

    @Test
    public void namedWorkspaceMapsUnderWorkspacesRoot() {
        assertEquals(WS_ROOT + "/dev-rust", WorkspacePaths.homePathFor("dev-rust", HOME, WS_ROOT));
    }

    @Test
    public void validNamesAccepted() {
        assertTrue(WorkspacePaths.isValidName("dev-rust"));
        assertTrue(WorkspacePaths.isValidName("scraping_01"));
        assertTrue(WorkspacePaths.isValidName("a"));
    }

    @Test
    public void invalidNamesRejected() {
        assertFalse(WorkspacePaths.isValidName(null));
        assertFalse(WorkspacePaths.isValidName(""));
        assertFalse(WorkspacePaths.isValidName("default"));
        assertFalse(WorkspacePaths.isValidName("../escape"));
        assertFalse(WorkspacePaths.isValidName("with space"));
        assertFalse(WorkspacePaths.isValidName("UPPER"));
        assertFalse(WorkspacePaths.isValidName("a/b"));
    }

    @Test
    public void isDefaultDetectsDefaultName() {
        assertTrue(WorkspacePaths.isDefault(WorkspacePaths.DEFAULT_WORKSPACE));
        assertFalse(WorkspacePaths.isDefault("dev-rust"));
    }
}
