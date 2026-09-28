package com.eonmux.cadetcoder.agents;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** How a worker's assignment is composed and named. */
public class WorkerTaskTest {

    @Test
    public void theSharedBriefingLeadsAndTheTaskTrails() {
        WorkerTask task = new WorkerTask(1, "review retries", "You are reviewing the net package.");

        // The briefing is byte-identical across workers, so it is the part a provider can serve from
        // a cached prefix. Putting the differing half last is what makes that possible -- the same
        // append-only discipline the main loop's prompts follow.
        assertThat(task.prompt()).startsWith("You are reviewing the net package.");
        assertThat(task.prompt()).endsWith("review retries");
    }

    @Test
    public void twoWorkersInARunSharePrefixesUpToTheirOwnTask() {
        String briefing = "Reviewing the same diff.";
        String a = new WorkerTask(1, "check correctness", briefing).prompt();
        String b = new WorkerTask(2, "check performance", briefing).prompt();

        int common = 0;
        while (common < Math.min(a.length(), b.length()) && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        assertThat(common).isGreaterThanOrEqualTo(briefing.length());
    }

    @Test
    public void aTaskWithNoBriefingIsJustTheTask() {
        assertThat(new WorkerTask(1, "do the thing", "").prompt()).isEqualTo("do the thing");
        assertThat(new WorkerTask(1, "do the thing", null).prompt()).isEqualTo("do the thing");
    }

    @Test
    public void aLongTaskIsLabelledWhole() {
        String long_ = "review every call site in the net package for retry classification errors "
                       + "and report the ones that would end a run";
        WorkerTask task = new WorkerTask(1, long_, "");

        assertThat(task.label()).isEqualTo(long_);
        assertThat(task.prompt()).isEqualTo(long_);
    }

    @Test
    public void aLabelIsASingleLineEvenWhenTheTaskIsNot() {
        // The label titles a section and shows in the status bar; a newline would break both.
        WorkerTask task = new WorkerTask(1, "first line\nsecond line", "");

        assertThat(task.label()).doesNotContain("\n").isEqualTo("first line second line");
    }

    @Test
    public void workersAreNamedByTheirPosition() {
        assertThat(new WorkerTask(2, "t", "").name()).isEqualTo("Worker 2");
    }

    @Test
    public void aWorkerWithoutATaskIsRejectedAtTheBoundary() {
        assertThat(catchThrowable(() -> new WorkerTask(1, "", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new WorkerTask(1, "   ", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new WorkerTask(0, "t", "b")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
