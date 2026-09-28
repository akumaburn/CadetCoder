package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.TextSelectionModel;
import com.eonmux.cadetcoder.ui.TuiThemeManager;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;

import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A background job's output, drawn into the console while the job is still running.
 *
 * <h2>The defect</h2>
 *
 * <p>A job's output could be read only by typing {@code job output <id>}, which needs a free
 * prompt. During the long run that started the job there is no free prompt, so watching the thing
 * you started meant interrupting the thing you started it from. A worker already had a pane of its
 * own; a job, whose output belongs to another process entirely, had nothing.</p>
 *
 * <h2>Why the job's own cursor is left alone</h2>
 *
 * <p>{@code BackgroundJob.readNew} advances one cursor that the whole process shares. Drawing a
 * frame through it would consume the lines {@code job output} and the model's own end-of-job notice
 * were going to show, so watching a job would change it. The pane reads
 * {@code job.output().since(0, 0)}, which does not.</p>
 */
public class AjobIsWatchedWhileItRunsTest {

    private static final int WIDTH  = 70;
    private static final int HEIGHT = 14;

    private final ShellTranscript      transcript = new ShellTranscript(50, 500);
    private final ShellConsoleView     console    = new ShellConsoleView();
    private final ShellConsoleRenderer renderer   =
            new ShellConsoleRenderer(transcript, console, Glyphs.ASCII);

    private static final ShellConsoleRenderer.View LIVE =
            new ShellConsoleRenderer.View(TextSelectionModel.EMPTY, false, 0, "");

    @After
    public void stopEveryJob() {
        JobRegistry.stopAll();
        JobRegistry.clear();
    }

    /** Renders one frame and returns everything drawn, one row per line. */
    private String draw() {
        Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
        Frame  frame  = Frame.forTesting(buffer);
        renderer.render(frame, frame.area(), TuiThemeManager.getCurrentTheme(), LIVE);
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                out.append(buffer.get(x, y).symbol());
            }
            out.append('\n');
        }
        return out.toString();
    }

    @Test
    public void ajobsOutputIsDrawnUnderAheadingThatNamesIt() throws Exception {
        BackgroundJob job = JobRegistry.start("sh -c 'echo alpha; echo beta'", null, null);
        job.awaitEnd(Duration.ofSeconds(20));

        console.focusOn(BackgroundPane.job(job.id()));
        String drawn = draw();

        assertThat(drawn).contains(job.id()).contains("alpha").contains("beta");
    }

    /**
     * Reading the pane must not take the lines away from everybody else.
     *
     * <p>The one cursor a job has belongs to {@code job output} and to the notice the model is
     * given when the job ends. A pane that consumed it would leave both with nothing to report.</p>
     */
    @Test
    public void drawingAjobLeavesItsUnreadOutputUnread() throws Exception {
        BackgroundJob job = JobRegistry.start("sh -c 'echo alpha; echo beta'", null, null);
        job.awaitEnd(Duration.ofSeconds(20));

        console.focusOn(BackgroundPane.job(job.id()));
        draw();
        draw();

        assertThat(job.pending())
                .as("nothing has read the job on its own cursor, so it is all still unread")
                .isEqualTo(2);
        assertThat(job.readNew(0).lines()).containsExactly("alpha", "beta");
    }

    @Test
    public void ajobThatHasPrintedNothingYetSaysSoRatherThanNothing() throws Exception {
        BackgroundJob job = JobRegistry.start("sleep 30", null, null);

        console.focusOn(BackgroundPane.job(job.id()));

        assertThat(draw()).contains("nothing printed yet");
    }

    /** A job removed from the registry leaves focus, so the console falls back to the live view. */
    @Test
    public void ajobThatIsGoneGivesUpTheConsole() {
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls src");
        transcript.appendLine("Main.java");
        console.focusOn(BackgroundPane.job("j-no-such-job"));

        String drawn = draw();

        assertThat(console.focusedPane()).isNull();
        assertThat(drawn).contains("Main.java");
    }

    @Test
    public void thelistOfEverythingRunningNamesBothKinds() {
        List<BackgroundPanes.Row> rows = List.of(
                new BackgroundPanes.Row(BackgroundPane.worker(1), BackgroundPanes.Liveness.DONE,
                                        "worker 1", "done", "parser tests"),
                new BackgroundPanes.Row(BackgroundPane.job("j1"), BackgroundPanes.Liveness.RUNNING,
                                        "j1", "running  2m14s", "./gradlew run"));

        List<String> lines = renderer.overviewLines(rows, 1);

        assertThat(lines.get(0)).isEqualTo("Workers");
        assertThat(lines.get(1)).contains("worker 1").contains("done").contains("parser tests");
        assertThat(lines).contains("Jobs");
        assertThat(lines.get(lines.size() - 1))
                .contains("j1").contains("running").contains("./gradlew run");
    }

    /** The picked-out line is marked, and only that one. */
    @Test
    public void thepickedLineIsTheOnlyOneMarked() {
        List<BackgroundPanes.Row> rows = List.of(
                new BackgroundPanes.Row(BackgroundPane.job("j1"), BackgroundPanes.Liveness.RUNNING,
                                        "j1", "running", "one"),
                new BackgroundPanes.Row(BackgroundPane.job("j2"), BackgroundPanes.Liveness.RUNNING,
                                        "j2", "running", "two"));

        List<String> lines = renderer.overviewLines(rows, 1);

        assertThat(lines.stream().filter(l -> l.startsWith(" " + Glyphs.ASCII.focusMark())))
                .hasSize(1);
        assertThat(lines.get(2)).startsWith(" " + Glyphs.ASCII.focusMark());
    }

    @Test
    public void alistWithNothingOnItSaysSoAndSaysWhatStartsSomething() {
        List<String> lines = renderer.overviewLines(List.of(), 0);

        assertThat(lines.get(0)).isEqualTo("Nothing is running.");
        assertThat(String.join(" ", lines)).contains("job start");
    }
}
