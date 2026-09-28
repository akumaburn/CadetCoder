package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.commands.ShellTranscript.Kind;
import com.eonmux.cadetcoder.commands.ShellTranscript.Segment;
import com.eonmux.cadetcoder.commands.ShellTranscript.Snapshot;
import com.eonmux.cadetcoder.commands.ShellTranscript.Status;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

public class ShellTranscriptTest {

    private static List<String> linesOf(Snapshot s) {
        return s.lines();
    }

    @Test
    public void commandSegmentCollectsItsOutput() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "chat hello");
        t.append("line one\nline two\n");

        List<Snapshot> snap = t.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).kind()).isEqualTo(Kind.COMMAND);
        assertThat(snap.get(0).title()).isEqualTo("chat hello");
        assertThat(linesOf(snap.get(0))).containsExactly("line one", "line two");
    }

    @Test
    public void aSubheaderInProgramOutputOpensASectionThatGoesOnWithTheRun() {
        // A step's output is program output and holds the tool's own sub-headers. Each still opens
        // a section, and the section begins with an opening marker so its lines stay unrendered.
        String open  = com.eonmux.cadetcoder.ui.ProgramOutput.OPEN;
        String close = com.eonmux.cadetcoder.ui.ProgramOutput.CLOSE;
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "agent build a thing");
        t.append(open + "\n- before\n-- Step 1 of 3 --\n- after\n" + close + "\n");

        List<Snapshot> snap = t.snapshot();
        assertThat(snap).hasSize(2);
        assertThat(linesOf(snap.get(0))).containsExactly(open, "- before");
        assertThat(snap.get(1).title()).isEqualTo("Step 1 of 3");
        assertThat(linesOf(snap.get(1))).containsExactly(open, "-- Step 1 of 3 --", "- after", close);
    }

    @Test
    public void subheaderInsideCommandOpensASection() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "agent build a thing");
        t.append("preamble\n");
        t.append("-- Step 1 of 3 --\n");
        t.append("did the first thing\n");
        t.append("-- Step 2 of 3 --\n");
        t.append("did the second thing\n");

        List<Snapshot> snap = t.snapshot();
        // command (preamble) + 2 sections
        assertThat(snap).hasSize(3);
        assertThat(snap.get(0).kind()).isEqualTo(Kind.COMMAND);
        assertThat(linesOf(snap.get(0))).containsExactly("preamble");

        assertThat(snap.get(1).kind()).isEqualTo(Kind.SECTION);
        assertThat(snap.get(1).depth()).isEqualTo(1);
        assertThat(snap.get(1).title()).isEqualTo("Step 1 of 3");
        assertThat(linesOf(snap.get(1))).containsExactly("-- Step 1 of 3 --", "did the first thing");

        assertThat(snap.get(2).title()).isEqualTo("Step 2 of 3");
        assertThat(linesOf(snap.get(2))).containsExactly("-- Step 2 of 3 --", "did the second thing");
    }

    @Test
    public void subheaderInSystemSegmentDoesNotOpenSection() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.SYSTEM, "welcome");
        t.append("-- not a step --\n");
        List<Snapshot> snap = t.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).kind()).isEqualTo(Kind.SYSTEM);
        assertThat(linesOf(snap.get(0))).containsExactly("-- not a step --");
    }

    @Test
    public void partialLineIsVisibleThenCommittedOnNewline() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "read x");
        t.append("Enter value: ");
        List<Snapshot> snap = t.snapshot();
        assertThat(linesOf(snap.get(0))).containsExactly("Enter value: ");

        t.append("done\n");
        snap = t.snapshot();
        assertThat(linesOf(snap.get(0))).containsExactly("Enter value: done");
    }

    @Test
    public void outputBeforeAnySegmentCreatesASystemSegment() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.append("orphan line\n");
        List<Snapshot> snap = t.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).kind()).isEqualTo(Kind.SYSTEM);
        assertThat(linesOf(snap.get(0))).containsExactly("orphan line");
    }

    @Test
    public void clearRemovesEverything() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "x");
        t.append("a\nb\n");
        t.clear();
        assertThat(t.snapshot()).isEmpty();
        assertThat(t.segmentCount()).isZero();
        assertThat(t.totalLines()).isZero();
    }

    @Test
    public void capDropsOldestButKeepsActiveSegment() {
        ShellTranscript t = new ShellTranscript(3, 1000);
        for (int i = 1; i <= 6; i++) {
            t.beginSegment(Kind.COMMAND, "cmd" + i);
            t.append("out" + i + "\n");
        }
        List<Snapshot> snap = t.snapshot();
        assertThat(snap).hasSize(3);
        assertThat(snap.get(snap.size() - 1).title()).isEqualTo("cmd6");
        // The most recent (active) segment is always retained.
        assertThat(linesOf(snap.get(snap.size() - 1))).containsExactly("out6");
    }

    @Test
    public void statusIsRecordedAndSurfacedInSnapshot() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        Segment seg = t.beginSegment(Kind.COMMAND, "build");
        seg.setStatus(Status.RUNNING);
        assertThat(t.snapshot().get(0).status()).isEqualTo(Status.RUNNING);
        seg.setStatus(Status.ERROR);
        assertThat(t.snapshot().get(0).status()).isEqualTo(Status.ERROR);
    }

    @Test
    public void snapshotIsImmutable() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "x");
        t.append("a\n");
        List<Snapshot> snap = t.snapshot();
        assertThat(catchThrowable(() -> snap.add(null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(catchThrowable(() -> snap.get(0).lines().add("nope")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void appendLineCommitsPendingFirst() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "x");
        t.append("partial");           // no newline
        t.appendLine("explicit line"); // should flush the partial first
        assertThat(linesOf(t.snapshot().get(0))).containsExactly("partial", "explicit line");
    }

    @Test
    public void aGlyphSubHeaderOpensASectionAndTitlesItWithoutTheMarker() {
        // The structural half of the marker redesign. A sub-header is the ONLY thing that opens a
        // nested, Tab-navigable section, so if the console started emitting a marker this class did
        // not strip, agent steps would silently stop being browsable -- no error, just a flat run.
        ShellTranscript transcript = new ShellTranscript(50, 500);
        transcript.beginSegment(Kind.COMMAND, "agent do something");
        transcript.append("▸ Thinking\n");
        transcript.append("working\n");

        List<Snapshot> snapshot = transcript.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(1).kind()).isEqualTo(Kind.SECTION);
        assertThat(snapshot.get(1).title()).isEqualTo("Thinking");
    }

    @Test
    public void theBracketedSubHeaderStillOpensASection() {
        // The ASCII fallback vocabulary has to keep working: it is what a non-UTF-8 terminal emits.
        ShellTranscript transcript = new ShellTranscript(50, 500);
        transcript.beginSegment(Kind.COMMAND, "agent do something");
        transcript.append("-- Thinking --\n");

        List<Snapshot> snapshot = transcript.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(1).title()).isEqualTo("Thinking");
    }

    @Test
    public void anIterationMarkerIsStyledLikeAHeadingButIsNotSomewhereToLand() {
        // Tab moves between segments. An agent loop prints one iteration marker per turn, so while
        // these opened segments a long run buried every result worth returning to under a run of
        // "Iteration 7" stops. The marker still LOOKS like a heading; it just is not a stop.
        ShellTranscript t = new ShellTranscript(200, 200);
        t.beginSegment(ShellTranscript.Kind.COMMAND, "chat do a thing");
        int afterCommand = t.segmentIds().size();

        t.append(com.eonmux.cadetcoder.ui.OutputLineStyler.ITERATION_GLYPH + " Iteration 1\n");
        t.append("thinking out loud\n");
        t.append(com.eonmux.cadetcoder.ui.OutputLineStyler.ITERATION_GLYPH + " Iteration 2\n");

        assertThat(t.segmentIds()).hasSize(afterCommand);

        // A real sub-header still opens one, which is what keeps results navigable.
        t.append(com.eonmux.cadetcoder.ui.OutputLineStyler.SUBHEADER_GLYPH + " Worker 1  review\n");
        assertThat(t.segmentIds()).hasSize(afterCommand + 1);
    }

    @Test
    public void anIterationMarkerStillReadsAsAHeading() {
        assertThat(com.eonmux.cadetcoder.ui.OutputLineStyler.classify(
                com.eonmux.cadetcoder.ui.OutputLineStyler.ITERATION_GLYPH + " Iteration 3"))
                .isEqualTo(com.eonmux.cadetcoder.ui.OutputLineStyler.Kind.ITERATION);
        assertThat(com.eonmux.cadetcoder.ui.OutputLineStyler.classify(
                com.eonmux.cadetcoder.ui.OutputLineStyler.SUBHEADER_GLYPH + " Worker 1"))
                .isEqualTo(com.eonmux.cadetcoder.ui.OutputLineStyler.Kind.SUBHEADER);
    }

    /**
     * Hidden output belongs to the result that produced it, whatever it is made of.
     *
     * <p>{@code multiread} heads every file it reads with a sub-header. Inside a collapsed run each
     * of those opened a section of its own, which took the rest of the output out of the segment
     * holding the opening marker: the body was drawn in full on the live console while the marker
     * sat behind it with nothing left to hide.</p>
     */
    @Test
    public void asubheaderInsideHiddenOutputStaysInsideIt() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "chat check the tests");
        t.append(com.eonmux.cadetcoder.ui.OutputLineStyler.SUBHEADER_GLYPH
                 + " multiread A.java B.java\n");
        t.append(com.eonmux.cadetcoder.ui.CollapsedOutput.OPEN + "\n");
        t.append("  " + com.eonmux.cadetcoder.ui.OutputLineStyler.SUBHEADER_GLYPH + " [1/2] A.java\n");
        t.append("  package a;\n");
        t.append("  " + com.eonmux.cadetcoder.ui.OutputLineStyler.SUBHEADER_GLYPH + " [2/2] B.java\n");
        t.append("  package b;\n");
        t.append(com.eonmux.cadetcoder.ui.CollapsedOutput.CLOSE + "\n");

        List<Snapshot> snap = t.snapshot();

        assertThat(snap)
                .as("the step's own announcement opens one section; the files inside it open none")
                .hasSize(2);
        List<String> step = linesOf(snap.get(1));
        assertThat(com.eonmux.cadetcoder.ui.CollapsedOutput.visible(step))
                .containsExactly(com.eonmux.cadetcoder.ui.OutputLineStyler.SUBHEADER_GLYPH
                                 + " multiread A.java B.java");
        assertThat(com.eonmux.cadetcoder.ui.CollapsedOutput.expanded(step))
                .contains("  package a;", "  package b;");
    }

    @Test
    public void asubheaderAfterTheHiddenOutputOpensAsectionAgain() {
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "chat check the tests");
        t.append(com.eonmux.cadetcoder.ui.CollapsedOutput.OPEN + "\n");
        t.append("  " + com.eonmux.cadetcoder.ui.OutputLineStyler.SUBHEADER_GLYPH + " [1/1] A.java\n");
        t.append(com.eonmux.cadetcoder.ui.CollapsedOutput.CLOSE + "\n");
        t.append(com.eonmux.cadetcoder.ui.OutputLineStyler.SUBHEADER_GLYPH + " bash ls\n");

        assertThat(t.snapshot()).hasSize(2);
        assertThat(t.snapshot().get(1).title()).isEqualTo("bash ls");
    }

    @Test
    public void arunLeftOpenDoesNotSwallowTheNextCommandsSections() {
        // A closing marker can be lost with the lines a bounded buffer retires, and a command that
        // fails part way through prints no more. The next command starts clean either way.
        ShellTranscript t = new ShellTranscript(100, 1000);
        t.beginSegment(Kind.COMMAND, "chat one");
        t.append(com.eonmux.cadetcoder.ui.CollapsedOutput.OPEN + "\n");
        t.append("  output that never closed\n");

        t.beginSegment(Kind.COMMAND, "chat two");
        t.append(com.eonmux.cadetcoder.ui.OutputLineStyler.SUBHEADER_GLYPH + " bash ls\n");

        assertThat(t.snapshot()).hasSize(3);
        assertThat(t.snapshot().get(2).kind()).isEqualTo(Kind.SECTION);
        assertThat(t.snapshot().get(2).title()).isEqualTo("bash ls");
    }
}
