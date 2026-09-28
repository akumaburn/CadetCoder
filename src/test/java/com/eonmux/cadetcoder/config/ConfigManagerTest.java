package com.eonmux.cadetcoder.config;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ConfigManager configManager;
    private Path          configFile;
    private String        originalUserHome;
    private String        originalBaseDir;

    @Before
    public void setUp() throws Exception {
        // Save original user home and base dir
        originalUserHome = System.getProperty("user.home");
        originalBaseDir  = Configuration.defaultBaseDir;

        // Reset singleton before each test
        resetConfigManagerSingleton();

        // Set default base dir to temp folder
        Configuration.defaultBaseDir = tempFolder.getRoot().getAbsolutePath() + "/.config/cadet";

        // Set system property to use temp directory
        System.setProperty("user.home", tempFolder.getRoot().getAbsolutePath());
        configFile = tempFolder.getRoot().toPath().resolve(".config/cadet/config.json");
    }

    private void resetConfigManagerSingleton() throws Exception {
        java.lang.reflect.Field instance = ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @After
    public void tearDown() throws Exception {
        // Restore original user home
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }

        // Restore what was actually there.
        //
        // This used to RECONSTRUCT a default of "<user.home>/.config/cadet", which is not the
        // default -- Configuration uses "<user.home>/.cadet". Every run therefore left the shared
        // static aimed at a directory the application never uses, inside the real home, and
        // anything that asked for the configuration afterwards built a tree there: config.json,
        // logs, prompts, saved sessions and all.
        Configuration.defaultBaseDir = originalBaseDir;

        // Reset singleton after each test
        resetConfigManagerSingleton();
    }

    @Test
    public void testConfigManager_DefaultConfiguration() {
        configManager = ConfigManager.getInstance();
        Configuration config = configManager.getConfig();

        // Test default values
        assertThat(config).isNotNull();
        assertThat(config.getAi()).isNotNull();
        assertThat(config.getAi().getModel()).isEqualTo("o1-mini");
        assertThat(config.getAi().getTemperature()).isEqualTo(0.7f);
        // Zero means no ceiling is asked for. How long an answer needs to be is not knowable
        // before it is written, so nothing guesses one. See net/OutputBudget.
        assertThat(config.getAi().getMaxTokens()).isEqualTo(0);

        assertThat(config.getSecurity()).isNotNull();
        assertThat(config.getSecurity().isReadOnlyMode()).isFalse();
        assertThat(config.getSecurity().isRequireConfirmation()).isTrue();

        assertThat(config.getGit()).isNotNull();
        assertThat(config.getGit().isAutoCommitEnabled()).isFalse();
    }

    @Test
    public void testConfigManager_LoadFromFile() throws Exception {
        // Create config file
        String configJson = """
                            {
                                "ai": {
                                    "model": "custom-model",
                                    "temperature": 0.5,
                                    "maxTokens": 2048,
                                    "apiEndpoint": "https://custom.api.com"
                                },
                                "security": {
                                    "readOnlyMode": true,
                                    "requireConfirmation": false
                                },
                                "git": {
                                    "autoCommitEnabled": false,
                                    "commitMessageTemplate": "Custom: {changeSummary}"
                                }
                            }
                            """;

        // Create config directory and file
        Path configDir = configFile.getParent();
        Files.createDirectories(configDir);
        Files.writeString(configFile, configJson);

        // Get instance - should load from file
        configManager = ConfigManager.getInstance();
        Configuration config = configManager.getConfig();

        // Test loaded values
        assertThat(config.getAi().getModel()).isEqualTo("custom-model");
        assertThat(config.getAi().getTemperature()).isEqualTo(0.5f);
        assertThat(config.getAi().getMaxTokens()).isEqualTo(2048);
        assertThat(config.getAi().getApiEndpoint()).isEqualTo("https://custom.api.com");

        assertThat(config.getSecurity().isReadOnlyMode()).isTrue();
        assertThat(config.getSecurity().isRequireConfirmation()).isFalse();

        assertThat(config.getGit().isAutoCommitEnabled()).isFalse();
        assertThat(config.getGit().getCommitMessageTemplate()).isEqualTo("Custom: {changeSummary}");
    }

    @Test
    public void testConfigManager_SaveConfiguration() throws Exception {
        configManager = ConfigManager.getInstance();
        Configuration config = configManager.getConfig();

        // Modify configuration
        config.getAi().setModel("modified-model");
        config.getAi().setTemperature(0.9f);
        config.getSecurity().setReadOnlyMode(true);

        // Save
        configManager.saveConfig();

        // Verify file exists
        assertThat(configFile).exists();

        // Reset singleton and reload
        resetConfigManagerSingleton();

        ConfigManager newManager     = ConfigManager.getInstance();
        Configuration reloadedConfig = newManager.getConfig();

        // Verify saved values
        assertThat(reloadedConfig.getAi().getModel()).isEqualTo("modified-model");
        assertThat(reloadedConfig.getAi().getTemperature()).isEqualTo(0.9f);
        assertThat(reloadedConfig.getSecurity().isReadOnlyMode()).isTrue();
    }

    /**
     * A configuration that will not parse must say so.
     *
     * <p>{@code loadConfig} caught the mapping failure and substituted defaults, and the message was
     * gated on {@code !initializing} -- which is false for the whole of the constructor, the only
     * place the first load ever happens. So every setting the user had saved was silently discarded
     * on every run, and the only evidence was the tool behaving as though nothing was configured.</p>
     */
    @Test
    public void testConfigManager_UnparseableConfigIsReportedRatherThanSilentlyDiscarded()
            throws Exception {
        java.nio.file.Files.createDirectories(configFile.getParent());
        java.nio.file.Files.writeString(configFile, "{ not valid json");
        resetConfigManagerSingleton();

        java.util.List<String> output =
                com.eonmux.cadetcoder.ui.OutputCapture.collect(ConfigManager::getInstance);

        assertThat(String.join("\n", output))
                .as("the user has to be told their settings are not in effect")
                .contains("Failed to load configuration")
                .contains("defaults");
    }

    @Test
    public void testConfigManager_InvalidConfigFile() throws Exception {
        // Create invalid config file
        String invalidJson = """
                             {
                                 "ai": {
                                     "model": 12345,
                                     "temperature": "invalid"
                                 }
                             """;

        Path configDir = configFile.getParent();
        Files.createDirectories(configDir);
        Files.writeString(configFile, invalidJson);

        // Get instance - should fall back to defaults
        configManager = ConfigManager.getInstance();
        Configuration config = configManager.getConfig();

        // Should have default values
        assertThat(config.getAi().getModel()).isEqualTo("o1-mini");
        assertThat(config.getAi().getTemperature()).isEqualTo(0.7f);
    }

    @Test
    public void testConfigManager_LoadConfigFromFile() throws Exception {
        // Create custom config file
        File customConfigFile = tempFolder.newFile("custom-config.json");
        String configJson = """
                            {
                                "ai": {
                                    "model": "path-model",
                                    "maxTokens": 1024
                                }
                            }
                            """;
        Files.writeString(customConfigFile.toPath(), configJson);

        // Get instance first
        configManager = ConfigManager.getInstance();

        // Load config from custom file
        configManager.loadConfigFromFile(customConfigFile.getAbsolutePath());
        Configuration config = configManager.getConfig();

        // Test loaded values
        assertThat(config.getAi().getModel()).isEqualTo("path-model");
        assertThat(config.getAi().getMaxTokens()).isEqualTo(1024);
    }

    @Test
    public void testConfigManager_EmptyConfigFile() throws Exception {
        // Create empty config file
        Path configDir = configFile.getParent();
        Files.createDirectories(configDir);
        Files.writeString(configFile, "{}");

        // Get instance
        configManager = ConfigManager.getInstance();
        Configuration config = configManager.getConfig();

        // Should have default values
        assertThat(config.getAi().getModel()).isEqualTo("o1-mini");
        assertThat(config.getSecurity().isReadOnlyMode()).isFalse();
        assertThat(config.getGit().isAutoCommitEnabled()).isFalse();
    }

    @Test
    public void testConfigManager_PartialConfiguration() throws Exception {
        // Create partial config file
        String partialJson = """
                             {
                                 "ai": {
                                     "model": "partial-model"
                                 },
                                 "security": {
                                     "readOnlyMode": true
                                 }
                             }
                             """;

        Path configDir = configFile.getParent();
        Files.createDirectories(configDir);
        Files.writeString(configFile, partialJson);

        // Verify file was written
        assertThat(Files.exists(configFile)).isTrue();
        String content = Files.readString(configFile);
        assertThat(content).contains("partial-model");

        // Get instance
        configManager = ConfigManager.getInstance();
        Configuration config = configManager.getConfig();

        // Test mixed values (some from file, some defaults)
        assertThat(config.getAi().getModel()).isEqualTo("partial-model");
        assertThat(config.getAi().getTemperature()).isEqualTo(0.7f); // Field default from Configuration class
        assertThat(config.getSecurity().isReadOnlyMode()).isTrue();
        assertThat(config.getSecurity().isRequireConfirmation()).isTrue(); // Field default from Configuration class
    }

    /**
     * Skips the calling test when the temp folder's filesystem has no POSIX permissions
     * (Windows, and non-POSIX mounts elsewhere) -- there is nothing to assert there.
     */
    private void assumePosixFileSystem() throws Exception {
        Assume.assumeTrue(
                Files.getFileStore(tempFolder.getRoot().toPath())
                     .supportsFileAttributeView(PosixFileAttributeView.class));
    }

    @Test
    public void testConfigManager_SaveConfigRestrictsPermissionsToOwner() throws Exception {
        assumePosixFileSystem();

        configManager = ConfigManager.getInstance();
        // The config file stores ai.apiKey / ai.providerApiKeys, so it must never be left at the
        // umask default (typically rw-r--r--), which exposes live API keys to every local account.
        configManager.getConfig().getAi().setApiKey("sk-secret-value");

        assertThat(configManager.saveConfig()).isTrue();

        assertThat(configFile).exists();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(configFile)))
                .isEqualTo("rw-------");
        // The secret really is in the file the permissions are protecting.
        assertThat(Files.readString(configFile)).contains("sk-secret-value");
    }

    @Test
    public void testConfigManager_LoadRepairsWorldReadableConfig() throws Exception {
        assumePosixFileSystem();

        String configJson = """
                            {
                                "ai": {
                                    "model": "repaired-model",
                                    "apiKey": "sk-existing-secret"
                                }
                            }
                            """;

        Path configDir = configFile.getParent();
        Files.createDirectories(configDir);
        Files.writeString(configFile, configJson);
        // Simulate a config written before permissions were enforced.
        Files.setPosixFilePermissions(configFile, PosixFilePermissions.fromString("rw-r--r--"));

        configManager = ConfigManager.getInstance();

        // Loading an existing config repairs its permissions instead of leaving the keys exposed.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(configFile)))
                .isEqualTo("rw-------");
        // Repairing permissions must not disturb the contents.
        assertThat(configManager.getConfig().getAi().getModel()).isEqualTo("repaired-model");
        assertThat(configManager.getConfig().getAi().getApiKey()).isEqualTo("sk-existing-secret");
    }
}
