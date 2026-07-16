package com.termux.shared;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.AgeFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class CommonsIoCompatibilityTest {

    @Test
    public void fileApisWorkWithNioDesugaring() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "commons-io-compat-" + System.nanoTime());

        try {
            FileUtils.forceMkdir(root);

            File sourceFile = new File(root, "source.txt");
            FileUtils.writeStringToFile(sourceFile, "payload", StandardCharsets.UTF_8);

            File copiedFile = new File(root, "copied.txt");
            FileUtils.copyFile(sourceFile, copiedFile, true);
            assertEquals("payload", FileUtils.readFileToString(copiedFile, StandardCharsets.UTF_8));

            File sourceDirectory = new File(root, "source-directory/nested");
            FileUtils.forceMkdir(sourceDirectory);
            FileUtils.writeStringToFile(new File(sourceDirectory, "nested.txt"), "nested", StandardCharsets.UTF_8);

            File copiedDirectory = new File(root, "copied-directory");
            FileUtils.copyDirectory(new File(root, "source-directory"), copiedDirectory, true);
            assertEquals("nested", FileUtils.readFileToString(
                new File(copiedDirectory, "nested/nested.txt"), StandardCharsets.UTF_8));

            Iterator<File> oldFiles = FileUtils.iterateFiles(
                root,
                new AgeFileFilter(new Date(System.currentTimeMillis() + 1000)),
                TrueFileFilter.INSTANCE);
            assertTrue(oldFiles.hasNext());

            File cleanDirectory = new File(root, "clean-directory");
            FileUtils.forceMkdir(cleanDirectory);
            FileUtils.writeStringToFile(new File(cleanDirectory, "entry.txt"), "entry", StandardCharsets.UTF_8);
            FileUtils.cleanDirectory(cleanDirectory);
            assertNotNull(cleanDirectory.list());
            assertEquals(0, cleanDirectory.list().length);

            File forceDeletedFile = new File(root, "force-delete.txt");
            FileUtils.writeStringToFile(forceDeletedFile, "delete", StandardCharsets.UTF_8);
            FileUtils.forceDelete(forceDeletedFile);
            assertFalse(forceDeletedFile.exists());

            File deletedDirectory = new File(root, "delete-directory");
            FileUtils.forceMkdir(deletedDirectory);
            FileUtils.writeStringToFile(new File(deletedDirectory, "entry.txt"), "entry", StandardCharsets.UTF_8);
            FileUtils.deleteDirectory(deletedDirectory);
            assertFalse(deletedDirectory.exists());

            ByteArrayInputStream input = new ByteArrayInputStream("stream".getBytes(StandardCharsets.UTF_8));
            assertEquals("stream", IOUtils.toString(input, StandardCharsets.UTF_8));
        } finally {
            FileUtils.deleteQuietly(root);
        }
    }
}
