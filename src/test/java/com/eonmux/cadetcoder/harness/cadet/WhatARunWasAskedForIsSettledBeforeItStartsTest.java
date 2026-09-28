package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Everything a run needs to know about itself, checked once, before anything has been spent.
 *
 * <p>A run reaches a backend within a second of starting and writes to the project within a few more.
 * A request with no task in it, or one aimed at a directory that is not there, is a mistake that
 * costs real money and real edits if it is only noticed part way through -- and the agent is the one
 * left holding it, as an unexplained failure it will try to work around. These tests make each of
 * those a refusal at the point the run is described rather than a surprise once it is under way.</p>
 */
class WhatARunWasAskedForIsSettledBeforeItStartsTest {

    /** A step budget somebody might reasonably type. */
    private static final BudgetLimits TWENTY_TURNS =
            new BudgetLimits(BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED,
                             20);

    @Test
    void aRunAskedForNothingIsRefused(@TempDir Path project) {
        assertThatThrownBy(() -> RunRequest.of("   ", project))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
    }

    @Test
    void aRunAimedAtSomewhereThatIsNotThereIsRefused(@TempDir Path project) {
        Path missing = project.resolve("nowhere");

        assertThatThrownBy(() -> RunRequest.of("tidy the imports", missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nowhere");
    }

    @Test
    void theTaskIsKeptAsItWasGivenWithoutTheSpaceAroundIt(@TempDir Path project) {
        assertThat(RunRequest.of("  make the tests pass  ", project).task())
                .isEqualTo("make the tests pass");
    }

    @Test
    void aRunNobodyBoundedIsBoundedByNothing(@TempDir Path project) {
        assertThat(RunRequest.of("tidy the imports", project).limits())
                .isEqualTo(BudgetLimits.unlimited());
    }

    @Test
    void mostTasksHaveNoWayToCheckThemAndAreStillRunnable(@TempDir Path project) {
        assertThat(RunRequest.of("explain the parser", project).goalCheck()).isNull();
    }

    @Test
    void aCheckThatIsOnlySpaceIsTheSameAsHavingNone(@TempDir Path project) {
        assertThat(RunRequest.of("explain the parser", project).checkedBy("   ").goalCheck())
                .isNull();
    }

    @Test
    void whatTheRunIsCheckedAgainstIsKeptAsItWasTyped(@TempDir Path project) {
        RunRequest asked = RunRequest.of("make the tests pass", project)
                                     .checkedBy("  bash mvn -o -B test  ");

        assertThat(asked.goalCheck()).isEqualTo("bash mvn -o -B test");
    }

    @Test
    void aBudgetGivenLaterReplacesTheOneNobodyAskedFor(@TempDir Path project) {
        RunRequest asked = RunRequest.of("tidy the imports", project).spending(TWENTY_TURNS);

        assertThat(asked.limits()).isEqualTo(TWENTY_TURNS);
        assertThat(asked.task()).isEqualTo("tidy the imports");
    }

    @Test
    void narrowingARunLeavesTheRequestItWasNarrowedFromAlone(@TempDir Path project) {
        RunRequest asked = RunRequest.of("tidy the imports", project);

        asked.spending(TWENTY_TURNS).checkedBy("bash mvn -o -B test");

        assertThat(asked.limits()).isEqualTo(BudgetLimits.unlimited());
        assertThat(asked.goalCheck()).isNull();
    }

    @Test
    void aRunWithNoAllowanceAtAllIsRefusedRatherThanTreatedAsUnlimited(@TempDir Path project) {
        assertThatThrownBy(() -> new RunRequest("tidy the imports", null, project, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
