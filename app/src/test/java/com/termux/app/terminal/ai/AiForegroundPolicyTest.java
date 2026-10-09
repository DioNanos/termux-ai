package com.termux.app.terminal.ai;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The blocking AICore failures name a remedy; the others do not. */
public class AiForegroundPolicyTest {

    @Test public void theForegroundFailuresNameARemedy() {
        assertNotNull(AiForegroundPolicy.remedyFor("FOREGROUND_REQUIRED"));
        assertNotNull(AiForegroundPolicy.remedyFor("BACKGROUND_USE_BLOCKED"));
    }

    @Test public void otherFailuresNameNoRemedy() {
        assertNull(AiForegroundPolicy.remedyFor("BUSY"));
        assertNull(AiForegroundPolicy.remedyFor("NEEDS_SYSTEM_UPDATE"));
        assertNull(AiForegroundPolicy.remedyFor(null));
    }
}
