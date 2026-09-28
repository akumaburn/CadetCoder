package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.eonmux.cadetcoder.test.ProjectFolder;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A search pattern that will not compile is searched for as plain text.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code grep placeBuy(} was refused with "Unclosed group near index 9" and a tip about
 * escaping. The caller was looking for a method call and had written it the way it appears in the
 * file; the bracket was the point. One turn was spent being told the bracket was unclosed, and the
 * turn after that the search was given up rather than corrected. The same happened twice more in
 * the same run with a backslash.</p>
 *
 * <h2>Why a literal search is the safe answer and not a guess</h2>
 *
 * <p>A pattern that does not compile is, by definition, not a working regular expression, so no
 * search that used to succeed can change meaning. What is left is the text the caller actually
 * wrote, which is almost always what they meant to find. The substitution is announced, so a caller
 * who did mean an expression is told to escape it rather than left wondering.</p>
 */
public class ApatternThatIsNotAnExpressionIsStillASearchTest {

    @Rule
    public TemporaryFolder projectFolder = new ProjectFolder();

    private TestOutputCapture output;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
    }

    @Test
    public void anUnclosedBracketIsSearchedForAsPlainText() throws IOException {
        write("Venue.java", "    order.placeBuy(symbol, qty);\n    order.placeSell(symbol);\n");

        String seen = search("placeBuy(");

        assertThat(seen).contains("order.placeBuy(symbol, qty);");
        assertThat(seen)
                .as("a substitution nobody is told about is a search answering another question")
                .contains("searched for as plain text");
    }

    @Test
    public void atrailingBackslashIsSearchedForAsPlainText() throws IOException {
        write("Notes.txt", "a line ending in a backslash \\\n");

        String seen = search("backslash \\");

        assertThat(seen).contains("Notes.txt");
    }

    /** The substitution is for patterns that do not compile, and for nothing else. */
    @Test
    public void apatternThatIsAnExpressionIsStillAnExpression() throws IOException {
        write("Venue.java", "    order.placeBuy(symbol, qty);\n    order.placeSell(symbol);\n");

        String seen = search("place(Buy|Sell)");

        assertThat(seen).contains("placeBuy").contains("placeSell");
        assertThat(seen).doesNotContain("searched for as plain text");
    }

    private void write(String name, String contents) throws IOException {
        Path file = projectFolder.getRoot().toPath().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    /**
     * Runs a search over the temporary project.
     *
     * @param pattern the search pattern
     * @return everything the caller would see
     */
    private String search(String pattern) {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            Configuration config  = new Configuration();
            config.getUi().setColorEnabled(false);
            when(manager.getConfig()).thenReturn(config);
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            new GrepCommand().execute(
                    new String[] {pattern, "-p", projectFolder.getRoot().getAbsolutePath()});
        }
        return output.getAllOutput();
    }
}
