package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ConfigCommand     configCommand;
    private TestOutputCapture outputCapture;
    private String            originalUserHome;
    private String            originalBaseDir;

    @Before
    public void setUp() throws Exception {
        configCommand = new ConfigCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();

        // Set up temp directory as home
        // Both are process-wide and both outlive this class unless they are put back: surefire
        // runs every test in one reused fork, so whatever is left here is what the next 90-odd
        // classes see.
        originalUserHome = System.getProperty("user.home");
        originalBaseDir  = Configuration.defaultBaseDir;
        System.setProperty("user.home", tempFolder.getRoot().getAbsolutePath());
        Configuration.defaultBaseDir = tempFolder.getRoot().getAbsolutePath() + "/.cadet";

        // Reset ConfigManager singleton
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
    public void testConfigCommand_ShowConfig() {
        // Execute command without arguments
        int exitCode = configCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("ai");
        assertThat(output).contains("model");
        assertThat(output).contains("temperature");
        assertThat(output).contains("context");
    }

    @Test
    public void testConfigCommand_SetOption() {
        // Execute command to set an option
        int exitCode = configCommand.execute(new String[] {"ai.temperature", "0.8"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Configuration updated: ai.temperature = 0.8");

        // Verify the configuration was actually updated
        Configuration config = ConfigManager.getInstance().getConfig();
        assertThat(config.getAi().getTemperature()).isEqualTo(0.8f);
    }

    @Test
    public void testConfigCommand_SetModel() {
        // Execute command to set model
        int exitCode = configCommand.execute(new String[] {"ai.model", "gpt-4"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Configuration updated: ai.model = gpt-4");

        // Verify the configuration was actually updated
        Configuration config = ConfigManager.getInstance().getConfig();
        assertThat(config.getAi().getModel()).isEqualTo("gpt-4");
    }

    @Test
    public void oneArgumentShowsThatOneSetting() {
        // A single name used to be an error, so checking one value meant reading eighty lines of
        // JSON to find it.
        int exitCode = configCommand.execute(new String[] {"ai.model"});

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("ai.model = ");
    }

    @Test
    public void anUnknownSettingIsReportedWithTheNearestNames() {
        int exitCode = configCommand.execute(new String[] {"ai.modle"});

        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("No such setting: ai.modle");
        assertThat(output).contains("ai.model");
    }

    @Test
    public void everySettingIsListedByTheNameThatSetsIt() {
        int exitCode = configCommand.execute(new String[0]);

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getAllOutput();
        // The listed name is the one the setter takes: the JSON it used to print showed the
        // nesting but never the dotted key you have to type back.
        assertThat(output).contains("ai.model");
        assertThat(output).contains("indexing.enabled");
        assertThat(output).doesNotContain("\"ai\" : {");
    }

    @Test
    public void secretsAreNeverPrinted() {
        ConfigManager.getInstance().getConfig().getAi().setApiKey("sk-NOT-FOR-THE-CONSOLE");
        try {
            configCommand.execute(new String[0]);
            configCommand.execute(new String[] {"ai.apiKey"});

            assertThat(outputCapture.getAllOutput()).doesNotContain("sk-NOT-FOR-THE-CONSOLE");
        } finally {
            ConfigManager.getInstance().getConfig().getAi().setApiKey("");
        }
    }

    @Test
    public void testConfigCommand_InvalidUsage_TooManyArguments() {
        // Execute command with too many arguments
        int exitCode = configCommand.execute(new String[] {"ai.model", "gpt-4", "extra"});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Too many arguments");
        assertThat(output).contains("config <name> <value>");
    }

    @Test
    public void testConfigCommand_SetBooleanOption() {
        // Execute command to set a boolean option
        int exitCode = configCommand.execute(new String[] {"ui.colorEnabled", "false"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Configuration updated: ui.colorEnabled = false");

        // Verify the configuration was actually updated
        Configuration config = ConfigManager.getInstance().getConfig();
        assertThat(config.getUi().isColorEnabled()).isFalse();
    }

    @Test
    public void testConfigCommand_SetIntegerOption() {
        // Execute command to set an integer option
        int exitCode = configCommand.execute(new String[] {"ai.maxTokens", "8192"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Configuration updated: ai.maxTokens = 8192");

        // Verify the configuration was actually updated
        Configuration config = ConfigManager.getInstance().getConfig();
        assertThat(config.getAi().getMaxTokens()).isEqualTo(8192);
    }

    @Test
    public void testConfigCommand_ShowConfig_RedactsProviderApiKeys() {
        // A real provider API key is stored keyed by a NON-secret provider id; the no-arg
        // dump must mask the VALUE, not print it. Sentinel value only - never a real key.
        final String sentinel = "FAKE_PROVIDER_KEY_MUST_BE_REDACTED";
        ConfigManager.getInstance().getConfig().getAi().getProviderApiKeys().put("anthropic", sentinel);

        int exitCode = configCommand.execute(new String[] {});

        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("providerApiKeys");
        assertThat(output).doesNotContain(sentinel);
        assertThat(output).contains("***redacted***");
    }

    @Test
    public void testGetDescription() {
        assertThat(configCommand.getDescription())
                .isEqualTo("Show or change a setting");
    }

    @Test
    public void testGetUsage() {
        assertThat(configCommand.getUsage())
                .contains("config                      Show every setting")
                .contains("config <name>               Show one setting")
                .contains("config <name> <value>");
    }
}