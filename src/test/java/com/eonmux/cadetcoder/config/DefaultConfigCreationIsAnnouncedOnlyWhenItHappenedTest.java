package com.eonmux.cadetcoder.config;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Created new default configuration at ..." must name a file that exists.
 *
 * <p>{@code loadDefaultConfig} discarded {@code saveConfig()}'s result, so on a home directory that
 * could not be written the user was shown {@code saveConfig}'s own "Failed to save configuration"
 * and then this line, naming a path with nothing at it. Every subsequent run announced the same
 * creation again.</p>
 */
public class DefaultConfigCreationIsAnnouncedOnlyWhenItHappenedTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private String            originalBaseDir;
    private Path              configDir;
    private TestOutputCapture output;

    private static void resetSingleton() throws Exception {
        java.lang.reflect.Field instance = ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Before
    public void setUp() throws Exception {
        originalBaseDir = Configuration.defaultBaseDir;
        configDir       = tempFolder.getRoot().toPath().resolve("cadet-home");
        Configuration.defaultBaseDir = configDir.toString();
        resetSingleton();
    }

    @After
    public void tearDown() throws Exception {
        if (output != null) {
            output.restore();
        }
        if (Files.isDirectory(configDir)) {
            try {
                Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Nothing to restore on a filesystem without POSIX permissions.
            }
        }
        Configuration.defaultBaseDir = originalBaseDir;
        resetSingleton();
    }

    @Test
    public void aDefaultConfigThatCouldNotBeWrittenIsNotAnnouncedAsCreated() throws Exception {
        ConfigManager manager = ConfigManager.getInstance();
        Path          configFile = configDir.resolve("config.json");
        Files.deleteIfExists(configFile);

        try {
            Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("r-x------"));
        } catch (UnsupportedOperationException e) {
            Assume.assumeNoException("needs POSIX permissions to make the directory unwritable", e);
        }
        Assume.assumeFalse("the test user can write the directory anyway (running as root?)",
                Files.isWritable(configDir));

        output = new TestOutputCapture();
        manager.loadDefaultConfig();
        String printed = output.getAllOutput();

        assertThat(Files.exists(configFile))
                .as("the premise of the test: the file could not be written")
                .isFalse();
        assertThat(printed)
                .as("nothing was created, so nothing may be announced as created")
                .doesNotContain("Created new default configuration");
        assertThat(printed)
                .as("the failure itself must still be reported")
                .contains("Failed to save configuration");
    }

    @Test
    public void aDefaultConfigThatWasWrittenIsStillAnnounced() throws Exception {
        ConfigManager manager    = ConfigManager.getInstance();
        Path          configFile = configDir.resolve("config.json");
        Files.deleteIfExists(configFile);

        output = new TestOutputCapture();
        manager.loadDefaultConfig();

        assertThat(Files.exists(configFile)).isTrue();
        assertThat(output.getAllOutput()).contains("Created new default configuration");
    }

    /**
     * The tool's directory holds sessions, logs and an index of the user's code. Created at the
     * umask it is readable by every local account on a machine whose home directory is.
     */
    @Test
    public void theDefaultBaseDirectoryIsOwnerOnly() throws Exception {
        ConfigManager.getInstance();
        Assume.assumeTrue("needs POSIX permissions",
                Files.getFileStore(configDir).supportsFileAttributeView(
                        java.nio.file.attribute.PosixFileAttributeView.class));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(configDir)))
                .isEqualTo("rwx------");
    }

    @Test
    public void anExistingDefaultBaseDirectoryIsNarrowedWhenItIsRead() throws Exception {
        Files.createDirectories(configDir);
        Assume.assumeTrue("needs POSIX permissions",
                Files.getFileStore(configDir).supportsFileAttributeView(
                        java.nio.file.attribute.PosixFileAttributeView.class));
        Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("rwxr-xr-x"));

        ConfigManager.getInstance();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(configDir)))
                .isEqualTo("rwx------");
    }
}
