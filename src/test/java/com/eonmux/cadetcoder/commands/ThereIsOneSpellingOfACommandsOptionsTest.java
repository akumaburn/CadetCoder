package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A command's options are described in one place, and every route to them shows that description.
 *
 * <p><b>The defect</b>: {@code ls} parsed with picocli and, on {@code --help} or an unknown option,
 * printed picocli's generated table -- {@code Usage: ls [-adhlrRSt] [--max-depth=<maxDepth>]...} --
 * while {@code cadet help ls} printed the hand-written text in {@code getUsage()}. Two spellings of
 * one command's options, differing in shape, wording and ordering, reachable by three routes, and
 * only one of them kept in step with the command. Every other command in the tool renders
 * {@code getUsage()} on all three routes; {@code ls} was the one that did not.</p>
 */
public class ThereIsOneSpellingOfACommandsOptionsTest {

    /** picocli's own usage renderer. Nothing user-facing may call it. */
    private static final String PICOCLI_RENDERER = ".usage(";

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void noCommandPrintsPicocliSgeneratedUsageTable() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path commands = Paths.get("src/main/java/com/eonmux/cadetcoder/commands");
        try (Stream<Path> tree = Files.walk(commands)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains(PICOCLI_RENDERER)) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }

        assertThat(offenders)
                .as("these render picocli's table instead of their own getUsage(): %s", offenders)
                .isEmpty();
    }

    @Test
    public void anUnknownOptionShowsTheSameOptionListThatHelpShows() {
        String output = runLs("--zzz-bogus");

        assertThat(output).contains("Unknown option");
        assertThat(output)
                .as("the hand-written option list, as `cadet help ls` renders it")
                .contains("-R, --recursive")
                .contains("path defaults to the current directory");
        assertThat(output)
                .as("picocli's generated synopsis must not appear")
                .doesNotContain("[-adhlrRSt]");
    }

    @Test
    public void askingForHelpShowsTheSameOptionListThatHelpShows() {
        String output = runLs("--help");

        assertThat(output)
                .contains("-R, --recursive")
                .contains("path defaults to the current directory");
        assertThat(output).doesNotContain("[-adhlrRSt]");
    }

    private String runLs(String... args) {
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager manager = mock(ConfigManager.class);
            when(manager.getConfig()).thenReturn(new Configuration());
            configMock.when(ConfigManager::getInstance).thenReturn(manager);

            new LSCommand().execute(args);
            return outputCapture.getAllOutput();
        }
    }
}
