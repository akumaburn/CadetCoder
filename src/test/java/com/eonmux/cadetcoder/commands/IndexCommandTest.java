package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class IndexCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private IndexCommand      indexCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        indexCommand  = new IndexCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void testIndexCommand_Success() throws Exception {
        // Mock ContextEngine
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            when(mockEngine.reindex()).thenReturn(new ContextEngine.ReindexSummary(3, 0, 0));
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            // Execute command
            int exitCode = indexCommand.execute(new String[] {});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getStdout();
            assertThat(output).contains("Code index updated").contains("3 files indexed");

            // Exactly one scan for one command: the engine's constructor used to run its own.
            verify(mockEngine, times(1)).reindex();
        }
    }

    @Test
    public void testIndexCommand_WithArguments() throws Exception {
        // Mock ContextEngine
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            when(mockEngine.reindex()).thenReturn(new ContextEngine.ReindexSummary(0, 5, 0));
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            // A single existing path argument triggers a (full) reindex.
            int exitCode = indexCommand.execute(new String[] {"."});

            // Verify - should still work
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getStdout();
            assertThat(output).contains("Code index updated").contains("all unchanged");

            verify(mockEngine, times(1)).reindex();
        }
    }

    @Test
    public void testIndexCommand_ReindexFailure() throws Exception {
        // Mock ContextEngine
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            // Setup mock to throw exception
            doThrow(new RuntimeException("Index error")).when(mockEngine).reindex();

            // Execute command
            int exitCode = indexCommand.execute(new String[] {});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Index error");
        }
    }

    @Test
    public void testIndexCommand_IOException() throws Exception {
        // Mock ContextEngine
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            // Setup mock to throw IOException
            doThrow(new java.io.IOException("Failed to write index")).when(mockEngine).reindex();

            // Execute command
            int exitCode = indexCommand.execute(new String[] {});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Failed to write index");
        }
    }

    @Test
    public void anIndexThatHadToBeRebuiltIsMentionedOnce() throws Exception {
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            when(mockEngine.wasIndexRebuilt()).thenReturn(true);
            when(mockEngine.reindex()).thenReturn(new ContextEngine.ReindexSummary(2, 0, 0));
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            assertThat(indexCommand.execute(new String[] {})).isEqualTo(0);

            assertThat(outputCapture.getAllOutput())
                    .contains("could not be read")
                    .contains("rebuilt from scratch");
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(indexCommand.getDescription())
                .isEqualTo("Rebuild the searchable index of the project's files");
    }

    @Test
    public void testGetUsage() {
        assertThat(indexCommand.getUsage())
                .isEqualTo("index [path]");
    }
}