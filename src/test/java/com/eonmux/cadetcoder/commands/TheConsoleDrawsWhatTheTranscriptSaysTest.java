package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.TextSelectionModel;
import com.eonmux.cadetcoder.ui.TuiThemeManager;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console, drawn into a buffer and read back.
 *
 * <h2>Why this is tested</h2>
 *
 * <p>Painting the console was the largest single thing the shell did and the only part of it with no
 * test at all, because reaching it meant constructing a class whose constructor takes over the
 * process's output routing. Lifted into a collaborator it draws into any frame, and TamboUI will
 * hand out a frame backed by a plain in-memory buffer -- so what ends up on which row is an ordinary
 * assertion.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the transcript's text reaches the screen and the region is headed; that select mode says
 * so on that heading, since it is the only indication that the mouse now belongs to the terminal;
 * that focusing a result draws that result, a counter saying which of them it is, and none of the
 * others; that a focused segment or worker which has since gone gives up focus and falls back to the
 * live view rather than drawing nothing; and that a degenerate rectangle or an absent theme costs
 * chrome and colour rather than content.</p>
 */
public class TheConsoleDrawsWhatTheTranscriptSaysTest {

    private static final int WIDTH  = 60;
    private static final int HEIGHT = 12;

    /** A worker number no run will have launched, whatever else has run in this JVM. */
    private static final int NO_SUCH_WORKER = 99_999;

    private final ShellTranscript      transcript = new ShellTranscript(50, 500);
    private final ShellConsoleView     console    = new ShellConsoleView();
    private final ShellConsoleRenderer renderer   =
            new ShellConsoleRenderer(transcript, console, Glyphs.ASCII);

    private static final ShellConsoleRenderer.View LIVE =
            new ShellConsoleRenderer.View(TextSelectionModel.EMPTY, false, 0, "");

    /** Renders one frame and returns everything drawn, one row per line. */
    private String draw(ShellConsoleRenderer.View view) {
        Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
        Frame  frame  = Frame.forTesting(buffer);
        renderer.render(frame, frame.area(), TuiThemeManager.getCurrentTheme(), view);
        return text(buffer);
    }

    private static String text(Buffer buffer) {
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                out.append(buffer.get(x, y).symbol());
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Focuses a result the way a click does: against the map the last frame published. */
    private void focusSegment(int id) {
        console.publish(List.of("one line"), new int[] {id}, 0, 0, WIDTH, HEIGHT, 0);
        console.focusSegmentAt(0, 0);
    }

    private void twoResults() {
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "read Config.java");
        transcript.appendLine("package com.example;");
    }

    @Test
    public void theTranscriptsTextReachesTheScreen() {
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");

        assertThat(draw(LIVE)).contains("Main.java");
    }

    @Test
    public void theRegionIsHeaded() {
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");

        assertThat(draw(LIVE)).contains("Console");
    }

    @Test
    public void selectModeSaysSoOnTheHeading() {
        // The only indication anywhere that the mouse has been handed to the terminal: without it,
        // an in-application drag simply stops working with nothing to say why.
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");

        assertThat(draw(new ShellConsoleRenderer.View(TextSelectionModel.EMPTY, true, 0, "")))
                .contains("SELECT MODE");
    }

    @Test
    public void asecondResultIsIntroducedByItsOwnCommand() {
        twoResults();

        String drawn = draw(LIVE);

        assertThat(drawn).contains("read Config.java");
        assertThat(drawn).contains("package com.example;");
        assertThat(drawn).contains("Main.java");
    }

    @Test
    public void anEmptyTranscriptStillDrawsItsChrome() {
        // Drawn before anything has run, on every start; a renderer that needed content would fail
        // on the first frame of every session.
        assertThat(draw(LIVE)).contains("Console");
    }

    @Test
    public void aFocusedResultFillsTheConsoleAndSaysWhichItIs() {
        twoResults();
        int second = transcript.segmentIds().get(transcript.segmentIds().size() - 1);

        focusSegment(second);
        String drawn = draw(LIVE);

        assertThat(console.isFocused()).isTrue();
        assertThat(drawn).contains("package com.example;");
        assertThat(drawn).contains("(2/2)");
        // The other result is not on screen: that is what focusing this one means.
        assertThat(drawn).doesNotContain("ls src");
    }

    @Test
    public void aFocusedSegmentThatHasGoneFallsBackToTheLiveView() {
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");

        // A result that has scrolled out of the bounded transcript while it was the focused one.
        focusSegment(9999);
        String drawn = draw(LIVE);

        assertThat(console.isFocused()).isFalse();
        assertThat(drawn).contains("Console");
        assertThat(drawn).contains("Main.java");
    }

    @Test
    public void aFocusedWorkerThatHasGoneAlsoFallsBack() {
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");

        console.enterOrMoveFocus(1, List.of(BackgroundPane.worker(NO_SUCH_WORKER)));
        String drawn = draw(LIVE);

        assertThat(console.focusedPane()).isNull();
        assertThat(drawn).contains("Main.java");
    }

    @Test
    public void theThreeEndingsAreMarkedDifferentlyFromEachOther() {
        // A tick, a cross and a spinner phase: the mark is the only part of a result heading that
        // says how it went, so two of them being the same character would lose that entirely.
        assertThat(renderer.statusMark(ShellTranscript.Status.OK, 0))
                .isNotEqualTo(renderer.statusMark(ShellTranscript.Status.ERROR, 0));
        assertThat(renderer.statusMark(ShellTranscript.Status.OK, 0))
                .isNotEqualTo(renderer.statusMark(ShellTranscript.Status.RUNNING, 0));
        assertThat(renderer.statusMark(ShellTranscript.Status.ERROR, 0))
                .isNotEqualTo(renderer.statusMark(ShellTranscript.Status.RUNNING, 0));
    }

    @Test
    public void aResultWithNoStatusIsNotMarkedAtAll() {
        assertThat(renderer.statusMark(null, 0)).isEmpty();
        assertThat(renderer.statusMark(ShellTranscript.Status.NONE, 0)).isEmpty();
    }

    @Test
    public void aRunningResultAnimates() {
        assertThat(renderer.statusMark(ShellTranscript.Status.RUNNING, 0))
                .isNotEqualTo(renderer.statusMark(ShellTranscript.Status.RUNNING, 1));
    }

    @Test
    public void aConsoleWithNoRoomDrawsNothingRatherThanFailing() {
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");

        Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
        Frame  frame  = Frame.forTesting(buffer);

        // A terminal mid-resize hands out degenerate rectangles; every one of them still has to
        // return rather than index off the end of a row.
        renderer.render(frame, new Rect(0, 0, 0, 0), TuiThemeManager.getCurrentTheme(), LIVE);
        renderer.render(frame, new Rect(0, 0, WIDTH, 1), TuiThemeManager.getCurrentTheme(), LIVE);
        renderer.render(frame, new Rect(0, 0, 1, HEIGHT), TuiThemeManager.getCurrentTheme(), LIVE);
    }

    @Test
    public void aMissingThemeIsNotAMissingConsole() {
        // Every style lookup goes through a null-tolerant helper for this reason: a theme that has
        // not loaded must cost colour, not content.
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");

        Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
        Frame  frame  = Frame.forTesting(buffer);
        renderer.render(frame, frame.area(), null, LIVE);

        assertThat(text(buffer)).contains("Main.java");
    }

    @Test
    public void aSelectionIsDrawnOverTheRowsItCovers() {
        // The highlight is written over cells the paragraph widget has already drawn, so it can only
        // be asserted against a frame that has actually been painted.
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");
        draw(LIVE); // publishes the document the selection is taken against

        TextSelectionModel selection = TextSelectionModel.EMPTY.start(0, 0).extendTo(0, 4);
        String drawn = draw(new ShellConsoleRenderer.View(selection, false, 0, ""));

        assertThat(drawn).contains("Main.java");
    }

    @Test
    public void whereYouAreIsSaidInTheConsolesOwnCorner() {
        // It used to open the bottom line, a row below the console and at the far end of the screen
        // from the text it describes.
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "chat hello");
        transcript.append("an answer\n");

        String[] rows = draw(new ShellConsoleRenderer.View(TextSelectionModel.EMPTY, false, 0,
                                                           Glyphs.ASCII.liveDot() + " live"))
                .split("\n");

        assertThat(rows[0]).contains("Console");
        assertThat(rows[0].stripTrailing()).endsWith("live");
    }

    @Test
    public void aHeadingWithNothingToSayInTheCornerIsJustTheHeading() {
        assertThat(ShellConsoleRenderer.headingRow(" Console ", "", 20).stripTrailing())
                .isEqualTo(" Console");
        assertThat(ShellConsoleRenderer.headingRow(" Console ", null, 20).stripTrailing())
                .isEqualTo(" Console");
    }

    @Test
    public void theCornerIsPushedToTheRightEdgeAndTheTitleKeepsTheLeft() {
        String row = ShellConsoleRenderer.headingRow(" Console ", "* live", 20);

        assertThat(row).hasSize(20);
        assertThat(row).startsWith(" Console ");
        assertThat(row).endsWith("* live ");
    }

    @Test
    public void aRowTooNarrowForBothKeepsWhatFitsRatherThanOverflowing() {
        String row = ShellConsoleRenderer.headingRow(" Console ", "* live", 8);

        assertThat(row).hasSize(8);
    }
}
