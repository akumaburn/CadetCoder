package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ModelsCommand}, focusing on the model-configuration verbs
 * ({@code current}, {@code use}, {@code select}). Config writes are isolated to a temp home.
 */
public class ModelsCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ModelsCommand     modelsCommand;
    private TestOutputCapture outputCapture;
    private String            originalUserHome;
    private String            originalBaseDir;

    @Before
    public void setUp() throws Exception {
        modelsCommand = new ModelsCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();

        // Both are process-wide and both outlive this class unless they are put back: surefire
        // runs every test in one reused fork, so whatever is left here is what the next 90-odd
        // classes see.
        originalUserHome = System.getProperty("user.home");
        originalBaseDir  = Configuration.defaultBaseDir;
        System.setProperty("user.home", tempFolder.getRoot().getAbsolutePath());
        Configuration.defaultBaseDir = tempFolder.getRoot().getAbsolutePath() + "/.cadet";

        java.lang.reflect.Field configInstance = ConfigManager.class.getDeclaredField("instance");
        configInstance.setAccessible(true);
        configInstance.set(null, null);
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        Configuration.defaultBaseDir = originalBaseDir;
    }

    @Test
    public void getDescriptionAndUsageMentionConfiguration() {
        assertThat(modelsCommand.getDescription()).contains("configure the active model");
        assertThat(modelsCommand.getUsage()).contains("select");
        assertThat(modelsCommand.getUsage()).contains("use <provider>");
    }

    @Test
    public void providers_listsConnectors() {
        int exitCode = modelsCommand.execute(new String[] {"providers"});
        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("anthropic");
    }

    @Test
    public void current_showsActiveModel() {
        int exitCode = modelsCommand.execute(new String[] {"current"});
        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("Active model");
    }

    /**
     * {@code models current} names the input window and where the figure came from.
     *
     * <p>The window decides how much of a prompt may be sent. Until it was printed here, the only
     * way to learn it was a request refused for being too large, and the only way to learn where
     * the figure came from was to read the code. A model served through a gateway takes its
     * window from the catalog at large rather than from the provider being called, so the figure
     * is worth being able to check before it costs a run.</p>
     */
    @Test
    public void current_saysWhatTheInputWindowIsAndWhereItCameFrom() throws Exception {
        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        ai.setProvider("anthropic");
        ai.setModel("claude-sonnet-4-5");
        ai.setContextTokens(0);
        try {
            assertThat(modelsCommand.execute(new String[] {"current"})).isEqualTo(0);

            assertThat(outputCapture.getAllOutput())
                    .contains("Window:")
                    .contains("input tokens")
                    .contains("published for anthropic/claude-sonnet-4-5");
        } finally {
            // The singleton outlives this class, and what was set here is not what the next one
            // should see.
            Field configInstance = ConfigManager.class.getDeclaredField("instance");
            configInstance.setAccessible(true);
            configInstance.set(null, null);
        }
    }

    @Test
    public void use_unknownProvider_returnsError() {
        int exitCode = modelsCommand.execute(new String[] {"use", "not-a-provider"});
        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Unknown provider: not-a-provider");
    }

    @Test
    public void use_missingProvider_returnsUsageError() {
        int exitCode = modelsCommand.execute(new String[] {"use"});
        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput())
                .contains("Which provider should I use?")
                .contains("Usage: cadet models use <provider> [model]");
    }

    @Test
    public void use_setsActiveProviderAndModel() {
        int exitCode = modelsCommand.execute(new String[] {"use", "local", "llama-test"});

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("Active model set to local/llama-test");

        Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
        assertThat(ai.getProvider()).isEqualTo("local");
        assertThat(ai.getModel()).isEqualTo("llama-test");
    }

    @Test
    public void use_keyProviderWithoutCredential_warnsButStillSets() {
        int exitCode = modelsCommand.execute(new String[] {"use", "anthropic", "claude-test"});

        assertThat(exitCode).isEqualTo(0);
        String out = outputCapture.getAllOutput();
        assertThat(out).contains("No credential found for anthropic");
        assertThat(out).contains("Active model set to anthropic/claude-test");
        assertThat(ConfigManager.getInstance().getConfig().getAi().getProvider()).isEqualTo("anthropic");
    }

    /**
     * config-models-4: the success confirmation emitted by {@code applyActiveModel} must not
     * disclose the absolute on-disk config path. (The catalog/path message originates in
     * ConfigManager.saveConfig, owned elsewhere; this asserts the command-level message stays
     * path-free.)
     */
    @Test
    public void use_successMessage_doesNotDiscloseConfigPath() {
        modelsCommand.execute(new String[] {"use", "local", "llama-test"});

        String configPath = Configuration.defaultBaseDir;
        // The "Active model set to ..." confirmation line must not embed the config directory.
        for (String line : outputCapture.getAllOutput().split("\\R")) {
            if (line.contains("Active model set to")) {
                assertThat(line).doesNotContain(configPath);
                assertThat(line).doesNotContain("config.json");
            }
        }
    }

    /**
     * config-models-3: when persisting the configuration fails, the command reports an error,
     * suppresses the "active model set" success confirmation, and returns a non-zero exit code.
     */
    @Test
    public void use_whenSaveFails_reportsErrorAndReturnsNonZero() throws Exception {
        // Force ConfigManager to materialise so we can corrupt its target path.
        ConfigManager manager = ConfigManager.getInstance();

        // Point the private config path at a location whose parent is a regular file, so
        // Files.createDirectories(parent) fails and saveConfig() returns false.
        File blockingFile = tempFolder.newFile("blocking-parent");
        Path unwritable = blockingFile.toPath().resolve("nested").resolve("config.json");

        Field configPathField = ConfigManager.class.getDeclaredField("configPath");
        configPathField.setAccessible(true);
        configPathField.set(manager, unwritable);

        int exitCode = modelsCommand.execute(new String[] {"use", "local", "llama-test"});

        assertThat(exitCode).isEqualTo(1);
        String out = outputCapture.getAllOutput();
        assertThat(out).contains("the active model was not persisted");
        assertThat(out).doesNotContain("Active model set to local/llama-test");
    }

    /**
     * What the connector table says about retention.
     *
     * <h2>Why this is here</h2>
     *
     * <p>{@code ai.zeroDataRetention} is on unless it is turned off, and only a provider offering a
     * way to refuse retention can honour it. With nothing saying which providers those are, a
     * setting that reads as on everywhere would be doing nothing on most of the table -- a privacy
     * guarantee believed rather than held. Four states have to be told apart: the provider cannot
     * promise; it can and was not asked; it was asked and will not retain the request; and it was
     * asked and can only keep the exchange out of stored history, which is a weaker thing and must
     * not be printed as though it were the same one.</p>
     */
    @Test
    public void theTableSaysWhereZeroRetentionActuallyBites() {
        ProviderRegistry registry     = ProviderRegistry.getInstance();
        ProviderConnector commandcode = registry.get("commandcode");
        ProviderConnector openrouter  = registry.get("openrouter");
        ProviderConnector openai      = registry.get("openai");
        ProviderConnector xai         = registry.get("xai");

        assertThat(ModelsCommand.retentionLabel(commandcode, true)).isEqualTo("zero retention");
        assertThat(ModelsCommand.retentionLabel(openrouter, true)).isEqualTo("zero retention");
        assertThat(ModelsCommand.retentionLabel(commandcode, false)).contains("off");

        // OpenAI has no per-request zero retention: store=false is all a request can say, and
        // calling that "zero retention" would report a guarantee OpenAI never gave.
        assertThat(ModelsCommand.retentionLabel(openai, true)).isEqualTo("no storage");
        assertThat(ModelsCommand.retentionLabel(openai, false)).contains("off");

        // Nothing to ask of it, so the setting changes nothing either way.
        assertThat(ModelsCommand.retentionLabel(xai, true)).isEqualTo("provider default");
        assertThat(ModelsCommand.retentionLabel(xai, false)).isEqualTo("provider default");
    }
}
