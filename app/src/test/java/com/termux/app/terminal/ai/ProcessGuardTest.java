package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProcessGuardTest {
    @Test public void onlyTheLitertProcessOfThisPackageIsGuarded() {
        assertTrue(ProcessGuard.isLitertProcess("com.termux:litert", "com.termux"));
        assertFalse(ProcessGuard.isLitertProcess("com.termux", "com.termux"));
        assertFalse(ProcessGuard.isLitertProcess("com.termux:other", "com.termux"));
        assertFalse(ProcessGuard.isLitertProcess("org.other:litert", "com.termux"));
        assertFalse(ProcessGuard.isLitertProcess("com.termux:litert2", "com.termux"));
        assertFalse(ProcessGuard.isLitertProcess(null, "com.termux"));
        assertFalse(ProcessGuard.isLitertProcess("com.termux:litert", null));
    }

    @Test public void theProcessNameIsTheFirstNulTerminatedPart() {
        assertEquals("com.termux:litert", ProcessGuard.parseCmdline("com.termux:litert\0--flag\0".getBytes()));
        assertEquals("com.termux", ProcessGuard.parseCmdline("com.termux".getBytes()));
        assertNull(ProcessGuard.parseCmdline(new byte[0]));
        assertNull(ProcessGuard.parseCmdline(new byte[] {0, 'a'}));
        assertNull(ProcessGuard.parseCmdline(null));
    }
}
