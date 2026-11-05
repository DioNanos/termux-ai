package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The protocol 2 allowlist must carry the same NAMES as the legacy dispatch
 * switch, not just the same count: at an equal count a renamed command
 * would drift silently between the two surfaces. The switch lives in the
 * Android source of TermuxAiSocketServer, which these JVM tests cannot
 * load, so the parity is asserted on its source text: the string case
 * labels of the {@code switch (cmd)} block are the dispatch surface,
 * literally — other string switches in the same file (audio streams, sensor
 * types) are outside the block and must not leak in.
 */
public class AiV2CommandsTest {

    @Test
    public void theKnownNamesMatchTheLegacyDispatchSwitchExactly() throws IOException {
        String source = readServerSource();
        Set<String> labels = dispatchCaseLabels(source);
        assertFalse("the extraction really found the dispatch switch", labels.isEmpty());
        assertEquals("the allowlist and the legacy switch carry the same names",
            new HashSet<>(AiV2Commands.KNOWN), labels);
    }

    /**
     * Gradle runs JVM tests with the module directory as the working
     * directory; a plain checkout may run them from the repo root.
     */
    private static String readServerSource() throws IOException {
        String[] candidates = {
            "src/main/java/com/termux/app/terminal/ai/TermuxAiSocketServer.java",
            "app/src/main/java/com/termux/app/terminal/ai/TermuxAiSocketServer.java"};
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("TermuxAiSocketServer.java not found from "
            + Paths.get("").toAbsolutePath()
            + " (looked at " + String.join(", ", candidates) + ")");
    }

    /** The string case labels of the {@code switch (cmd)} block, and nothing else. */
    private static Set<String> dispatchCaseLabels(String source) {
        int switchAt = source.indexOf("switch (cmd)");
        assertTrue("the dispatch switch is where it always was", switchAt >= 0);
        int defaultAt = source.indexOf("default:", switchAt);
        assertTrue("the dispatch switch has its default", defaultAt > switchAt);
        Set<String> labels = new HashSet<>();
        Matcher matcher = Pattern.compile("case\\s+\"([^\"]+)\"").matcher(source);
        while (matcher.find()) {
            if (matcher.start() > switchAt && matcher.start() < defaultAt) {
                labels.add(matcher.group(1));
            }
        }
        return labels;
    }
}
