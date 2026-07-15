package com.termux.app.event;

import static com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand.Runner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TermuxBootScriptsRunnerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void collectBootScriptsUsesModernThenLegacyDirectoryAndSortsEach() throws Exception {
        File home = temporaryFolder.newFolder("home");
        File modern = new File(home, ".config/termux/boot");
        File legacy = new File(home, ".termux/boot");
        assertTrue(modern.mkdirs());
        assertTrue(legacy.mkdirs());

        assertTrue(new File(modern, "20-modern").createNewFile());
        assertTrue(new File(modern, "10-modern").createNewFile());
        assertTrue(new File(legacy, "20-legacy").createNewFile());
        assertTrue(new File(legacy, "10-legacy").createNewFile());
        assertTrue(new File(modern, "ignored-directory").mkdir());

        List<File> scripts = TermuxBootScriptsRunner.collectBootScripts(home);

        assertEquals(4, scripts.size());
        assertEquals("10-modern", scripts.get(0).getName());
        assertEquals("20-modern", scripts.get(1).getName());
        assertEquals("10-legacy", scripts.get(2).getName());
        assertEquals("20-legacy", scripts.get(3).getName());
    }

    @Test
    public void runDoesNotStartServiceWhenNoScriptsExist() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File home = temporaryFolder.newFolder("empty-home");
        List<Intent> launched = new ArrayList<>();

        int started = TermuxBootScriptsRunner.run(context, home,
            (serviceContext, intent) -> launched.add(intent));

        assertEquals(0, started);
        assertTrue(launched.isEmpty());
    }

    @Test
    public void runStartsExplicitBackgroundIntentForEachScript() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File home = temporaryFolder.newFolder("active-home");
        File directory = new File(home, ".termux/boot");
        assertTrue(directory.mkdirs());
        File script = new File(directory, "10-proof");
        assertTrue(script.createNewFile());

        List<Intent> launched = new ArrayList<>();
        int started = TermuxBootScriptsRunner.run(context, home,
            (serviceContext, intent) -> launched.add(intent));

        assertEquals(1, started);
        assertEquals(1, launched.size());

        Intent intent = launched.get(0);
        assertEquals(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, intent.getAction());
        assertNotNull(intent.getComponent());
        assertEquals(TermuxService.class.getName(), intent.getComponent().getClassName());
        assertEquals(script.getAbsolutePath(), intent.getData().getPath());
        assertEquals(Runner.APP_SHELL.getName(), intent.getStringExtra(TERMUX_SERVICE.EXTRA_RUNNER));
        assertTrue(intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false));
        assertEquals(home.getAbsolutePath(), intent.getStringExtra(TERMUX_SERVICE.EXTRA_WORKDIR));
    }
}
