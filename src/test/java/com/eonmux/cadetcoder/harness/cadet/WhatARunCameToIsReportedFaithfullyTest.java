package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.loop.RunResult;
import com.eonmux.cadetcoder.harness.loop.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An exit code is the only part of a run another program reads, so it has to mean what it says.
 *
 * <p>{@code cadet agent "..." && cadet commit} is the shape this ends up in, and everything that is
 * not finished work has to stop that line. The loop this replaced answered success to a run that had
 * exhausted its step budget and to one the operator had interrupted, so a chained command ran on top
 * of half-finished work with nothing to warn it. These tests fix each of the six endings to the
 * answer it deserves, and require the run's record to be named in what is printed -- a run whose
 * ledger nobody can find has established nothing anyone can use.</p>
 */
class WhatARunCameToIsReportedFaithfullyTest {

    private static RunOutcome ending(RunStatus status, Path project) {
        return new RunOutcome(new RunResult(status, 4, 2, 9, 0, "that is that"),
                              RunRecord.under(project));
    }

    @Test
    void workTheWorldItselfConfirmedIsSuccess(@TempDir Path project) {
        assertThat(ending(RunStatus.GOAL, project).exitCode()).isEqualTo(RunOutcome.FINISHED);
    }

    @Test
    void workTheAgentDeclaredFinishedIsSuccess(@TempDir Path project) {
        assertThat(ending(RunStatus.DONE, project).exitCode()).isEqualTo(RunOutcome.FINISHED);
    }

    @Test
    void aRunThatSpentItsAllowanceWithoutFinishingIsNotSuccess(@TempDir Path project) {
        assertThat(ending(RunStatus.BUDGET, project).exitCode()).isEqualTo(RunOutcome.UNFINISHED);
    }

    @Test
    void aRunTheAgentGaveUpOnIsNotSuccess(@TempDir Path project) {
        assertThat(ending(RunStatus.STUCK, project).exitCode()).isEqualTo(RunOutcome.UNFINISHED);
    }

    @Test
    void aRunTheWorldEndedIsNotSuccess(@TempDir Path project) {
        assertThat(ending(RunStatus.TERMINAL, project).exitCode()).isEqualTo(RunOutcome.UNFINISHED);
    }

    /**
     * A run somebody took back is neither a success nor a failure, and is told apart from both.
     *
     * <p>Folded into {@link RunOutcome#UNFINISHED} it read identically to an agent that gave up and
     * to an episode the world ended, so a script could not tell "you stopped this" from "this could
     * not be done" -- and the two call for opposite next moves.</p>
     */
    @Test
    void aRunSomebodyCalledOffIsToldApartFromOneThatFailed(@TempDir Path project) {
        assertThat(ending(RunStatus.STOPPED, project).exitCode())
                .isEqualTo(RunOutcome.CALLED_OFF)
                .isNotEqualTo(RunOutcome.FINISHED)
                .isNotEqualTo(RunOutcome.UNFINISHED);
    }

    @Test
    void everyWayARunCanEndHasAnAnswerHere(@TempDir Path project) {
        for (RunStatus status : RunStatus.values()) {
            if (status == RunStatus.RUNNING) {
                continue;
            }
            assertThat(ending(status, project).exitCode())
                    .describedAs("exit code for %s", status)
                    .isIn(RunOutcome.FINISHED, RunOutcome.UNFINISHED, RunOutcome.CALLED_OFF);
        }
    }

    /** The one interruption code, wherever the interruption was noticed. */
    @Test
    void whatAcalledOffRunReportsIsWhatEverythingElseReportsForTheSameEvent(@TempDir Path project) {
        assertThat(ending(RunStatus.STOPPED, project).exitCode())
                .isEqualTo(com.eonmux.cadetcoder.ExitCode.INTERRUPTED);
    }

    @Test
    void whatIsPrintedSaysHowItEndedAndWhereTheRecordIs(@TempDir Path project) {
        RunOutcome outcome = ending(RunStatus.DONE, project);

        assertThat(outcome.render()).contains("DONE")
                                    .contains("that is that")
                                    .contains(outcome.record().directory().toString());
    }

    @Test
    void anOutcomeWithNoResultIsRefusedRatherThanReportedAsSuccess(@TempDir Path project) {
        RunRecord record = RunRecord.under(project);

        assertThatThrownBy(() -> new RunOutcome(null, record))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOutcomeWithNoRecordIsRefusedBecauseItsEvidenceWouldBeUnfindable() {
        RunResult result = new RunResult(RunStatus.DONE, 1, 1, 1, 0, "");

        assertThatThrownBy(() -> new RunOutcome(result, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
