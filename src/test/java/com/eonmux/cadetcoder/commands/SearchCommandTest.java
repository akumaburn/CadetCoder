package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.context.ContextMatch;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class SearchCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private SearchCommand     searchCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        searchCommand = new SearchCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }


    /** One match, as the engine now hands it back: a path and the lines that earned it. */
    private static ContextMatch match(String path, String... lines) {
        List<ContextMatch.Line> numbered = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            numbered.add(new ContextMatch.Line(i + 1, lines[i], true));
        }
        return new ContextMatch(path, 1.0f, numbered, 0);
    }

    @Test
    public void testSearchCommand_FoundResults() throws Exception {
        // Mock ContextEngine
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            when(mockEngine.find("findUser")).thenReturn(List.of(
                    match("src/UserService.java",
                          "public class UserService {",
                          "    public User findUser(String id) {")));

            // Execute command
            int exitCode = searchCommand.execute(new String[] {"findUser"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("public class UserService");
            assertThat(output).contains("public User findUser");

            // Verify search was called
            verify(mockEngine).find("findUser");
        }
    }

    @Test
    public void testSearchCommand_NoResults() throws Exception {
        // Mock ContextEngine
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            // Setup mock to return empty results
            when(mockEngine.find("nonexistent")).thenReturn(Collections.emptyList());

            // Execute command
            int exitCode = searchCommand.execute(new String[] {"nonexistent"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("No relevant snippets found");
        }
    }

    @Test
    public void testSearchCommand_ExceptionHandling() throws Exception {
        // Mock ContextEngine
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            // Setup mock to throw exception
            when(mockEngine.find(anyString())).thenThrow(new RuntimeException("Search error"));

            // Execute command
            int exitCode = searchCommand.execute(new String[] {"query"});

            // Verify
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Search error");
        }
    }

    @Test
    public void testSearchCommand_NoArguments() {
        // Mock AIManager for iterative execution
        try (MockedStatic<AIManager> aiManagerMock = mockStatic(AIManager.class)) {
            AIManager mockManager = mock(AIManager.class);
            aiManagerMock.when(AIManager::getInstance).thenReturn(mockManager);

            // Setup mock to handle the query request
            when(mockManager.complete(any(PromptData.class), any(Map.class)))
                    .thenReturn("");  // Empty response, no query provided

            // Execute command without arguments
            int exitCode = searchCommand.execute(new String[] {});

            // Verify - will ask for query via iterative execution
            // Since no query is provided (empty response), it will complete with cancellation
            assertThat(exitCode).isEqualTo(1);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("No query provided");
        }
    }

    @Test
    public void testSearchCommand_MultipleSnippets() throws Exception {
        // Mock ContextEngine
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            when(mockEngine.find("user")).thenReturn(List.of(
                    match("src/UserService.java", "public User getUser(String id) {"),
                    match("src/UserController.java", "public Response getUserById(String id) {"),
                    match("src/UserRepository.java", "User findById(String id);")));

            // Execute command
            int exitCode = searchCommand.execute(new String[] {"user"});

            // Verify
            assertThat(exitCode).isEqualTo(0);
            String output = outputCapture.getAllOutput();
            assertThat(output).contains("UserService.java");
            assertThat(output).contains("UserController.java");
            assertThat(output).contains("UserRepository.java");
        }
    }

    @Test
    public void testSearchCommand_ResultsVisibleAtMinimalVerbosity() throws Exception {
        // Regression for finding 19: primary search snippets were routed through the
        // verbosity-gated printInfo and thus suppressed at MINIMAL verbosity while the
        // command still reported success. They now use a non-gated channel.
        com.eonmux.cadetcoder.config.Configuration.UiConfig ui =
                com.eonmux.cadetcoder.config.ConfigManager.getInstance().getConfig().getUi();
        int originalVerbosity = ui.getVerbosityLevel();
        try {
            ui.setVerbosityLevel(
                    com.eonmux.cadetcoder.config.Configuration.UiConfig.VERBOSITY.MINIMAL.ordinal());
            com.eonmux.cadetcoder.ui.ThemedOutputFormatter.clearColorCache();

            try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
                ContextEngine mockEngine = mock(ContextEngine.class);
                contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);
                when(mockEngine.find("token")).thenReturn(List.of(
                        match("src/Token.java", "SNIPPET_MARKER_VISIBLE")));

                int exitCode = searchCommand.execute(new String[] {"token"});

                assertThat(exitCode).isEqualTo(0);
                assertThat(outputCapture.getAllOutput()).contains("SNIPPET_MARKER_VISIBLE");
            }
        } finally {
            ui.setVerbosityLevel(originalVerbosity);
        }
    }

    @Test
    public void testSearchCommand_PassesThePunctuationStraightThrough() throws Exception {
        // index-search-3: ordinary punctuation must not reach the Lucene parser raw -- and the
        // escaping that stops it belongs to ContextEngine, which is the one place that parses. The
        // command therefore hands over what the user typed, unchanged. Escaping it here as well
        // would turn every backslash the engine's own pass adds into a term of its own.
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            String rawQuery = "foo:bar(baz)";
            when(mockEngine.find(rawQuery)).thenReturn(List.of(
                    match("src/Foo.java", "match for punctuation query")));

            int exitCode = searchCommand.execute(new String[] {rawQuery});

            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getAllOutput()).contains("match for punctuation query");
            verify(mockEngine).find(rawQuery);
            verify(mockEngine, never())
                    .find(org.apache.lucene.queryparser.classic.QueryParser.escape(rawQuery));
        }
    }

    @Test
    public void testSearchCommand_ParseExceptionDegradesToNoResults() throws Exception {
        // index-search-3: if the engine still cannot parse the query, degrade to a clear
        // "no results" with a success exit code instead of an error exit.
        try (MockedStatic<ContextEngine> contextEngineMock = mockStatic(ContextEngine.class)) {
            ContextEngine mockEngine = mock(ContextEngine.class);
            contextEngineMock.when(ContextEngine::getInstance).thenReturn(mockEngine);

            when(mockEngine.find(anyString()))
                    .thenThrow(new org.apache.lucene.queryparser.classic.ParseException("bad query"));

            int exitCode = searchCommand.execute(new String[] {"weird"});

            assertThat(exitCode).isEqualTo(0);
            assertThat(outputCapture.getAllOutput()).contains("No relevant snippets found");
        }
    }

    @Test
    public void testGetDescription() {
        assertThat(searchCommand.getDescription())
                .isEqualTo("Find code by meaning, using the project index");
    }

    @Test
    public void testGetUsage() {
        assertThat(searchCommand.getUsage())
                .isEqualTo("search \"<query>\"");
    }
}