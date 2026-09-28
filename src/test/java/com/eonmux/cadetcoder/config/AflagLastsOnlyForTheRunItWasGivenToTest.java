package com.eonmux.cadetcoder.config;

import com.eonmux.cadetcoder.Main;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A command-line flag changes the run it was given to, and never the configuration file.
 *
 * <h2>The defect</h2>
 *
 * <p>The flags were written straight into the live configuration, and {@code saveConfig()} writes
 * the live configuration. So the first command of the run that saved anything -- {@code models use},
 * {@code config set}, a login -- also saved every flag the run had been started with. One smoke test
 * run with {@code --base-dir} pointing at a scratch folder under {@code /tmp} moved the user's base
 * directory there for good: every later session wrote its logs, its session files and its index
 * into a folder that a reboot deletes, and nothing said so.</p>
 *
 * <h2>What still reaches the file</h2>
 *
 * <p>A setting the run changes after the flag -- {@code models use} after {@code --model} -- is a
 * choice made in the run, and it is saved like any other, even when the choice is the flag's own
 * value. So is everything no flag touched.</p>
 */
public class AflagLastsOnlyForTheRunItWasGivenToTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private String originalBaseDir;
    private Path   file;

    private static void resetSingleton() throws Exception {
        java.lang.reflect.Field instance = ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        java.lang.reflect.Field chosen = ConfigManager.class.getDeclaredField("chosen");
        chosen.setAccessible(true);
        chosen.set(null, null);
    }

    @Before
    public void setUp() throws Exception {
        originalBaseDir = Configuration.defaultBaseDir;
        Path home = tempFolder.getRoot().toPath().resolve("home");
        Configuration.defaultBaseDir = home.toString();
        resetSingleton();
        file = home.resolve("config.json");
        Files.createDirectories(home);
        Files.writeString(file, "{\"baseDir\":\"" + home + "\",\"ai\":{\"model\":\"from-the-file\"}}");
    }

    @After
    public void tearDown() throws Exception {
        Configuration.defaultBaseDir = originalBaseDir;
        resetSingleton();
    }

    private static Configuration live() {
        return ConfigManager.getInstance().getConfig();
    }

    private String saved() throws Exception {
        assertThat(ConfigManager.getInstance().saveConfig()).isTrue();
        return Files.readString(file);
    }

    @Test
    public void aflaggedBaseDirectoryIsUsedButNotSaved() throws Exception {
        String scratch = tempFolder.getRoot().toPath().resolve("scratch").toString();

        ConfigManager.getInstance().applyForThisRunOnly(() -> live().setBaseDir(scratch));

        assertThat(live().getBaseDir()).as("the run uses the flag").isEqualTo(scratch);
        assertThat(saved()).doesNotContain(scratch).contains(Configuration.defaultBaseDir);
    }

    @Test
    public void asettingTheRunChangesAfterTheFlagIsSaved() throws Exception {
        ConfigManager.getInstance().applyForThisRunOnly(() -> live().getAi().setModel("from-a-flag"));

        live().getAi().setModel("chosen-with-models-use");

        assertThat(saved()).contains("chosen-with-models-use").doesNotContain("from-a-flag");
    }

    @Test
    public void akeyAflagAddedIsNotWrittenToTheFile() throws Exception {
        ConfigManager.getInstance().applyForThisRunOnly(
                () -> live().getAi().getProviderApiKeys().put("somewhere", "sk-from-the-command-line"));

        assertThat(saved()).doesNotContain("sk-from-the-command-line");
        assertThat(live().getAi().getProviderApiKeys()).containsKey("somewhere");
    }

    @Test
    public void asettingNoFlagTouchedIsSavedAsBefore() throws Exception {
        ConfigManager.getInstance().applyForThisRunOnly(() -> live().getAi().setModel("from-a-flag"));

        live().getUi().setColorEnabled(false);

        String saved = saved();
        assertThat(saved).contains("from-the-file");
        assertThat(ConfigFile.read(file).config().getUi().isColorEnabled()).isFalse();
    }

    /**
     * {@code models use X} after {@code --model X} leaves the value as the flag set it, so only the
     * command that chose it can say it was chosen.
     */
    @Test
    public void asettingChosenAgainWithTheFlagsOwnValueIsSaved() throws Exception {
        ConfigManager.getInstance().applyForThisRunOnly(() -> live().getAi().setModel("same-model"));

        live().getAi().setModel("same-model");
        assertThat(ConfigManager.getInstance().saveChoice("AI.Model")).isTrue();

        assertThat(Files.readString(file)).contains("same-model");
        assertThat(saved()).as("a later save keeps the choice").contains("same-model");
    }

    @Test
    public void choosingOneSettingLeavesTheOtherFlagsOutOfTheFile() throws Exception {
        String scratch = tempFolder.getRoot().toPath().resolve("scratch").toString();
        ConfigManager.getInstance().applyForThisRunOnly(() -> {
            live().setBaseDir(scratch);
            live().getAi().setModel("from-a-flag");
        });

        assertThat(ConfigManager.getInstance().saveChoice("ai.model")).isTrue();

        assertThat(Files.readString(file)).contains("from-a-flag").doesNotContain(scratch);
    }

    @Test
    public void thebaseDirFlagOnTheCommandLineDoesNotReachTheFile() throws Exception {
        String scratch = tempFolder.getRoot().toPath().resolve("scratch").toString();

        Main.execute(new String[] {"--base-dir", scratch});

        assertThat(live().getBaseDir()).isEqualTo(scratch);
        assertThat(saved()).doesNotContain(scratch);
    }
}
