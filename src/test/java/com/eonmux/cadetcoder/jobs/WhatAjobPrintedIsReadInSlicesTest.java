package com.eonmux.cadetcoder.jobs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a job's output without reading it all again every time.
 *
 * <h2>Why a cursor and not a copy</h2>
 *
 * <p>"What has this printed since I last looked" is the question asked on every check-in. Answered
 * with the whole transcript each time, a build watched over ten steps would spend the context
 * window ten times on the same lines, and the tenth answer would be mostly output the model had
 * already read and reasoned about.</p>
 *
 * <h2>Why what was dropped is counted</h2>
 *
 * <p>The buffer is bounded and a job can print forever, so at some point the oldest lines go. A
 * reader handed the remaining lines with no sign that anything is missing would draw conclusions
 * from a transcript that begins in the middle -- "the build printed no errors" is false when the
 * errors scrolled off.</p>
 *
 * <h2>Why a credential is taken out here and nowhere else</h2>
 *
 * <p>Everything ever shown of a job's output is read back through this buffer: {@code job output},
 * the notice a finished job leaves, and the transcript a later prompt carries. A line that prints a
 * token -- a deploy script echoing its environment is the ordinary case -- would otherwise be sent
 * to the model and written into the session file. Taking it out as the line arrives is what makes
 * every reader safe at once.</p>
 */
public class WhatAjobPrintedIsReadInSlicesTest {

    @Test
    void areadStartsWhereTheLastOneStopped() {
        JobOutput output = new JobOutput();
        output.append("one");
        output.append("two");

        assertThat(output.since(0, 0)).containsExactly("one", "two");
        assertThat(output.pendingFrom(2)).isZero();

        output.append("three");

        assertThat(output.since(2, 0)).containsExactly("three");
        assertThat(output.pendingFrom(2)).isEqualTo(1);
    }

    @Test
    void alimitKeepsTheNewestLinesRatherThanTheOldest() {
        // The end of a build is the part anybody reads.
        JobOutput output = new JobOutput();
        for (int i = 1; i <= 10; i++) {
            output.append("line " + i);
        }

        assertThat(output.since(0, 3)).containsExactly("line 8", "line 9", "line 10");
    }

    @Test
    void theOldestLinesGoWhenThereIsNoRoomLeft() {
        JobOutput output = new JobOutput();
        String    fat    = "x".repeat(1000);
        for (int i = 0; i < (JobOutput.MAX_CHARS / 1000) + 50; i++) {
            output.append(fat);
        }

        assertThat(output.oldestKept()).isGreaterThan(0);
        assertThat(output.since(0, 0).size() * 1001L).isLessThanOrEqualTo(JobOutput.MAX_CHARS + 1001);
    }

    @Test
    void areaderWhoFellBehindIsToldHowMuchItMissed() {
        JobOutput output = new JobOutput();
        String    fat    = "y".repeat(1000);
        for (int i = 0; i < (JobOutput.MAX_CHARS / 1000) + 10; i++) {
            output.append(fat);
        }

        long dropped = output.oldestKept();

        assertThat(dropped).isGreaterThan(0);
        assertThat(output.missedBefore(0)).isEqualTo(dropped);
        // A reader already past the drop has missed nothing.
        assertThat(output.missedBefore(output.produced())).isZero();
    }

    @Test
    void areadFromBeforeTheOldestKeptLineStartsAtTheOldestKeptLine() {
        JobOutput output = new JobOutput();
        String    fat    = "z".repeat(1000);
        for (int i = 0; i < (JobOutput.MAX_CHARS / 1000) + 5; i++) {
            output.append(fat);
        }

        List<String> everything = output.since(0, 0);

        assertThat(everything).isNotEmpty();
        assertThat(everything.size()).isEqualTo((int) (output.produced() - output.oldestKept()));
    }

    @Test
    void acredentialIsTakenOutAsTheLineArrives() {
        JobOutput output = new JobOutput();
        output.append("exporting AWS_SECRET_ACCESS_KEY=AKIAQWERTYUIOPASDFGH for the deploy");

        assertThat(output.since(0, 0)).hasSize(1);
        assertThat(output.since(0, 0).get(0))
                .as("what a job printed is read back by the model and written to the session file")
                .doesNotContain("AKIAQWERTYUIOPASDFGH")
                .contains("for the deploy");
    }

    @Test
    void ajobThatHasPrintedNothingHasNothingToRead() {
        JobOutput output = new JobOutput();

        assertThat(output.since(0, 0)).isEmpty();
        assertThat(output.produced()).isZero();
        assertThat(output.pendingFrom(0)).isZero();
        assertThat(output.missedBefore(0)).isZero();
    }
}
