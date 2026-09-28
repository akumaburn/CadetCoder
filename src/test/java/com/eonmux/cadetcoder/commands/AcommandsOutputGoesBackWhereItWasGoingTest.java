package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Something the shell prints of its own accord does not take over a running command's output.
 *
 * <p><b>The defect</b>: opening a region was one-way. Pressing F3 while a command was running
 * opened a "Command History" region and left it as the sink, so every remaining line of the command
 * -- its results, its errors, its completion -- was filed under the history dump, for the rest of
 * that command. The region the user was watching simply stopped growing.</p>
 */
class AcommandsOutputGoesBackWhereItWasGoingTest {

    private static List<String> linesOf(ShellTranscript transcript, String title) {
        for (ShellTranscript.Snapshot segment : transcript.snapshot()) {
            if (title.equals(segment.title())) {
                return segment.lines();
            }
        }
        throw new AssertionError("no region titled " + title);
    }

    @Test
    void whatAcommandPrintsAfterAnInterjectionIsStillItsOwn() {
        ShellTranscript transcript = new ShellTranscript(64, 500);
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "grep needle");
        transcript.appendLine("first result");

        ShellTranscript.Where before = transcript.activeWhere();
        transcript.beginSegment(ShellTranscript.Kind.SYSTEM, "Command History");
        transcript.appendLine("1  grep needle");
        transcript.resumeWhere(before);

        transcript.appendLine("second result");

        assertThat(linesOf(transcript, "grep needle"))
                .containsExactly("first result", "second result");
        assertThat(linesOf(transcript, "Command History"))
                .containsExactly("1  grep needle");
    }

    @Test
    void asubSectionInsideAcommandIsPutBackToo() {
        ShellTranscript transcript = new ShellTranscript(64, 500);
        ShellTranscript.Segment command =
                transcript.beginSegment(ShellTranscript.Kind.COMMAND, "agent");
        // A sub-header line inside a command opens a section, which is then the sink while the
        // command remains the region -- the case where the two are not the same segment.
        transcript.appendLine("▸ Thinking");
        transcript.appendLine("working on it");

        ShellTranscript.Where before = transcript.activeWhere();
        transcript.beginSegment(ShellTranscript.Kind.SYSTEM, "Command History");
        transcript.appendLine("1  agent");
        transcript.resumeWhere(before);

        transcript.appendLine("still working");

        assertThat(linesOf(transcript, "Thinking"))
                .containsExactly("▸ Thinking", "working on it", "still working");
        assertThat(transcript.activeSectionTitleFor(command))
                .as("the command is the region again, and the section is the sink again")
                .isEqualTo("Thinking");
    }

    @Test
    void thereIsNothingToPutBackWhenNothingWasOpen() {
        ShellTranscript transcript = new ShellTranscript(64, 500);

        transcript.resumeWhere(transcript.activeWhere());
        transcript.resumeWhere(null);

        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "ls");
        transcript.appendLine("a file");

        assertThat(linesOf(transcript, "ls")).containsExactly("a file");
    }
}
