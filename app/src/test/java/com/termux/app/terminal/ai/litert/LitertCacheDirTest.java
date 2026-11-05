package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public class LitertCacheDirTest {
    private static File tmp() throws Exception { return Files.createTempDirectory("litert-cache").toFile(); }

    @Test public void aMissingDirectoryIsCreatedWithItsParents() throws Exception {
        File dir = new File(tmp(), "cache/litert");
        assertEquals(dir, LitertCacheDir.ensure(dir));
        assertTrue(dir.isDirectory());
    }

    @Test public void anExistingDirectoryIsLeftAlone() throws Exception {
        File dir = tmp();
        File marker = new File(dir, "kept");
        assertTrue(marker.createNewFile());
        LitertCacheDir.ensure(dir);
        assertTrue(marker.isFile());
    }

    @Test public void aFileInThePlaceOfTheDirectoryIsATypedErrorNamingThePath() throws Exception {
        File file = new File(tmp(), "litert");
        assertTrue(file.createNewFile());
        try {
            LitertCacheDir.ensure(file);
            fail("expected a typed failure");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.CACHE_DIRECTORY_UNAVAILABLE, f.code);
            assertTrue(f.getMessage(), f.getMessage().contains(file.getPath()));
        }
    }

    @Test public void anUnwritableParentIsATypedErrorNamingThePath() throws Exception {
        File parent = tmp();
        File dir = new File(parent, "litert");
        assertTrue(parent.setWritable(false, false));
        try {
            LitertCacheDir.ensure(dir);
            fail("expected a typed failure");
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.CACHE_DIRECTORY_UNAVAILABLE, f.code);
            assertTrue(f.getMessage(), f.getMessage().contains(dir.getPath()));
        } finally {
            parent.setWritable(true, false);
        }
    }

    @Test public void theCodeNumberIsStable() {
        assertEquals(1018, LitertErrorCode.CACHE_DIRECTORY_UNAVAILABLE.number);
    }

    @Test public void aRefusingRuntimeFailsEveryLoadWithTheSameFailure() throws Exception {
        LitertFailure failure = new LitertFailure(LitertErrorCode.CACHE_DIRECTORY_UNAVAILABLE, null, "init", null, "no cache at /x", null);
        LitertRuntime runtime = LitertCacheDir.refusing(failure);
        for (int i = 0; i < 2; i++) {
            try {
                runtime.load("/m.litertlm", LitertBackend.CPU, 4096, LitertActivation.DEFAULT);
                fail("expected the refusal");
            } catch (LitertFailure f) {
                assertEquals(LitertErrorCode.CACHE_DIRECTORY_UNAVAILABLE, f.code);
                assertEquals("no cache at /x", f.getMessage());
            }
        }
    }
}
