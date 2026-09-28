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
 * Test class for TemplatePromptBuilder
 */
public class TemplatePromptBuilderTest {

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
    public void testTemplatePromptBuilder_Constructor() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = new TemplatePromptBuilder("test-prompt");

            assertThat(builder).isNotNull();
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
    public void testWith_SingleVariable() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = new TemplatePromptBuilder("test-prompt");
            TemplatePromptBuilder result  = builder.with("name", "Alice");

            assertThat(result).isSameAs(builder); // Fluent interface
        }
    }

    @Test
    public void testWith_NullValues() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = new TemplatePromptBuilder("test-prompt");

            // Should not throw exception with null values
            TemplatePromptBuilder result1 = builder.with(null, "value");
            TemplatePromptBuilder result2 = builder.with("name", null);
            TemplatePromptBuilder result3 = builder.with(null, null);

            assertThat(result1).isSameAs(builder);
            assertThat(result2).isSameAs(builder);
            assertThat(result3).isSameAs(builder);
        }
    }

    @Test
    public void testWithAll_MultipleVariables() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = new TemplatePromptBuilder("test-prompt");

            Map<String, String> variables = new HashMap<>();
            variables.put("name", "Alice");
            variables.put("age", "30");
            variables.put("city", "New York");

            TemplatePromptBuilder result = builder.withAll(variables);

            assertThat(result).isSameAs(builder); // Fluent interface
        }
    }

    @Test
    public void testWithAll_NullMap() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = new TemplatePromptBuilder("test-prompt");

            // Should not throw exception with null map
            TemplatePromptBuilder result = builder.withAll(null);

            assertThat(result).isSameAs(builder);
        }
    }

    @Test
    public void testBuild() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = new TemplatePromptBuilder("test-prompt");
            builder.with("name", "Alice")
                   .with("greeting", "Hello");

            String result = builder.build();

            assertThat(result).isNotNull();
            assertThat(result).contains("test-prompt");
        }
    }

    @Test
    public void testGetRawTemplate() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = new TemplatePromptBuilder("test-prompt");

            String rawTemplate = builder.getRawTemplate();

            assertThat(rawTemplate).isNotNull();
            assertThat(rawTemplate).contains("test-prompt");
        }
    }

    @Test
    public void testFluentInterface_ChainedCalls() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            Map<String, String> baseVars = new HashMap<>();
            baseVars.put("base1", "value1");
            baseVars.put("base2", "value2");

            TemplatePromptBuilder builder = new TemplatePromptBuilder("test-prompt");
            String result = builder
                    .withAll(baseVars)
                    .with("extra", "value")
                    .with("final", "last")
                    .build();

            assertThat(result).isNotNull();
            assertThat(result).contains("test-prompt");
        }
    }

    @Test
    public void testStaticFactory_Chat() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = TemplatePromptBuilder.chat();

            assertThat(builder).isNotNull();

            String result = builder.build();
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    public void testStaticFactory_Edit() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = TemplatePromptBuilder.edit();

            assertThat(builder).isNotNull();

            String result = builder.build();
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    public void testStaticFactory_Analyze() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = TemplatePromptBuilder.analyze();

            assertThat(builder).isNotNull();

            String result = builder.build();
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    public void testStaticFactory_Explain() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = TemplatePromptBuilder.explain();

            assertThat(builder).isNotNull();

            String result = builder.build();
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    public void testStaticFactory_Suggest() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = TemplatePromptBuilder.suggest();

            assertThat(builder).isNotNull();

            String result = builder.build();
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    public void testStaticFactory_Refactor() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = TemplatePromptBuilder.refactor();

            assertThat(builder).isNotNull();

            String result = builder.build();
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    public void testStaticFactory_Agent() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = TemplatePromptBuilder.agent();

            assertThat(builder).isNotNull();

            String result = builder.build();
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    public void testStaticFactory_WebFetch() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = TemplatePromptBuilder.webFetch();

            assertThat(builder).isNotNull();

            String result = builder.build();
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    public void testStaticFactory_WebSearch() {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            setupMockConfigManager(configMock);

            TemplatePromptBuilder builder = TemplatePromptBuilder.webSearch();

            assertThat(builder).isNotNull();

            String result = builder.build();
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    public void testComplexScenario_WithVariableSubstitution() throws IOException {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            Path baseDir = tempFolder.newFolder("test-config").toPath();
            setupMockConfigManagerWithPath(configMock, baseDir);

            // Create user prompt directory and file with variables
            Path promptsDir = baseDir.resolve("prompts");
            Files.createDirectories(promptsDir);

            String userPromptContent = """
                                       {
                                           "name": "custom-greeting",
                                           "version": "1.0", 
                                           "content": "Hello {{name}}, welcome to {{location}}! Your role is {{role}}."
                                       }
                                       """;

            Files.writeString(promptsDir.resolve("custom-greeting.json"), userPromptContent);

            // Test the builder with this custom prompt
            TemplatePromptBuilder builder = new TemplatePromptBuilder("custom-greeting");
            String result = builder
                    .with("name", "Alice")
                    .with("location", "CadetCoder")
                    .with("role", "developer")
                    .build();

            assertThat(result).isEqualTo("Hello Alice, welcome to CadetCoder! Your role is developer.");
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