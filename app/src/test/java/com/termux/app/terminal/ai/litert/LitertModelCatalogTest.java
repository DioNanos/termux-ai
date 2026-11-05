package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

public class LitertModelCatalogTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File models() throws Exception { return tmp.newFolder("models"); }

    private static void write(File file, int bytes) throws Exception {
        Files.write(file.toPath(), new byte[bytes]);
    }

    private static LitertErrorCode codeOf(LitertModelCatalog catalog, String id) {
        try {
            catalog.resolve(id);
            fail("expected a failure for " + id);
            return null;
        } catch (LitertFailure f) {
            return f.code;
        }
    }

    @Test public void aMissingDirectoryIsAnEmptyCatalogNotAnError() throws Exception {
        JSONObject out = new LitertModelCatalog(new File(tmp.getRoot(), "nope")).list();
        assertFalse(out.getBoolean("directory_exists"));
        assertEquals(0, out.getJSONArray("models").length());
        assertEquals(0, out.getJSONArray("problems").length());
    }

    @Test public void listsRegularModelFilesSortedAndIgnoresOtherFiles() throws Exception {
        File dir = models();
        write(new File(dir, "b-model.litertlm"), 10);
        write(new File(dir, "a-model.litertlm"), 20);
        write(new File(dir, "notes.txt"), 5);
        JSONObject out = new LitertModelCatalog(dir).list();
        assertEquals(2, out.getJSONArray("models").length());
        assertEquals("a-model", out.getJSONArray("models").getJSONObject(0).getString("id"));
        assertEquals(20, out.getJSONArray("models").getJSONObject(0).getLong("size_bytes"));
        assertEquals(0, out.getJSONArray("problems").length());
    }

    @Test public void emptyFilesDirectoriesAndBadNamesAreProblemsNotModels() throws Exception {
        File dir = models();
        write(new File(dir, "empty.litertlm"), 0);
        assertTrue(new File(dir, "folder.litertlm").mkdir());
        write(new File(dir, "-bad.litertlm"), 4);
        write(new File(dir, "good.litertlm"), 4);
        JSONObject out = new LitertModelCatalog(dir).list();
        assertEquals(1, out.getJSONArray("models").length());
        assertEquals(3, out.getJSONArray("problems").length());
    }

    @Test public void aSymlinkOutsideTheDirectoryIsRejected() throws Exception {
        File dir = models();
        File outside = tmp.newFile("secret.bin");
        write(outside, 8);
        Files.createSymbolicLink(new File(dir, "leak.litertlm").toPath(), outside.toPath());
        LitertModelCatalog catalog = new LitertModelCatalog(dir);
        assertEquals(LitertErrorCode.MODEL_INVALID, codeOf(catalog, "leak"));
        JSONObject out = catalog.list();
        assertEquals(0, out.getJSONArray("models").length());
        assertEquals(1, out.getJSONArray("problems").length());
    }

    @Test public void aSymlinkInsideTheDirectoryResolvesToItsTarget() throws Exception {
        File dir = models();
        File real = new File(dir, "real.litertlm");
        write(real, 8);
        Files.createSymbolicLink(new File(dir, "alias.litertlm").toPath(), real.toPath());
        File resolved = new LitertModelCatalog(dir).resolve("alias");
        assertEquals(real.getCanonicalPath(), resolved.getPath());
    }

    @Test public void idsCannotTraverseAndUnknownModelsAreNotFound() throws Exception {
        LitertModelCatalog catalog = new LitertModelCatalog(models());
        for (String id : new String[] {"../x", "", ".hidden", "a b", null}) {
            assertEquals("id " + id, LitertErrorCode.INVALID_ARGUMENT, codeOf(catalog, id));
        }
        assertEquals(LitertErrorCode.MODEL_NOT_FOUND, codeOf(catalog, "absent"));
    }

    // ---- 12: models in subfolders are listed and usable, with the same guard as the top level

    private static void model(File root, String relative, int bytes) throws Exception {
        File file = new File(root, relative);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        write(file, bytes);
    }

    @Test public void modelsInSubfoldersAreListedWithTheirRelativeId() throws Exception {
        File dir = models();
        model(dir, "top.litertlm", 3);
        model(dir, "qwen/q3.litertlm", 5);
        model(dir, "qwen/small/q0.litertlm", 7);
        org.json.JSONArray list = new LitertModelCatalog(dir).list().getJSONArray("models");
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < list.length(); i++) ids.add(list.getJSONObject(i).getString("id"));
        assertEquals(java.util.Arrays.asList("qwen/q3", "qwen/small/q0", "top"), ids);
    }

    @Test public void aModelInASubfolderResolvesInsideTheRoot() throws Exception {
        File dir = models();
        model(dir, "qwen/q3.litertlm", 5);
        File resolved = new LitertModelCatalog(dir).resolve("qwen/q3");
        assertEquals(new File(dir, "qwen/q3.litertlm").getCanonicalPath(), resolved.getPath());
    }

    @Test public void traversalAndMalformedRelativeIdsAreStillRejected() throws Exception {
        LitertModelCatalog catalog = new LitertModelCatalog(models());
        for (String id : new String[] {"../x", "a/../x", "/abs", "a//b", "a/", "a/.hidden", "a/b/c/d", "a/ b"}) {
            assertEquals("id " + id, LitertErrorCode.INVALID_ARGUMENT, codeOf(catalog, id));
        }
        assertEquals(LitertErrorCode.MODEL_NOT_FOUND, codeOf(catalog, "qwen/absent"));
    }

    @Test public void aSymlinkInASubfolderThatLeavesTheRootIsRejected() throws Exception {
        File dir = models();
        File outside = tmp.newFile("secret2.bin");
        write(outside, 8);
        assertTrue(new File(dir, "qwen").mkdir());
        Files.createSymbolicLink(new File(dir, "qwen/leak.litertlm").toPath(), outside.toPath());
        LitertModelCatalog catalog = new LitertModelCatalog(dir);
        assertEquals(LitertErrorCode.MODEL_INVALID, codeOf(catalog, "qwen/leak"));
        JSONObject out = catalog.list();
        assertEquals(0, out.getJSONArray("models").length());
        assertEquals(1, out.getJSONArray("problems").length());
    }

    @Test public void aSymlinkedFolderIsNotFollowedAndIsReported() throws Exception {
        File dir = models();
        File other = tmp.newFolder("elsewhere");
        write(new File(other, "m.litertlm"), 9);
        Files.createSymbolicLink(new File(dir, "link").toPath(), other.toPath());
        JSONObject out = new LitertModelCatalog(dir).list();
        assertEquals(0, out.getJSONArray("models").length());
        assertEquals(1, out.getJSONArray("problems").length());
        assertEquals("link", out.getJSONArray("problems").getJSONObject(0).getString("file"));
    }

    @Test public void foldersNestedTooDeepAndBadlyNamedFoldersAreProblemsNotSilence() throws Exception {
        File dir = models();
        model(dir, "a/b/c/m.litertlm", 4);
        model(dir, "-bad/m.litertlm", 4);
        model(dir, "ok/m.litertlm", 4);
        JSONObject out = new LitertModelCatalog(dir).list();
        assertEquals(1, out.getJSONArray("models").length());
        assertEquals("ok/m", out.getJSONArray("models").getJSONObject(0).getString("id"));
        assertEquals(2, out.getJSONArray("problems").length());
    }
}
