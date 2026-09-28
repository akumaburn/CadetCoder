package com.eonmux.cadetcoder.harness.loop;

import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import com.eonmux.cadetcoder.harness.commit.CommitPolicy;
import com.eonmux.cadetcoder.harness.env.Corridor;
import com.eonmux.cadetcoder.harness.tools.ToolSession;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run is minutes or hours of somebody else's machine, and they have to be able to take it back.
 *
 * <p>Without this the only ways out of the loop are the agent declaring an ending and the allowance
 * running out -- so a run started by mistake, or one that has clearly gone the wrong way, can only be
 * ended by killing the process, which loses the ledger's last write and tells whoever asked nothing
 * about what happened. These tests lock down the third ending: the run stops before the next thing it
 * would have paid for, everything it established is still on disk, and it is reported as having been
 * called off rather than as the agent giving up -- which are different facts about different
 * parties.</p>
 */
public class ARunCanBeCalledOffTest {

    /** A blind probe of one step, which is what a scripted agent commits when it wants to act. */
    private static final String STEP =
            "<call tool=\"commit\"><arg name=\"actions\">[{\"move\": 1}]</arg></call>";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ToolSession session;

    @Before
    public void open() throws Exception {
        session = ToolSession.open(new Corridor(), folder.newFolder("run").toPath(),
                                   new Budget(BudgetLimits.unlimited()), CommitPolicy.standard());
    }

    private Harness harness(RunStop stop, RunWatch watch, Reasoner... reasoners) {
        return new Harness(session, List.of(reasoners), HarnessLimits.standard(), watch, stop);
    }

    @Test
    public void aRunNobodyCallsOffEndsTheWayItWouldHaveAnyway() {
        RunResult result = harness(RunStop.never(), RunWatch.silent(),
                                   ScriptedReasoner.saying("DONE: nothing to do here")).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
    }

    @Test
    public void aRunIsStoppedBeforeItPaysForAnotherReply() {
        ScriptedReasoner agent = ScriptedReasoner.saying(STEP, STEP, STEP);

        harness(() -> true, RunWatch.silent(), agent).run();

        assertThat(agent.asked()).isZero();
    }

    @Test
    public void aRunCalledOffPartWayThroughStopsAtTheNextTurnRatherThanCarryingOn() {
        ScriptedReasoner agent   = ScriptedReasoner.saying(STEP, STEP, STEP, STEP, STEP);
        AtomicBoolean    calledOff = new AtomicBoolean();

        RunResult result = harness(() -> calledOff.get() || flip(calledOff, agent), RunWatch.silent(),
                                   agent).run();

        assertThat(agent.asked()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
    }

    /** Calls the run off once the agent has been asked something, so a stop lands mid-run. */
    private static boolean flip(AtomicBoolean calledOff, ScriptedReasoner agent) {
        return agent.asked() > 0 && calledOff.compareAndSet(false, true);
    }

    @Test
    public void whatWasCalledOffIsNotReportedAsTheAgentGivingUp() {
        RunResult result = harness(() -> true, RunWatch.silent(),
                                   ScriptedReasoner.saying(STEP)).run();

        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(result.status()).isNotEqualTo(RunStatus.STUCK);
        assertThat(result.status().over()).isTrue();
    }

    @Test
    public void everythingTheRunEstablishedBeforeItWasCalledOffIsStillOnDisk() {
        RunResult result = harness(() -> true, RunWatch.silent(),
                                   ScriptedReasoner.saying(STEP)).run();

        assertThat(session.ledger().size()).isEqualTo(1);
        assertThat(session.ledger().get(0).isReset()).isTrue();
        assertThat(result.ledgerLength()).isEqualTo(1);
    }

    @Test
    public void whoeverIsWatchingIsToldTheRunEndedRatherThanBeingLeftHanging() {
        RecordingWatch watching = new RecordingWatch();

        harness(() -> true, watching, ScriptedReasoner.saying(STEP)).run();

        assertThat(watching.endings()).isEqualTo(1);
        assertThat(watching.ending().status()).isEqualTo(RunStatus.STOPPED);
    }

    @Test
    public void aResultSaysWhyTheRunStoppedSoNobodyReadsItAsFinishedWork() {
        RunResult result = harness(() -> true, RunWatch.silent(),
                                   ScriptedReasoner.saying(STEP)).run();

        assertThat(result.render()).contains("STOPPED");
        assertThat(result.finalText()).isNotEmpty();
    }

    @Test
    public void aThreadThatHasBeenInterruptedCallsOffTheRunItIsDriving() throws Exception {
        ScriptedReasoner agent  = ScriptedReasoner.saying(STEP, STEP);
        RunStatus[]      status = new RunStatus[1];

        Thread running = new Thread(() -> {
            Thread.currentThread().interrupt();
            status[0] = harness(RunStop.whenThreadInterrupted(), RunWatch.silent(), agent).run()
                              .status();
        });
        running.start();
        running.join();

        assertThat(status[0]).isEqualTo(RunStatus.STOPPED);
        assertThat(agent.asked()).isZero();
    }

    @Test
    public void aRunOnAnUninterruptedThreadIsNotCalledOffByAccident() throws Exception {
        RunStatus[] status = new RunStatus[1];

        Thread running = new Thread(() -> status[0] =
                harness(RunStop.whenThreadInterrupted(), RunWatch.silent(),
                        ScriptedReasoner.saying("DONE: finished")).run().status());
        running.start();
        running.join();

        assertThat(status[0]).isEqualTo(RunStatus.DONE);
    }

    @Test
    public void theFourArgumentRunIsOneNobodyCanCallOff() {
        RunResult result = new Harness(session, List.of(ScriptedReasoner.saying("DONE: finished")),
                                       HarnessLimits.standard(), RunWatch.silent()).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
    }
}
