package com.eonmux.cadetcoder.prompts;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.*;

/**
 * Test class for PromptTemplateEngine
 */
public class PromptTemplateEngineTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        // Reset singleton instance for each test
        PromptTemplateEngineTestHelper.resetInstance();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        PromptTemplateEngineTestHelper.resetInstance();
    }

    @Test
    public void testPromptTemplateEngine_Singleton() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine1 = PromptTemplateEngine.getInstance();
            PromptTemplateEngine engine2 = PromptTemplateEngine.getInstance();

            assertThat(engine1).isSameAs(engine2);
        }
    }

    private void setupMockConfigManager(MockedStatic<ConfigManager> configMock) {
        try {
            Path tempDir = tempFolder.newFolder("test-config").toPath();
            setupMockConfigManagerWithPath(configMock, tempDir);
        } catch (IOException e) {
            fail("Failed to create temp directory");
        }
    }

    private void setupMockConfigManagerWithPath(MockedStatic<ConfigManager> configMock, Path baseDir) {
        ConfigManager mockConfigManager = mock(ConfigManager.class);
        Configuration mockConfig        = mock(Configuration.class);

        when(mockConfig.getBaseDir()).thenReturn(baseDir.toString());
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);
        configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
    }

    @Test
    public void testGetPrompt_WithVariableSubstitution() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            Map<String, String> variables = new HashMap<>();
            variables.put("user_input", "test input");
            variables.put("context", "test context");

            String prompt = engine.getPrompt("test-template", variables);

            assertThat(prompt).isNotNull();
            assertThat(prompt).contains("test-template");
        }
    }

    @Test
    public void testGetPrompt_NoVariables() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            String prompt = engine.getPrompt("test-template", null);

            assertThat(prompt).isNotNull();
            assertThat(prompt).contains("test-template");
        }
    }

    @Test
    public void testGetRawPrompt() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            String rawPrompt = engine.getRawPrompt("test-template");

            assertThat(rawPrompt).isNotNull();
            assertThat(rawPrompt).contains("test-template");
        }
    }

    @Test
    public void testLoadUserOverride() throws IOException {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            Path baseDir = tempFolder.newFolder("test-config").toPath();
            setupMockConfigManagerWithPath(configMock, baseDir);

            // Create user prompt directory and file
            Path promptsDir = baseDir.resolve("prompts");
            Files.createDirectories(promptsDir);

            String userPromptContent = """
                                       {
                                           "name": "custom-prompt",
                                           "version": "1.0", 
                                           "content": "This is a custom user prompt with {{variable}}"
                                       }
                                       """;

            Files.writeString(promptsDir.resolve("custom-prompt.json"), userPromptContent);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            Map<String, String> variables = new HashMap<>();
            variables.put("variable", "test value");

            String prompt = engine.getPrompt("custom-prompt", variables);

            assertThat(prompt).contains("This is a custom user prompt with test value");
        }
    }

    @Test
    public void testVariableSubstitution() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            // Create a test template with variables
            String              testTemplate = "Hello {{name}}, welcome to {{place}}! Your score is {{score}}.";
            Map<String, String> variables    = new HashMap<>();
            variables.put("name", "Alice");
            variables.put("place", "CadetCoder");
            variables.put("score", "100");

            // Use reflection to test substituteVariables method
            try {
                java.lang.reflect.Method method = PromptTemplateEngine.class.getDeclaredMethod(
                        "substituteVariables", String.class, Map.class);
                method.setAccessible(true);

                String result = (String) method.invoke(engine, testTemplate, variables);

                assertThat(result).isEqualTo("Hello Alice, welcome to CadetCoder! Your score is 100.");
            } catch (Exception e) {
                fail("Failed to test variable substitution: " + e.getMessage());
            }
        }
    }

    @Test
    public void testVariableSubstitution_UnknownVariable() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            String              testTemplate = "Hello {{name}}, your {{unknown}} variable.";
            Map<String, String> variables    = new HashMap<>();
            variables.put("name", "Alice");

            try {
                java.lang.reflect.Method method = PromptTemplateEngine.class.getDeclaredMethod(
                        "substituteVariables", String.class, Map.class);
                method.setAccessible(true);

                String result = (String) method.invoke(engine, testTemplate, variables);

                assertThat(result).isEqualTo("Hello Alice, your {{unknown}} variable.");
            } catch (Exception e) {
                fail("Failed to test variable substitution: " + e.getMessage());
            }
        }
    }

    @Test
    public void testClearCache() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            // Load a prompt to populate cache
            engine.getRawPrompt("test-template");

            // Clear cache should not throw exception
            engine.clearCache();

            // ... and should print NOTHING. This used to assert that the console said "Prompt
            // template cache cleared", which it only did as a side effect of the engine's logger
            // going through SLF4J's console appender -- as "[INFO] PromptTemplateEngine - ...",
            // naming the Java class. The user-facing sentence belongs to the command that asked for
            // the reload (PromptCommand.reloadPrompts prints it), and having both meant the same
            // fact was reported twice, in two different shapes.
            assertThat(outputCapture.getAllOutput()).doesNotContain("Prompt template cache cleared");
        }
    }

    @Test
    public void testListAvailablePrompts() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            Map<String, String> prompts = engine.listAvailablePrompts();

            assertThat(prompts).isNotEmpty();
            assertThat(prompts).containsKey("chat");
            assertThat(prompts).containsKey("edit");
            assertThat(prompts).containsKey("analyze");
            assertThat(prompts.get("chat")).isEqualTo("default");
        }
    }

    @Test
    public void testSaveCustomPrompt() throws IOException {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            Path baseDir = tempFolder.newFolder("test-config").toPath();
            setupMockConfigManagerWithPath(configMock, baseDir);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            Map<String, String> metadata = new HashMap<>();
            metadata.put("author", "test user");
            metadata.put("description", "test prompt");

            engine.saveCustomPrompt("my-custom-prompt", "This is my custom prompt template", metadata);

            // Verify file was created
            Path promptFile = baseDir.resolve("prompts").resolve("my-custom-prompt.json");
            assertThat(Files.exists(promptFile)).isTrue();

            String content = Files.readString(promptFile);
            assertThat(content).contains("my-custom-prompt");
            assertThat(content).contains("This is my custom prompt template");
            assertThat(content).contains("test user");
        }
    }

    @Test
    public void testSaveCustomPrompt_NoMetadata() throws IOException {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            Path baseDir = tempFolder.newFolder("test-config").toPath();
            setupMockConfigManagerWithPath(configMock, baseDir);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            engine.saveCustomPrompt("simple-prompt", "Simple content", null);

            Path promptFile = baseDir.resolve("prompts").resolve("simple-prompt.json");
            assertThat(Files.exists(promptFile)).isTrue();

            String content = Files.readString(promptFile);
            assertThat(content).contains("simple-prompt");
            assertThat(content).contains("Simple content");
        }
    }

    @Test
    public void testInitialization_ConfigException() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenThrow(new RuntimeException("Config error"));

            // Should not throw exception, should use temp directory
            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            assertThat(engine).isNotNull();
        }
    }

    @Test
    public void testParsePromptJson_ContentField() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            String jsonContent = """
                                 {
                                     "name": "test",
                                     "content": "This is the prompt content"
                                 }
                                 """;

            try {
                java.lang.reflect.Method method = PromptTemplateEngine.class.getDeclaredMethod(
                        "parsePromptJson", String.class, boolean.class);
                method.setAccessible(true);

                String result = (String) method.invoke(engine, jsonContent, false);

                assertThat(result).isEqualTo("This is the prompt content");
            } catch (Exception e) {
                fail("Failed to test JSON parsing: " + e.getMessage());
            }
        }
    }

    @Test
    public void testParsePromptJson_TemplateField() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            String jsonContent = """
                                 {
                                     "name": "test",
                                     "template": "This is the template content"
                                 }
                                 """;

            try {
                java.lang.reflect.Method method = PromptTemplateEngine.class.getDeclaredMethod(
                        "parsePromptJson", String.class, boolean.class);
                method.setAccessible(true);

                String result = (String) method.invoke(engine, jsonContent, false);

                assertThat(result).isEqualTo("This is the template content");
            } catch (Exception e) {
                fail("Failed to test JSON parsing: " + e.getMessage());
            }
        }
    }

    @Test
    public void testParsePromptJson_InvalidJson() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            PromptTemplateEngine engine = PromptTemplateEngine.getInstance();

            String invalidJson = "{ invalid json }";

            try {
                java.lang.reflect.Method method = PromptTemplateEngine.class.getDeclaredMethod(
                        "parsePromptJson", String.class, boolean.class);
                method.setAccessible(true);

                String result = (String) method.invoke(engine, invalidJson, false);

                // Should fallback to raw content
                assertThat(result).isEqualTo(invalidJson);
            } catch (Exception e) {
                fail("Failed to test JSON parsing: " + e.getMessage());
            }
        }
    }

    /**
     * Helper class to reset the singleton instance for testing
     */
    public static class PromptTemplateEngineTestHelper {
        public static void resetInstance() {
            try {
                java.lang.reflect.Field instanceField = PromptTemplateEngine.class.getDeclaredField("instance");
                instanceField.setAccessible(true);
                instanceField.set(null, null);
            } catch (Exception e) {
                // Ignore reflection errors in tests
            }
        }
    }
}