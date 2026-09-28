package com.eonmux.cadetcoder.config;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.eonmux.cadetcoder.security.SecurityValidator;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --config <path>} has to be the file that is read and the file that is written.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code --config} was declared on the command line, listed in the README, accepted without
 * complaint -- and read by nothing. The field picocli filled in was never used, so every run
 * silently went on using {@code ~/.cadet/config.json}. A user pointing the tool at a second
 * configuration got the first one, with no error to say so; anyone keeping a separate profile for a
 * project or a sandbox was quietly running against their real settings.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Reading and writing are the same file. Loading from the named path and saving back to the
 * default one is worse than not implementing the option at all: the change appears to have been
 * accepted, and lands where nothing will read it.</p>
 *
 * <p>The named file holds the same API keys as the default one, so the file commands treat it as a
 * credential file wherever it is. The denylist names {@code .cadet/config.json}, and a named file
 * can have any name.</p>
 */
public class TheConfigFileTheUserNamedIsTheOneUsedTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private String originalBaseDir;

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
        Configuration.defaultBaseDir =
                tempFolder.getRoot().toPath().resolve("default-home").toString();
        resetSingleton();
    }

    @After
    public void tearDown() throws Exception {
        Configuration.defaultBaseDir = originalBaseDir;
        resetSingleton();
    }

    /** A configuration file naming a model nothing else would produce. */
    private Path fileNaming(String model) throws Exception {
        Path elsewhere = tempFolder.getRoot().toPath().resolve("elsewhere");
        Files.createDirectories(elsewhere);
        Path file = elsewhere.resolve("config.json");
        Files.writeString(file, "{\"ai\":{\"model\":\"" + model + "\"}}");
        return file;
    }

    @Test
    public void theFileNamedOnTheCommandLineIsWhereSettingsAreReadFrom() throws Exception {
        Path named = fileNaming("a-model-only-this-file-names");

        ConfigManager.useConfigFile(named.toString());

        assertThat(ConfigManager.getInstance().getConfig().getAi().getModel())
                .isEqualTo("a-model-only-this-file-names");
    }

    @Test
    public void theFileNamedOnTheCommandLineIsWhereAChangeIsWrittenBack() throws Exception {
        Path named = fileNaming("a-model-only-this-file-names");
        ConfigManager.useConfigFile(named.toString());

        ConfigManager.getInstance().getConfig().getAi().setModel("changed");
        assertThat(ConfigManager.getInstance().saveConfig()).isTrue();

        assertThat(Files.readString(named)).contains("changed");
        assertThat(Files.exists(Path.of(Configuration.defaultBaseDir, "config.json")))
                .as("a change accepted here must not land where nothing will read it")
                .isFalse();
    }

    @Test
    public void aConfigFileThatIsNotThereYetIsCreatedWhereItWasAskedFor() throws Exception {
        Path named = tempFolder.getRoot().toPath().resolve("new-profile").resolve("config.json");

        ConfigManager.useConfigFile(named.toString());

        assertThat(Files.exists(named)).isTrue();
    }

    @Test
    public void namingAFileAfterSettingsHaveAlreadyBeenReadStillMovesThem() throws Exception {
        ConfigManager.getInstance().getConfig().getAi().setModel("read-from-the-default-file");
        Path named = fileNaming("a-model-only-this-file-names");

        ConfigManager.useConfigFile(named.toString());

        assertThat(ConfigManager.getInstance().getConfig().getAi().getModel())
                .as("the option is applied before anything else, so what was read first is dropped")
                .isEqualTo("a-model-only-this-file-names");
    }

    @Test
    public void theFileNamedOnTheCommandLineIsTreatedAsACredentialFile() throws Exception {
        Path named = fileNaming("a-model-only-this-file-names");
        SecurityValidator validator = new SecurityValidator();
        assertThat(validator.isSensitiveCredentialFile(named.toString())).isFalse();

        ConfigManager.useConfigFile(named.toString());

        assertThat(validator.isSensitiveCredentialFile(named.toString())).isTrue();
        assertThat(validator.isSensitiveCredentialFile(named.getParent().resolve("x/../config.json")))
                .isTrue();
        assertThat(validator.commandReferencesCredentialFile("cat " + named)).isTrue();
    }
}
