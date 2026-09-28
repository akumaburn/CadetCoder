package com.eonmux.cadetcoder.testsupport;

import com.eonmux.cadetcoder.ui.AnsiStripper;
import com.eonmux.cadetcoder.ui.OutputLineStyler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions about console output that check what a line <em>means</em> rather than how it is
 * decorated.
 *
 * <h2>Why</h2>
 *
 * <p>Many tests use the console marker as the only proof that a particular path ran -- that the
 * error branch was taken, that recovery succeeded, that a warning rather than a failure was
 * reported. Spelling the marker out in the assertion made that proof depend on the decoration:
 * every one of those tests broke when the console moved from {@code [OK]}/{@code [WARN]}/
 * {@code [ERR]} to single-column glyphs, and each would have had to be rewritten again on the next
 * change of mind.</p>
 *
 * <p>Worse, the marker is chosen at runtime. {@link com.eonmux.cadetcoder.ui.Glyphs#system()}
 * inspects the JVM encoding and the locale, so a literal {@code "✓"} in an assertion silently
 * depends on the machine the suite runs on and would fail under a non-UTF-8 {@code LANG}.</p>
 *
 * <p>These helpers assert through {@link OutputLineStyler#classify}, which is the same
 * classification the interactive shell uses to colour a line. A test written this way states the
 * real requirement ("this was reported as an error"), holds for both marker vocabularies, and
 * additionally pins the coupling the TUI depends on: if a formatter change made a line stop
 * classifying correctly, the shell would render it as plain body text and these assertions catch
 * it.</p>
 */
public final class ConsoleAssertions {

    private ConsoleAssertions() {
    }

    /** Asserts {@code output} carries a SUCCESS-classified line containing {@code message}. */
    public static void assertSuccess(String output, String message) {
        assertKind(output, message, OutputLineStyler.Kind.SUCCESS);
    }

    /** Asserts {@code output} carries a WARNING-classified line containing {@code message}. */
    public static void assertWarning(String output, String message) {
        assertKind(output, message, OutputLineStyler.Kind.WARNING);
    }

    /** Asserts {@code output} carries an ERROR-classified line containing {@code message}. */
    public static void assertError(String output, String message) {
        assertKind(output, message, OutputLineStyler.Kind.ERROR);
    }

    /**
     * Asserts {@code output} carries {@code message} as a notice: an undecorated line.
     *
     * <p>A notice has no marker, so it classifies as ordinary text. That is the requirement -- the
     * glyph in front of every informational line said only "this is information", which its own
     * text already said -- and asserting it here keeps a marker from creeping back on.</p>
     */
    public static void assertNotice(String output, String message) {
        assertKind(output, message, OutputLineStyler.Kind.NORMAL);
    }

    /** Asserts {@code output} carries a HEADER-classified line containing {@code message}. */
    public static void assertHeader(String output, String message) {
        assertKind(output, message, OutputLineStyler.Kind.HEADER);
    }

    /** Asserts {@code output} carries a SUBHEADER-classified line containing {@code message}. */
    public static void assertSubheader(String output, String message) {
        assertKind(output, message, OutputLineStyler.Kind.SUBHEADER);
    }

    /**
     * Asserts that no line of {@code output} both mentions {@code message} and classifies as
     * {@code kind} -- for the cases where the point of the test is that something was NOT reported
     * that way.
     */
    public static void assertNotKind(String output, String message, OutputLineStyler.Kind kind) {
        assertThat(lineFor(output, message, kind))
                .as("expected no %s line mentioning \"%s\" in:%n%s", kind, message, output)
                .isNull();
    }

    private static void assertKind(String output, String message, OutputLineStyler.Kind kind) {
        assertThat(output)
                .as("console output should mention \"%s\"", message)
                .contains(message);
        assertThat(lineFor(output, message, kind))
                .as("expected a %s line mentioning \"%s\" in:%n%s", kind, message, output)
                .isNotNull();
    }

    /**
     * The first line that both mentions {@code message} and classifies as {@code kind}.
     *
     * <p>A message can legitimately appear on more than one line -- a marked first line plus its
     * indented continuations -- so this looks for a matching line rather than assuming the first
     * mention is the marked one.</p>
     */
    private static String lineFor(String output, String message, OutputLineStyler.Kind kind) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            // Console output carries ANSI colour, and classify() keys off the FIRST character of the
            // line -- an escape sequence in front of the marker would hide it. The shell strips ANSI
            // before classifying for exactly this reason; do the same here.
            String bare = AnsiStripper.strip(line);
            if (bare != null && bare.contains(message) && OutputLineStyler.classify(bare) == kind) {
                return line;
            }
        }
        return null;
    }
}
