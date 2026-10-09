package com.termux.app.terminal.ai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * The installer copies the scripts the app ships into the Termux bin directory.
 * The shell suite runs the assets directly, so a script the installer never copies is
 * only visible here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TermuxAiCliInstallerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static byte[] asset(Context context, String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    @Test
    public void everyNamedAssetIsInstalledExecutableAndIdenticalToTheAsset() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File bin = temporaryFolder.newFolder("bin");

        TermuxAiCliInstaller.installNamedAssets(context, bin);

        for (String name : TermuxAiCliInstaller.NAMED_ASSETS) {
            File installed = new File(bin, name);
            assertTrue(name + " is installed", installed.isFile());
            assertTrue(name + " is executable", installed.canExecute());
            assertArrayEquals(name + " is the shipped asset", asset(context, name), Files.readAllBytes(installed.toPath()));
        }
    }

    @Test
    public void termuxAiServeIsAmongTheInstalledAssets() {
        assertTrue(Arrays.asList(TermuxAiCliInstaller.NAMED_ASSETS).contains("termux-ai-serve"));
    }

    @Test
    public void everyShippedTermuxAiScriptIsInstalled() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        for (String name : context.getAssets().list("")) {
            if (!name.startsWith("termux-ai")) continue;
            // "termux-ai" itself is installed under every managed helper name.
            boolean installed = name.equals("termux-ai") || Arrays.asList(TermuxAiCliInstaller.NAMED_ASSETS).contains(name);
            assertTrue("the asset " + name + " ships in the app but the installer never copies it", installed);
        }
    }

    @Test
    public void anUpdateReplacesWhatTheOlderVersionInstalled() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File bin = temporaryFolder.newFolder("bin");
        for (String name : TermuxAiCliInstaller.NAMED_ASSETS) {
            Files.write(new File(bin, name).toPath(), "#!/bin/sh\necho old\n".getBytes());
        }

        TermuxAiCliInstaller.installNamedAssets(context, bin);

        for (String name : TermuxAiCliInstaller.NAMED_ASSETS) {
            assertArrayEquals(name + " is replaced by the shipped asset", asset(context, name),
                Files.readAllBytes(new File(bin, name).toPath()));
        }
    }
}
