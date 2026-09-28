package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.CollapsedOutput;
import com.eonmux.cadetcoder.ui.Glyphs;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run opens without repeating what it was asked to do.
 *
 * <p><b>The defect</b>: the shell showed a submitted request three times, on three consecutive
 * lines. The transcript opened a result heading titled with the line,
 * {@code InteractiveShell.submitCommand} echoed the same line below it as {@code "> " + recorded},
 * and {@code ChatCommand} then printed {@code "Processing your request: " + userReq}. On the plain
 * command line it appeared twice: once as what the user typed at their own prompt, and once as the
 * header that repeated it straight back.</p>
 *
 * <p>One rendering is left. The heading carries the live status mark and the request as it was
 * submitted, wrapped rather than cut, and it is what {@code Tab} and a click focus on. The echo
 * below it was the second copy of the same words, so the heading consumes it: a copied transcript
 * still holds the request verbatim, once. The header was the third copy. Its only added words said
 * that a run had started, which the heading's own status mark already says.</p>
 *
 * <p>The shortened form of the request is still made, for the status bar, which has one line for
 * everything it says and cannot wrap.</p>
 */
public class ArequestIsShownOnceWhenARunOpensTest {

    private static final String REQUEST =
            "Please understand this project and then add a TLDR section to the readme";

    private static ShellTranscript.Snapshot result(String... lines) {
        return new ShellTranscript.Snapshot(1, ShellTranscript.Kind.COMMAND, 0, "add a TLDR",
                                            ShellTranscript.Status.RUNNING, List.of(lines));
    }

    @Test
    public void theStatusBarsLabelNamesTheRequestRatherThanRepeatingIt() {
        // The status bar has one line for the focus position, the mode and the label, so the label
        // is cut. The console heading is the place that carries the whole of it.
        String label = ShellWidgets.shortenCommand(REQUEST, "…");

        assertThat(label).startsWith("Please understand");
        assertThat(label.length()).isLessThanOrEqualTo(24);
        assertThat(label).doesNotContain("TLDR");
    }

    @Test
    public void aShortRequestStillReachesThatLabelWhole() {
        // Cut, not dropped. With several results stacked, the label is the only thing that says
        // which one is focused.
        assertThat(ShellWidgets.shortenCommand("add a TLDR section", "…"))
                .isEqualTo("add a TLDR section");
    }

    @Test
    public void theHeadingCarriesTheRequestAsItWasSubmitted() {
        List<String> heading = ShellConsoleRenderer.headingText("✓ ", "> " + REQUEST, 100);

        assertThat(heading).containsExactly("✓ > " + REQUEST);
    }

    @Test
    public void aRequestTooWideForTheScreenIsWrappedRatherThanCut() {
        // Cutting here would lose words that appear nowhere else, because the heading is now the
        // only rendering of the request.
        List<String> heading = ShellConsoleRenderer.headingText("✓ ", "> " + REQUEST, 30);

        assertThat(heading.size()).isGreaterThan(1);
        assertThat(heading).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(30));
        assertThat(heading.stream().map(String::strip).reduce("", (a, b) -> a + " " + b))
                .contains("TLDR section to the readme");
    }

    @Test
    public void aContinuationIsIndentedUnderTheRequestAndNotUnderTheMark() {
        // The status mark is a column. A wrapped line that started at column zero would put text
        // where the next result's mark goes.
        List<String> heading = ShellConsoleRenderer.headingText("✓ ", "> " + REQUEST, 30);

        assertThat(heading.get(1)).startsWith("  ");
    }

    @Test
    public void aWrappedHeadingKeepsEveryCharacterOfTheRequest() {
        // A continuation is indented under the request, so it has fewer columns for the request
        // than the first line has. Wrapping the whole heading to the full width and indenting the
        // fragments afterwards pushed the last characters of each one off the row, and the heading
        // is the only place the request is drawn.
        String       run     = "x".repeat(100);
        List<String> heading = ShellConsoleRenderer.headingText("done ", "> " + run, 30);

        assertThat(heading).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(30));
        assertThat(String.join("", heading).chars().filter(c -> c == 'x').count())
                .as("every character of the request is on some row")
                .isEqualTo(run.length());
    }

    @Test
    public void aResultWithNoRequestToShowGetsTheMarkAlone() {
        assertThat(ShellConsoleRenderer.headingText("✓ ", "", 40)).containsExactly("✓");
        assertThat(ShellConsoleRenderer.headingText("", "", 40)).isEmpty();
        assertThat(ShellConsoleRenderer.headingText("✓ ", "> anything", 0)).isEmpty();
    }

    @Test
    public void theEchoIsDroppedFromTheBodyTheHeadingSpeaksFor() {
        ShellTranscript.Snapshot seg = result("> " + REQUEST, "Working on it");

        assertThat(ShellConsoleRenderer.bodyLines(seg, false, true))
                .containsExactly("Working on it");
        assertThat(ShellConsoleRenderer.bodyLines(seg, false, false))
                .as("a view that draws no heading keeps the record")
                .containsExactly("> " + REQUEST, "Working on it");
    }

    @Test
    public void abodyThatNeverEchoedAnythingKeepsItsFirstLine() {
        ShellTranscript.Snapshot seg = result("Working on it");

        assertThat(ShellConsoleRenderer.bodyLines(seg, false, true)).containsExactly("Working on it");
    }

    @Test
    public void openingAresultShowsTheOutputTheLiveViewLeavesOut() {
        ShellTranscript.Snapshot seg = result("> " + REQUEST, "Working on it",
                                              CollapsedOutput.OPEN, "One.java", CollapsedOutput.CLOSE);

        assertThat(ShellConsoleRenderer.bodyLines(seg, false, true))
                .containsExactly("Working on it");
        assertThat(ShellConsoleRenderer.bodyLines(seg, true, false))
                .containsExactly("> " + REQUEST, "Working on it", "One.java");
    }

    @Test
    public void chatAddsNoRunTitleOfItsOwn() {
        // The executor's announcement is the other place a frame can come from. Chat returns null so
        // that it never adds one. That has to stay true now that the header is gone, or the third
        // copy returns under a different name.
        assertThat(new ChatCommand().getRunTitle(new String[] {"add a TLDR section"})).isNull();
    }

    @Test
    public void theBannerNamesTheSessionOnce() {
        // 'session new' asks the shell to redraw, and the redrawn banner carries "Session: <id>".
        // SessionCommand prints its own line only when no redraw happened, so the two must not both
        // name the session.
        List<String> banner =
                ShellWelcome.banner("v1.0", "/home/me/work", "session-2", Glyphs.ASCII, null);

        assertThat(banner.stream().filter(line -> line.contains("session-2")).count())
                .as("one line names the session, not two")
                .isEqualTo(1);
    }

    @Test
    public void theBannerSaysWhichSessionTheWorkGoesInto() {
        String text = String.join("\n",
                ShellWelcome.banner("v1.0", "/home/me/work", "session-2", Glyphs.ASCII, null));

        assertThat(text).contains("Session: session-2");
    }
}
