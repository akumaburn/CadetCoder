package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Searching the index with a sentence somebody wrote.
 *
 * <h2>The defect</h2>
 *
 * <p>The text reached Lucene's {@code QueryParser} exactly as it was typed, so every character the
 * query language reserves was read as syntax. A request that mentioned a file path, or any pair of
 * slashes, opened a regular expression that never closed:</p>
 *
 * <pre>
 * Cannot parse 'Add a ... palette with BG=#1c1410 (warm dark brown), SURFACE=#2a1f18
 * (surface/border), TEXT=#e8d5b7 ...': Lexical error at line 1, column 810.
 * Encountered: &lt;EOF&gt; after prefix "/border), TEXT=#e8d5b7 ..." (in lexical state 2)
 * </pre>
 *
 * <p>Nobody who uses this types a query language. Every caller passes what a person or a model
 * wrote: {@code edit} passes the edit request, the agent loop passes the task, and {@code search}
 * passes the search words. Escaping the text and parsing it anyway would answer that and leave a
 * second failure in place, because the parser also refuses a query of more than 1,024 terms. The
 * text now goes through the analyzer instead and never reaches the parser at all.</p>
 *
 * <h2>What it cost</h2>
 *
 * <p>Two different failures for the same cause. An {@code edit} ended the step with "Error preparing
 * edit", which the run then had to ask the model how to recover from. The agent loop caught it and
 * carried on with no index context at all, so a run lost the project's own code from its prompt and
 * said only "Could not retrieve context".</p>
 */
public class WhatSomebodyTypedIsNotAQueryLanguageTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String wasWorkingDir;
    private File   project;
    private File   indexDirectory;

    @Before
    public void setUp() throws Exception {
        wasWorkingDir  = System.getProperty("user.dir");
        project        = folder.newFolder("test-project");
        indexDirectory = folder.newFolder("index-" + System.nanoTime());
        System.setProperty("user.dir", project.getAbsolutePath());
    }

    @After
    public void tearDown() {
        if (wasWorkingDir != null) {
            System.setProperty("user.dir", wasWorkingDir);
        }
        ContextEngine.resetInstance();
    }

    private ConfigManager configuredManager() {
        ConfigManager                manager  = mock(ConfigManager.class);
        Configuration                config   = new Configuration();
        Configuration.IndexingConfig indexing = new Configuration.IndexingConfig();
        indexing.setEnabled(false);
        indexing.setIndexLocation(indexDirectory.getAbsolutePath());
        config.setIndexing(indexing);
        Configuration.ContextConfig context = new Configuration.ContextConfig();
        context.setMaxFiles(50);
        config.setContext(context);
        when(manager.getConfig()).thenReturn(config);
        return manager;
    }

    /** Runs {@code body} against an engine holding one indexed file. */
    private void withIndexed(String fileName, String content, Search body) throws Exception {
        Files.writeString(project.toPath().resolve(fileName), content);
        ConfigManager manager = configuredManager();
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            ContextEngine engine = ContextEngine.getInstance();
            engine.reindex();
            body.run(engine);
        }
    }

    /** What a test does with the engine once it has an index. */
    private interface Search {
        void run(ContextEngine engine) throws Exception;
    }

    @Test
    public void aRequestThatNamesAPathIsNotARegularExpression() throws Exception {
        withIndexed("palette.txt", "a warm dark brown surface and border colour",
                    engine -> assertThatCode(
                            () -> engine.searchRelevantSnippets(
                                    "add a dark/orange/brown palette with SURFACE=#2a1f18"
                                    + " (surface/border)"))
                            .doesNotThrowAnyException());
    }

    @Test
    public void aRequestThatNamesAFileIsNotARegularExpression() throws Exception {
        withIndexed("palette.txt", "a warm dark brown surface and border colour",
                    engine -> assertThatCode(
                            () -> engine.searchRelevantSnippets(
                                    "edit src/main/java/com/eonmux/cadetcoder/ui/ColorTheme.java"))
                            .doesNotThrowAnyException());
    }

    @Test
    public void everyCharacterTheQueryLanguageReservesIsJustText() throws Exception {
        // One test rather than one per character: they were all read as syntax by the same parser,
        // and a fix that covered some of them would leave the rest broken.
        withIndexed("notes.txt", "nothing in particular",
                    engine -> {
                        for (String reserved : new String[]{"/", "\\", "+", "-", "!", "(", ")", ":",
                                                            "^", "[", "]", "\"", "{", "}", "~", "*",
                                                            "?", "|", "&"}) {
                            assertThatCode(() -> engine.searchRelevantSnippets("a " + reserved + " b"))
                                    .as("a query containing " + reserved)
                                    .doesNotThrowAnyException();
                        }
                    });
    }

    @Test
    public void anUnbalancedQuoteIsNotAnUnfinishedPhrase() throws Exception {
        withIndexed("notes.txt", "nothing in particular",
                    engine -> assertThatCode(
                            () -> engine.searchRelevantSnippets("the file said \"hello"))
                            .doesNotThrowAnyException());
    }

    @Test
    public void theWordsStillFindTheFile() throws Exception {
        // Dropping the punctuation must not reduce the query to nothing. The words that were around
        // it are what the search was ever about.
        withIndexed("palette.txt", "a warm dark brown surface and border colour",
                    engine -> assertThat(engine.searchRelevantSnippets(
                            "add a dark/orange/brown palette with a warm surface"))
                            .as("the words either side of the slashes still match")
                            .isNotEmpty());
    }

    @Test
    public void apastedSpecificationIsSearchedRatherThanRefused() throws Exception {
        // The second failure escaping alone would have left in place. Lucene refuses a query of
        // more than IndexSearcher.getMaxClauseCount() terms, which is 1,024 by default, and a
        // request long enough to reach it is an ordinary thing for somebody to paste.
        StringBuilder request = new StringBuilder("rewrite the warm palette so that ");
        for (int i = 0; i < 4000; i++) {
            request.append("requirement").append(i).append(' ');
        }

        withIndexed("palette.txt", "a warm dark brown surface and border colour",
                    engine -> assertThat(engine.searchRelevantSnippets(request.toString()))
                            .as("the words at the front of a very long request still match")
                            .isNotEmpty());
    }

    @Test
    public void aWordRepeatedDoesNotSpendTheRoomTwice() throws Exception {
        // Repeats are dropped, so a request that says the same word forty times still leaves room
        // for the words that follow it.
        StringBuilder request = new StringBuilder();
        request.append("colour ".repeat(400));
        request.append("border");

        withIndexed("palette.txt", "a warm dark brown surface and border colour",
                    engine -> assertThat(engine.searchRelevantSnippets(request.toString()))
                            .isNotEmpty());
    }

    @Test
    public void anEmptyQueryFindsNothingRatherThanFailing() throws Exception {
        withIndexed("notes.txt", "nothing in particular",
                    engine -> {
                        assertThat(engine.searchRelevantSnippets("")).isEmpty();
                        assertThat(engine.searchRelevantSnippets("   ")).isEmpty();
                        assertThat(engine.searchRelevantSnippets(null)).isEmpty();
                    });
    }
}
