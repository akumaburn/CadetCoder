package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a TUI is on screen is a fact about the program, not about who is asking.
 *
 * <p>{@code ColorTheme} and {@code ThemedOutputFormatter} each carried a byte-identical private copy
 * of the answer, and both copies decided it the same way: ask {@code OutputRouter} whether it is
 * routing, and if it says no, walk the caller's stack looking for a class whose NAME CONTAINS
 * {@code "InteractiveShell"}, {@code "OutputRouter"} or {@code "dev.tamboui"}.</p>
 *
 * <p>A substring of a class name is not a fact about the terminal. Any class whose name happens to
 * contain one of those words -- and any frame of the router itself, which is on the stack for
 * console output too -- silently turned colour off for everything printed beneath it, while the
 * same call from a thread the shell had handed the work to (every interruptible command runs on
 * one, with no shell frame anywhere) turned it back on. The same message came out themed or plain
 * depending on the route it took to the screen.</p>
 *
 * <p>The router already knows: {@code isRouting()} is set when the shell takes over the terminal and
 * cleared when it gives it back, and four other classes ask it and nothing else.</p>
 */
public class OneTuiModeQuestionTest {

    private static final String ESC   = String.valueOf((char) 27);
    private static final String RED   = ESC + "[31m";
    private static final String RESET = ESC + "[0m";

    private final ColorTheme theme = new ColorTheme(
            "probe", "for this test",
            RED, RED, RED, RED, RED, RED, RED, RED, RED, RED, RED, RED);

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        System.clearProperty("cadet.tui.mode");
        OutputRouter.getInstance().stopRouting();
        outputCapture = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        outputCapture.stopCapture();
        System.clearProperty("cadet.tui.mode");
    }

    /**
     * A caller whose class name merely contains one of the watched words.
     *
     * <p>Nested so its binary name ends in {@code $InteractiveShellLookalike} -- it is not the
     * shell, has nothing to do with the terminal, and is exactly what a substring test cannot tell
     * apart from the real thing.</p>
     */
    private static final class InteractiveShellLookalike {

        static String colorize(ColorTheme theme, String text) {
            return theme.colorize(text, ColorTheme.ColorType.ERROR);
        }

        static void route(String text) {
            ThemedOutputFormatter.routeOutput(text);
        }
    }

    @Test
    public void colourSurvivesACallerWhoseNameMerelyLooksLikeTheShell() {
        String direct   = theme.colorize("boom", ColorTheme.ColorType.ERROR);
        String indirect = InteractiveShellLookalike.colorize(theme, "boom");

        assertThat(direct)
                .as("no TUI is running, so the console text keeps its colour")
                .contains(RED);
        assertThat(indirect)
                .as("who called must not change whether a TUI is on screen")
                .isEqualTo(direct);
    }

    @Test
    public void theOtherFormatterAnswersTheSameQuestionTheSameWay() {
        outputCapture.startCapture();
        InteractiveShellLookalike.route(RED + "boom" + RESET);
        outputCapture.restore();

        assertThat(outputCapture.getAllOutput())
                .as("no TUI is running, so there is no reason to strip the escapes")
                .contains(RED);
    }

    /** The rule still answers yes when the program says a TUI is on screen. */
    @Test
    public void anExplicitDeclarationOfTuiModeStillStripsColour() {
        System.setProperty("cadet.tui.mode", "true");

        assertThat(theme.colorize("boom", ColorTheme.ColorType.ERROR))
                .as("a TUI renders its own colours; ANSI in the text is corruption")
                .isEqualTo("boom");
    }

    @Test
    public void theRoutersAnswerIsWhatDecidesIt() {
        assertThat(TuiMode.isActive())
                .as("nothing is routing and nothing declared TUI mode")
                .isFalse();

        System.setProperty("cadet.tui.mode", "true");
        assertThat(TuiMode.isActive()).isTrue();
    }

    /** One rule, one place -- and not one decided by reading the call stack. */
    @Test
    public void nothingElseDecidesTuiModeForItself() throws IOException {
        try (Stream<Path> sources = Files.walk(Paths.get("src", "main", "java"))) {
            List<Path> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("TuiMode.java"))
                    .filter(OneTuiModeQuestionTest::restatesTheRule)
                    .collect(Collectors.toList());

            assertThat(offenders)
                    .as("TuiMode is where this is decided; a second copy is a second answer")
                    .isEmpty();
        }
    }

    private static boolean restatesTheRule(Path source) {
        try {
            String text = Files.readString(source);
            return text.contains("cadet.tui.mode")
                   || (text.contains("dev.tamboui") && text.contains("getStackTrace()"));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + source, e);
        }
    }
}
