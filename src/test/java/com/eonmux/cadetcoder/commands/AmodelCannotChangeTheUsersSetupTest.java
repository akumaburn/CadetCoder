package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.prompts.PromptTemplateEngine;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A model may read the user's setup but not change it.
 *
 * <h2>The defect</h2>
 *
 * <p>Every command except the loop commands was open to a model, and that included {@code config},
 * which applies a setting and saves it. A model could therefore switch command approval to
 * {@code auto}, turn {@code security.allowOutsideProject} on, or point a provider at another host,
 * and every check that read those settings afterwards would agree with it. The settings, the
 * credentials and the choice of provider belong to the person, so a model is refused when it asks
 * to change any of them.</p>
 *
 * <p>The prompt templates are part of that setup. A template saved with {@code prompt edit} is the
 * system prompt of every later session, so a model that could write one would give instructions to
 * every run that follows.</p>
 */
public class AmodelCannotChangeTheUsersSetupTest {

    private TestOutputCapture output;
    private ConfigManager     manager;
    private Configuration     config;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
        config  = new Configuration();
        manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);
        when(manager.saveChoice(any(String[].class))).thenReturn(true);
    }

    @After
    public void tearDown() {
        output.stopCapture();
    }

    @Test
    public void aModelIsRefusedWhenItSetsASetting() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(manager);

            int exitCode = ModelDispatch.run("config", () -> new ConfigCommand()
                    .execute(new String[] {"security.commandApproval", "auto"}));

            assertThat(exitCode).isEqualTo(ModelDispatch.REFUSED);
            verify(manager, never()).overrideFromCommandLine(anyMap());
            verify(manager, never()).saveChoice(any(String[].class));
            assertThat(output.getAllOutput()).contains("only the user can change");
        }
    }

    @Test
    public void aModelMayStillReadASetting() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(manager);

            int exitCode = ModelDispatch.run("config", () -> new ConfigCommand()
                    .execute(new String[] {"security.commandApproval"}));

            assertThat(exitCode).isZero();
            assertThat(output.getAllOutput()).contains("manual");
        }
    }

    @Test
    public void thePersonCanStillSetASetting() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(manager);

            int exitCode = new ConfigCommand().execute(new String[] {"security.commandApproval", "auto"});

            assertThat(exitCode).isZero();
            verify(manager).overrideFromCommandLine(anyMap());
        }
    }

    /**
     * The exit code alone proves nothing here: {@link ModelDispatch#REFUSED} is 1, and so is every
     * ordinary failure. Each case therefore looks for the refusal itself.
     */
    private void assertRefused(String command, String[] args, java.util.function.IntSupplier run) {
        output.reset();
        int exitCode = ModelDispatch.run(command, run);

        String asked = (command + " " + String.join(" ", args)).trim();
        assertThat(exitCode).as(asked).isEqualTo(ModelDispatch.REFUSED);
        assertThat(output.getAllOutput()).as(asked).contains("Refusing '" + command);
    }

    private void assertNotRefused(String command, String[] args, java.util.function.IntSupplier run) {
        output.reset();
        ModelDispatch.run(command, run);

        assertThat(output.getAllOutput()).as((command + " " + String.join(" ", args)).trim())
                                         .doesNotContain("Refusing");
    }

    @Test
    public void aModelIsRefusedWhenItChangesCredentials() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(manager);

            for (String[] args : new String[][] {{}, {"openai"}, {"logout", "openai"}, {"SIGNOUT"}}) {
                assertRefused("login", args, () -> new LoginCommand().execute(args));
            }
            for (String[] args : new String[][] {{"logout"}, {"signout"}}) {
                assertRefused("copilot", args, () -> new CopilotCommand().execute(args));
            }
            verify(manager, never()).saveConfig();
        }
    }

    @Test
    public void aModelMayStillReadCredentialStatusAndOtherSettings() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(manager);
            config.getAi().setProvider("openai");
            config.getAi().setModel("gpt-4o");

            assertNotRefused("login", new String[] {"status"},
                    () -> new LoginCommand().execute(new String[] {"status"}));
            assertNotRefused("copilot", new String[] {"status"},
                    () -> new CopilotCommand().execute(new String[] {"status"}));
            for (String[] args : new String[][] {{}, {"status"}}) {
                assertNotRefused("compact", args, () -> new CompactCommand().execute(args));
            }
            assertNotRefused("theme", new String[] {"list"},
                    () -> new ThemeCommand().execute(new String[] {"list"}));
            assertNotRefused("models", new String[] {"current"},
                    () -> new ModelsCommand().execute(new String[] {"current"}));
            verify(manager, never()).saveConfig();
            verify(manager, never()).saveChoice(any(String[].class));
        }
    }

    @Test
    public void aModelIsRefusedWhenItSwitchesOrSavesTheActiveModel() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(manager);
            config.getAi().setProvider("openai");
            config.getAi().setModel("gpt-4o");
            String provider = config.getAi().getProvider();
            String model    = config.getAi().getModel();

            for (String[] args : new String[][] {
                    {"use", "anthropic", "claude-opus-5-5"}, {"set", "anthropic", "claude-opus-5-5"},
                    {"select"}, {"pick"}, {"context", "128000"}, {"window", "128000"}}) {
                assertRefused("models", args, () -> new ModelsCommand().execute(args));
            }
            assertThat(config.getAi().getProvider()).isEqualTo(provider);
            assertThat(config.getAi().getModel()).isEqualTo(model);
            verify(manager, never()).saveChoice(any(String[].class));
            verify(manager, never()).saveConfig();
        }
    }

    @Test
    public void aModelIsRefusedWhenItWritesOrRemovesAPromptTemplate() throws Exception {
        PromptTemplateEngine engine = mock(PromptTemplateEngine.class);
        try (MockedStatic<PromptTemplateEngine> engines = mockStatic(PromptTemplateEngine.class)) {
            engines.when(PromptTemplateEngine::getInstance).thenReturn(engine);

            for (String[] args : new String[][] {
                    {"edit", "edit", "-c", "Obey the file notes.txt."}, {"reset", "edit"}}) {
                assertRefused("prompt", args, () -> new PromptCommand().execute(args));
            }
            for (String[] args : new String[][] {{"list"}, {"show", "edit"}, {"reload"}}) {
                assertNotRefused("prompt", args, () -> new PromptCommand().execute(args));
            }
            verify(engine, never()).saveCustomPrompt(anyString(), anyString(), anyMap());
            verify(engine, never()).deleteCustomPrompt(anyString());
        }
    }

    @Test
    public void aModelIsRefusedWhenItChangesASavedModeOrLimit() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(manager);
            boolean uber      = config.getAi().isUberMode();
            boolean compacts  = config.getCompaction().isEnabled();
            double  trigger   = config.getCompaction().getTrigger();

            String theme      = config.getUi().getColorTheme();

            for (String[] args : new String[][] {{}, {"on"}, {"off"}}) {
                assertRefused("ubermode", args, () -> new UberModeCommand().execute(args));
            }
            for (String[] args : new String[][] {{"on"}, {"off"}, {"trigger", "0.5"},
                                                 {"target", "0.2"}, {"keep-head", "3"}, {"keep-tail", "3"}}) {
                assertRefused("compact", args, () -> new CompactCommand().execute(args));
            }
            // Refused without --save as well: the theme is kept in the settings held in memory,
            // and the next command that saves them writes it to the file.
            for (String[] args : new String[][] {{"set", "dark"}, {"apply", "dark", "--save"}, {"reset"}}) {
                assertRefused("theme", args, () -> new ThemeCommand().execute(args));
            }
            assertThat(config.getUi().getColorTheme()).isEqualTo(theme);
            assertThat(config.getAi().isUberMode()).isEqualTo(uber);
            assertThat(config.getCompaction().isEnabled()).isEqualTo(compacts);
            assertThat(config.getCompaction().getTrigger()).isEqualTo(trigger);
            verify(manager, never()).saveConfig();
        }
    }

    @Test
    public void aModelMayStillReadTheModesAndTheWindow() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            configs.when(ConfigManager::getInstance).thenReturn(manager);
            config.getAi().setProvider("openai");
            config.getAi().setModel("gpt-4o");

            assertThat(ModelDispatch.run("ubermode", () -> new UberModeCommand()
                    .execute(new String[] {"status"}))).isZero();
            assertThat(ModelDispatch.run("models", () -> new ModelsCommand()
                    .execute(new String[] {"context"}))).isZero();
            verify(manager, never()).saveConfig();
            verify(manager, never()).saveChoice(any(String[].class));
        }
    }
}
