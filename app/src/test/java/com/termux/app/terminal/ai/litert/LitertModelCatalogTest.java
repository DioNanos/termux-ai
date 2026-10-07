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
        for (String id : new String[] {"../x", "a/b", "", ".hidden", "a b", null}) {
            assertEquals("id " + id, LitertErrorCode.INVALID_ARGUMENT, codeOf(catalog, id));
        }
        assertEquals(LitertErrorCode.MODEL_NOT_FOUND, codeOf(catalog, "absent"));
    }
}
