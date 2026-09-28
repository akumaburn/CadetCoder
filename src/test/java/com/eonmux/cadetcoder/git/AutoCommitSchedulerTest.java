package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.testing.Await;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.*;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class AutoCommitSchedulerTest {

    @Mock
    private GitIntegration mockGitIntegration;

    @Mock
    private WatchService mockWatchService;

    @Mock
    private WatchKey mockWatchKey;

    @Mock
    private WatchEvent<Path> mockWatchEvent;

    @Mock
    private ConfigManager mockConfigManager;

    @Mock
    private Configuration mockConfiguration;

    @Mock
    private Configuration.GitConfig mockGitConfig;

    @Mock
    private Configuration.SecurityConfig mockSecurityConfig;

    private AutoCommitScheduler scheduler;
    private TestOutputCapture   outputCapture;
    private AutoCloseable       mocks;

    @Before
    public void setUp() throws Exception {
        mocks         = MockitoAnnotations.openMocks(this);
        outputCapture = new TestOutputCapture();
        scheduler     = new AutoCommitScheduler(mockGitIntegration);
        lenient().when(mockConfiguration.getSecurity()).thenReturn(mockSecurityConfig);
    }

    @After
    public void tearDown() throws Exception {
        outputCapture.restore();
        if (scheduler != null) {
            scheduler.stop();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void testStart_RefusedInReadOnlyMode() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockSecurityConfig.isReadOnlyMode()).thenReturn(true);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(true);

            scheduler.start();

            assertThat(outputCapture.getAllOutput()).doesNotContain("Auto-commit scheduler started");
            assertThat(outputCapture.getAllOutput()).contains("read-only");
            verify(mockGitIntegration, never()).commit(anyString());
        }
    }

    @Test
    public void testStart_WithInterval() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(true);
            when(mockGitConfig.getCommitTrigger()).thenReturn("oninterval");
            when(mockGitConfig.getCommitMessageTemplate()).thenReturn("Auto-commit: {date}");
            when(mockGitConfig.getAutoCommitIntervalMinutes()).thenReturn(60);

            scheduler.start();

            assertThat(outputCapture.getOutput()).contains("Auto-commit scheduler started with interval");
        }
    }

    @Test
    public void testStart_WithOnChange() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(true);
            when(mockGitConfig.getCommitTrigger()).thenReturn("onchange");
            when(mockGitConfig.getCommitMessageTemplate()).thenReturn("Auto-commit: {date}");

            scheduler.start();

            assertThat(outputCapture.getOutput()).contains("Auto-commit scheduler started with 'onChange' trigger");
        }
    }

    @Test
    public void testStart_Disabled() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(false);

            scheduler.start();

            // Should not start anything
            assertThat(outputCapture.getOutput()).doesNotContain("Auto-commit scheduler started");
        }
    }

    @Test
    public void testStop() throws Exception {
        scheduler.start();
        Thread.sleep(100); // Let it start

        scheduler.stop();

        assertThat(outputCapture.getOutput()).contains("Auto-commit scheduler stopped.");

        // Give thread time to stop
        Thread.sleep(200);
        assertThat(Thread.getAllStackTraces().keySet().stream()
                         .noneMatch(t -> t.getName().equals("AutoCommitSchedulerThread"))).isTrue();
    }

    @Test
    public void testIntervalCommit() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(true);
            when(mockGitConfig.getCommitTrigger()).thenReturn("oninterval");
            when(mockGitConfig.getCommitMessageTemplate()).thenReturn("Auto-commit: {date}");
            when(mockGitConfig.getAutoCommitIntervalMinutes()).thenReturn(60);

            scheduler.start();

            // Verify scheduler was started
            assertThat(outputCapture.getOutput()).contains("Auto-commit scheduler started with interval: 60 minutes.");

            // We can't verify immediate execution since it has a 60-minute delay
            // The scheduler should have been started successfully
        }
    }

    /**
     * The interval used to be the literal 60, beside a note saying to make it configurable. A person
     * who wanted a commit every ten minutes had no way to ask for one.
     */
    @Test
    public void testIntervalComesFromConfiguration() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(true);
            when(mockGitConfig.getCommitTrigger()).thenReturn("oninterval");
            when(mockGitConfig.getCommitMessageTemplate()).thenReturn("Auto-commit: {date}");
            when(mockGitConfig.getAutoCommitIntervalMinutes()).thenReturn(10);

            scheduler.start();

            assertThat(outputCapture.getOutput())
                    .contains("Auto-commit scheduler started with interval: 10 minutes.");
        }
    }

    /**
     * An interval below a minute is not a fast auto-commit, it is an
     * {@code IllegalArgumentException} out of {@code scheduleAtFixedRate} with nothing to say why.
     */
    @Test
    public void testAnIntervalBelowOneMinuteIsRefusedWithAReason() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(true);
            when(mockGitConfig.getCommitTrigger()).thenReturn("oninterval");
            when(mockGitConfig.getAutoCommitIntervalMinutes()).thenReturn(0);

            scheduler.start();

            assertThat(outputCapture.getAllOutput())
                    .contains("git.autoCommitIntervalMinutes must be at least 1")
                    .doesNotContain("Auto-commit scheduler started");
        }
    }

    @Test
    public void testStart_UnknownTrigger() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(true);
            when(mockGitConfig.getCommitTrigger()).thenReturn("unknown");

            scheduler.start();

            assertThat(outputCapture.getStderr())
                    .contains("Unknown git.commitTrigger value: unknown")
                    .contains("Expected");
        }
    }

    @Test
    public void testStart_MissingTriggerIsReportedRatherThanThrown() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(true);
            when(mockGitConfig.getCommitTrigger()).thenReturn(null);

            scheduler.start();

            assertThat(outputCapture.getStderr()).contains("Unknown git.commitTrigger value");
        }
    }

    @Test
    public void testDebouncer() throws Exception {
        // A Debouncer of its own, with a short delay. The behaviour under test is that rapid calls
        // collapse into one; the production delay of 5000ms is not part of that claim, and waiting
        // it out cost this test five seconds on every run to learn nothing extra.
        java.lang.reflect.Field debouncerField = AutoCommitScheduler.class.getDeclaredField("debouncer");
        debouncerField.setAccessible(true);
        Class<?> debouncerClass = debouncerField.getType();

        java.lang.reflect.Constructor<?> ctor = debouncerClass.getDeclaredConstructor(long.class);
        ctor.setAccessible(true);
        Object debouncer = ctor.newInstance(60L);

        java.lang.reflect.Method debounceMethod = debouncerClass.getDeclaredMethod("debounce", Runnable.class);
        debounceMethod.setAccessible(true);
        java.lang.reflect.Method shutdown = debouncerClass.getDeclaredMethod("shutdown");
        shutdown.setAccessible(true);

        java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
        Runnable task = executions::incrementAndGet;

        try {
            // Three calls inside one debounce window.
            debounceMethod.invoke(debouncer, task);
            debounceMethod.invoke(debouncer, task);
            debounceMethod.invoke(debouncer, task);

            Await.until("the debounced task to run", () -> executions.get() >= 1);
            // ... and only the last one survives.
            Await.staysFalse("a second execution", () -> executions.get() > 1);
        } finally {
            shutdown.invoke(debouncer);
        }
    }

    @Test
    public void testStartWatchService() throws Exception {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
             MockedStatic<FileSystems> fsMock = mockStatic(FileSystems.class)) {

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
            when(mockConfiguration.getGit()).thenReturn(mockGitConfig);
            when(mockGitConfig.isAutoCommitEnabled()).thenReturn(true);
            when(mockGitConfig.getCommitTrigger()).thenReturn("onchange");
            when(mockGitConfig.getCommitMessageTemplate()).thenReturn("Auto-commit: {date}");

            FileSystem mockFileSystem = mock(FileSystem.class);
            fsMock.when(FileSystems::getDefault).thenReturn(mockFileSystem);
            when(mockFileSystem.newWatchService()).thenReturn(mockWatchService);

            // Mock Files.walkFileTree to avoid actual file system access
            try (MockedStatic<Files> filesMock = mockStatic(Files.class);
                 MockedStatic<Paths> pathsMock = mockStatic(Paths.class)) {
                Path mockCurrentPath = mock(Path.class);
                pathsMock.when(() -> Paths.get(".")).thenReturn(mockCurrentPath);

                filesMock.when(() -> Files.walkFileTree(any(Path.class), any(SimpleFileVisitor.class)))
                         .thenReturn(mockCurrentPath);

                scheduler.start();

                verify(mockFileSystem).newWatchService();
                assertThat(outputCapture.getOutput()).contains("Auto-commit scheduler started with 'onChange' trigger");
            }
        }
    }

    @Test
    public void testRegisterAll() throws Exception {
        // Test the registerAll method through reflection
        java.lang.reflect.Method registerMethod =
                AutoCommitScheduler.class.getDeclaredMethod("registerAll", Path.class);
        registerMethod.setAccessible(true);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {

            Path testPath     = mock(Path.class);
            Path testFileName = mock(Path.class);
            when(testPath.getFileName()).thenReturn(testFileName);
            when(testFileName.toString()).thenReturn("test");

            Path gitPath     = mock(Path.class);
            Path gitFileName = mock(Path.class);
            when(gitPath.getFileName()).thenReturn(gitFileName);
            when(gitFileName.toString()).thenReturn(".git");

            Path cadetPath     = mock(Path.class);
            Path cadetFileName = mock(Path.class);
            when(cadetPath.getFileName()).thenReturn(cadetFileName);
            when(cadetFileName.toString()).thenReturn(".cadet");

            // Mock Files.walkFileTree to control the traversal
            filesMock.when(() -> Files.walkFileTree(eq(testPath), any(SimpleFileVisitor.class)))
                     .thenAnswer(invocation -> {
                         SimpleFileVisitor<Path> visitor = invocation.getArgument(1);
                         // Visit a normal directory
                         visitor.preVisitDirectory(testPath, null);
                         // Visit .git directory (should be skipped)
                         visitor.preVisitDirectory(gitPath, null);
                         // Visit .cadet directory (should be skipped)
                         visitor.preVisitDirectory(cadetPath, null);
                         return testPath;
                     });

            // Create scheduler and call registerAll
            java.lang.reflect.Field watchServiceField = AutoCommitScheduler.class.getDeclaredField("watchService");
            watchServiceField.setAccessible(true);
            watchServiceField.set(scheduler, mockWatchService);

            registerMethod.invoke(scheduler, testPath);

            // Verify only the test path was registered, not .git or .cadet
            verify(testPath, times(1)).register(eq(mockWatchService),
                    eq(StandardWatchEventKinds.ENTRY_CREATE),
                    eq(StandardWatchEventKinds.ENTRY_DELETE),
                    eq(StandardWatchEventKinds.ENTRY_MODIFY));
            verify(gitPath, never()).register(any(), any());
            verify(cadetPath, never()).register(any(), any());
        }
    }
}