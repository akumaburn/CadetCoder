package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.commands.TodoReadCommand;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class SessionManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private SessionManager sessionManager;
    private Path           sessionFile;

    @Before
    public void setUp() throws Exception {
        // Reset singleton
        try {
            java.lang.reflect.Field instance = SessionManager.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            // Ignore
        }

        // Session file should be in the base directory
        sessionFile = tempFolder.getRoot().toPath().resolve("session.json");
    }

    @After
    public void tearDown() {
        // Clean up
    }

    @Test
    public void testSessionManager_SaveAndLoad() throws Exception {
        // Mock ConfigManager
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            mockConfig.setBaseDir(tempFolder.getRoot().getAbsolutePath());

            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Get instance
            sessionManager = SessionManager.getInstance();

            // Set data
            sessionManager.addToConversationHistory("User: test");
            sessionManager.addToConversationHistory("AI: response");

            // Add todos
            List<TodoReadCommand.TodoItem> todos = List.of(
                    new TodoReadCommand.TodoItem("1", "Task 1",
                            TodoReadCommand.TodoItem.Status.PENDING,
                            TodoReadCommand.TodoItem.Priority.HIGH)
                                                          );
            sessionManager.setTodoList(todos);

            // Save session
            sessionManager.saveSession();

            // Verify file exists
            assertThat(sessionFile).exists();

            // Reset instance and reload
            java.lang.reflect.Field instance = SessionManager.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);

            SessionManager newManager = SessionManager.getInstance();

            // Verify loaded data
            assertThat(newManager.getConversationHistory())
                    .containsExactly("User: test", "AI: response");
            assertThat(newManager.getTodoList()).hasSize(1);
            assertThat(newManager.getTodoList().get(0).getContent()).isEqualTo("Task 1");
        }
    }

    @Test
    public void testSessionManager_EmptySession() {
        // Mock ConfigManager
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            mockConfig.setBaseDir(tempFolder.getRoot().getAbsolutePath());

            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Get instance
            sessionManager = SessionManager.getInstance();

            // Verify defaults
            assertThat(sessionManager.getConversationHistory()).isEmpty();
            assertThat(sessionManager.getTodoList()).isEmpty();
            assertThat(sessionManager.getTranscript()).isEmpty();
        }
    }

    @Test
    public void testSessionManager_CorruptedSessionFile() throws Exception {
        // Mock ConfigManager
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            mockConfig.setBaseDir(tempFolder.getRoot().getAbsolutePath());

            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Create corrupted session file
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(sessionFile, "{ invalid json }");

            // Get instance - should handle corrupted file gracefully
            sessionManager = SessionManager.getInstance();

            // Should initialize with defaults
            assertThat(sessionManager.getConversationHistory()).isEmpty();
            assertThat(sessionManager.getTodoList()).isEmpty();
        }
    }

    @Test
    public void testSessionManager_UpdateOperations() {
        // Mock ConfigManager
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            mockConfig.setBaseDir(tempFolder.getRoot().getAbsolutePath());

            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Get instance
            sessionManager = SessionManager.getInstance();

            // Test conversation history
            sessionManager.addToConversationHistory("Message 1");
            sessionManager.addToConversationHistory("Message 2");
            List<String> history = sessionManager.getConversationHistory();
            assertThat(history).hasSize(2);
            assertThat(history).containsExactly("Message 1", "Message 2");
        }
    }

    @Test
    public void testSessionManager_Singleton() {
        // Mock ConfigManager
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager mockConfigManager = mock(ConfigManager.class);
            Configuration mockConfig        = new Configuration();
            mockConfig.setBaseDir(tempFolder.getRoot().getAbsolutePath());

            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);

            // Test singleton
            SessionManager instance1 = SessionManager.getInstance();
            SessionManager instance2 = SessionManager.getInstance();

            assertThat(instance1).isSameAs(instance2);
        }
    }
}