package com.termux.shared.termux.shell.command.environment;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The wire contract of the environment variable the termux-exec PTY hook reads to learn the
 * calling process's selinux context: with it set, the hook does not open /proc/self/attr/current
 * between fork and exec. Only the constant is reflected on here — the rest of the class needs
 * Android — and it runs as a plain JVM unit test.
 */
public class TermuxAppShellEnvironmentTest {

    @Test
    public void theHookVariableIsExportedUnderItsContractName() throws Exception {
        Object value = TermuxAppShellEnvironment.class
            .getField("ENV_TERMUX__SE_PROCESS_CONTEXT").get(null);
        assertEquals("TERMUX__SE_PROCESS_CONTEXT", value);
        assertEquals("TERMUX__SE_PROCESS_CONTEXT",
            com.termux.shared.termux.TermuxConstants.TERMUX_ENV_PREFIX_ROOT + "__SE_PROCESS_CONTEXT");
    }
}
