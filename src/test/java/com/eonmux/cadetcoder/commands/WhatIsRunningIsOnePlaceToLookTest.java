package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;

import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list of every worker and every job, and the key that opens it.
 *
 * <h2>The defect</h2>
 *
 * <p>Workers and jobs are the same thing to the person watching: work that goes on while the
 * prompt stays free. They were reached by different routes and reported in different places -- a
 * worker only by {@code Tab}, a job only by typing {@code job list} -- so the question anybody
 * actually asks, "what is running", had no single answer. A job's output could not be watched at
 * all while a command was running, because {@code job output <id>} needs a free prompt, and asking
 * for one means interrupting the very thing you wanted to watch.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the list covers both kinds in one order, that it opens on its own and always at the
 * first line, that the line picked out moves and stops at each end rather than wrapping, and that
 * a job is described by the same words {@code job list} uses for it.</p>
 */
public class WhatIsRunningIsOnePlaceToLookTest {

    private final ShellConsoleView console = new ShellConsoleView();

    @After
    public void stopEveryJob() {
        JobRegistry.stopAll();
        JobRegistry.clear();
    }

    @Test
    public void thelistIsOpenedOnItsOwnAndNotByWalkingToIt() {
        assertThat(console.openOverview()).isTrue();

        assertThat(console.isFocused()).isTrue();
        assertThat(console.focusedPane()).isNotNull();
        assertThat(console.focusedPane().isOverview()).isTrue();
        assertThat(console.overviewRow()).isZero();
    }

    /**
     * Walking is for panes, and the list is not one.
     *
     * <p>Cycling from the list goes to the first pane rather than to whatever would come after a
     * list, so {@code Tab} always means the same thing: show me the next piece of work.</p>
     */
    @Test
    public void walkingOnFromTheListLandsOnTheFirstPane() {
        console.openOverview();

        console.enterOrMoveFocus(1, List.of(BackgroundPane.worker(1), BackgroundPane.job("j1")));

        assertThat(console.focusedPane()).isEqualTo(BackgroundPane.worker(1));
    }

    @Test
    public void thelistAlwaysOpensAtItsFirstLine() {
        console.openOverview();
        console.moveOverviewRow(1, 4);
        console.exitFocus();

        console.openOverview();

        assertThat(console.overviewRow()).isZero();
    }

    @Test
    public void thepickedLineMovesAndStopsAtEachEnd() {
        console.openOverview();

        assertThat(console.moveOverviewRow(-1, 3)).as("already at the top").isFalse();
        assertThat(console.moveOverviewRow(1, 3)).isTrue();
        assertThat(console.overviewRow()).isEqualTo(1);
        assertThat(console.moveOverviewRow(1, 3)).isTrue();
        assertThat(console.overviewRow()).isEqualTo(2);
        assertThat(console.moveOverviewRow(1, 3))
                .as("a selection that wrapped to the top would look like it had not moved")
                .isFalse();
        assertThat(console.overviewRow()).isEqualTo(2);
    }

    @Test
    public void anemptyListHasNoLineToPick() {
        console.openOverview();

        assertThat(console.moveOverviewRow(1, 0)).isFalse();
        assertThat(console.overviewRow()).isZero();
    }

    @Test
    public void arunningJobIsListedAsOnePaneAndOneRow() throws Exception {
        BackgroundJob job = JobRegistry.start("sleep 30", "a job to look at", null);

        assertThat(BackgroundPanes.all()).contains(BackgroundPane.job(job.id()));

        List<BackgroundPanes.Row> rows = BackgroundPanes.rows();
        assertThat(rows).hasSize(1);
        BackgroundPanes.Row row = rows.get(0);
        assertThat(row.pane()).isEqualTo(BackgroundPane.job(job.id()));
        assertThat(row.liveness()).isEqualTo(BackgroundPanes.Liveness.RUNNING);
        assertThat(row.name()).isEqualTo(job.id());
        assertThat(row.state()).startsWith("running");
        assertThat(row.detail()).contains("a job to look at").contains("sleep 30");
    }

    /**
     * A job that has ended is still worth listing, and is described as it ends.
     *
     * <p>The words are {@code job list}'s own. Two places that say how a job is doing, in two
     * vocabularies, is one place too many to keep in step.</p>
     */
    @Test
    public void ajobThatFailedSaysSoInTheWordsJobListUses() throws Exception {
        BackgroundJob job = JobRegistry.start("sh -c 'exit 3'", null, null);
        job.awaitEnd(Duration.ofSeconds(20));

        assertThat(BackgroundPanes.state(job)).isEqualTo("exit 3");
        assertThat(BackgroundPanes.liveness(job)).isEqualTo(BackgroundPanes.Liveness.FAILED);
    }

    @Test
    public void howLongSomethingHasBeenGoingIsSaidInMinutesAndSeconds() {
        assertThat(BackgroundPanes.spoken(Duration.ofSeconds(41))).isEqualTo("41s");
        assertThat(BackgroundPanes.spoken(Duration.ofSeconds(134))).isEqualTo("2m14s");
        assertThat(BackgroundPanes.spoken(Duration.ofSeconds(60))).isEqualTo("1m0s");
        assertThat(BackgroundPanes.spoken(Duration.ZERO)).isEqualTo("0s");
    }

    @Test
    public void nothingRunningIsAnAnswerRatherThanAnEmptyPane() {
        assertThat(BackgroundPanes.all()).isEmpty();
        assertThat(BackgroundPanes.rows()).isEmpty();
    }
}
