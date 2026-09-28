package com.eonmux.cadetcoder.harness.loop;

import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import com.eonmux.cadetcoder.harness.commit.CommitPolicy;
import com.eonmux.cadetcoder.harness.env.Corridor;
import com.eonmux.cadetcoder.harness.tools.ToolSession;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order in which one round of thinking does what, which is what decides whether the things it
 * says about itself are true.
 *
 * <p><b>The defect</b>: a round is a loop of its own inside the run's loop, and everything the outer
 * loop does between rounds -- asking whether there is still time, deciding whether anything has
 * happened -- is done for a round that may go round many more times before it ends. Four of those
 * inner steps were in the wrong order or read the wrong thing:</p>
 *
 * <ul>
 *   <li>time was asked about only between rounds, so a round that kept reading -- which costs no
 *       action and, until the call cap, no further round -- was never stopped by the clock;</li>
 *   <li>being called off was asked about only before thinking, so a run taken back while the model
 *       was answering still reached the world with what came back;</li>
 *   <li>replies that ended without acting were counted straight through rather than in a row, so a
 *       round that read, asked, read and asked was stopped as stuck and told it had refused to act
 *       a number of times running it never had;</li>
 *   <li>whether the world had been reached was read off the name of the tool rather than off the
 *       ledger, so a commit the gate refused counted as something happening, and a reply that
 *       reached the goal and then began a fresh episode had its goal read off the wrong
 *       transition.</li>
 * </ul>
 */
public class WhatARoundOfThinkingDoesInWhatOrderTest {

    /** A blind probe of one step, which is what a scripted agent commits when it wants to act. */
    private static final String STEP =
            "<call tool=\"commit\"><arg name=\"actions\">[{\"move\": 1}]</arg></call>";

    /** A commit of a plan that was never found, which the toolbox refuses without acting. */
    private static final String REFUSED =
            "<call tool=\"commit\"><arg name=\"plan\">one-nobody-found</arg></call>";

    /** A call that reads and changes nothing, which is what a round spends its turns on. */
    private static final String LOOK = "<call tool=\"observe\"></call>";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ToolSession open(Corridor world, BudgetLimits limits) throws Exception {
        Path at = folder.newFolder("run" + System.nanoTime()).toPath();
        return ToolSession.open(world, at, new Budget(limits), CommitPolicy.standard());
    }

    private static Harness harness(ToolSession session, RunStop stop, Reasoner... reasoners) {
        return new Harness(session, List.of(reasoners), HarnessLimits.standard(), RunWatch.silent(),
                           stop);
    }

    /**
     * Being called off has to be asked about after the reply as well as before it.
     *
     * <p>Thinking is the long part of a round and the part a person watching it go wrong is most
     * likely to stop it during. Asked only at the top of the round, the reply that came back while
     * they were stopping it is still dispatched, so the last thing a run taken back does is reach
     * the world.</p>
     */
    @Test
    public void arunTakenBackWhileItWasThinkingDoesNotActOnWhatCameBack() throws Exception {
        Corridor         world  = new Corridor();
        ToolSession      session = open(world, BudgetLimits.unlimited());
        ScriptedReasoner agent  = ScriptedReasoner.saying(STEP, STEP, STEP);
        AtomicBoolean    off    = new AtomicBoolean();

        RunResult result = harness(session,
                                   () -> off.get() || (agent.asked() > 0
                                                       && off.compareAndSet(false, true)),
                                   agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.STOPPED);
        assertThat(agent.asked()).isEqualTo(1);
        assertThat(world.taken())
                .as("the reply asked for a step; the run had already been taken back")
                .isEmpty();
        assertThat(session.ledger().size())
                .as("only the reset that opened the run")
                .isEqualTo(1);
    }

    /** A reasoner that reads the world a fixed number of times, slowly, and then says it is done. */
    private static final class Slowly implements Reasoner {

        private final int  reads;
        private final long pause;

        private int asked;

        private Slowly(int reads, long pause) {
            this.reads = reads;
            this.pause = pause;
        }

        @Override
        public String name() {
            return "slowly";
        }

        @Override
        public Reply think(String system, Transcript transcript) {
            try {
                Thread.sleep(pause);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return new Reply(asked++ < reads ? LOOK : "DONE: read enough", 1L, 1L);
        }
    }

    /**
     * The clock has to be read inside a round as well as between them.
     *
     * <p>A round ends at the first thing that happens to the world, so a round in which nothing
     * happens does not end. Reading costs no action, and until the call cap is reached it costs no
     * further round either, so the outer loop -- where the time was asked about -- is somewhere such
     * a run never gets back to. A run given nothing but a deadline would sail past it.</p>
     */
    @Test
    public void arunThatKeepsReadingStillRunsOutOfTheTimeItWasGiven() throws Exception {
        BudgetLimits deadline = new BudgetLimits(BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED,
                                                 120L, BudgetLimits.UNLIMITED);
        Slowly       agent    = new Slowly(40, 25L);

        RunResult result = harness(open(new Corridor(), deadline), RunStop.never(), agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.BUDGET);
        assertThat(result.finalText()).contains("time allowance");
    }

    /**
     * Turns that ended without acting are counted in a row, because that is what the run is told.
     *
     * <p>The ending says "{@code N} turns in a row ended without acting", and a run stopped by it is
     * a run whose work is thrown away. Counted straight through, a round that reads, asks for
     * something, reads and asks again accumulates the same total as one that said nothing at all
     * four times over -- so a working round is stopped, and the reason it is given is untrue.</p>
     */
    @Test
    public void areplyThatAskedForSomethingStartsTheCountingAgain() throws Exception {
        ScriptedReasoner agent = ScriptedReasoner.saying("thinking about it", "still thinking",
                                                         "nearly there", LOOK,
                                                         "thinking about it", "still thinking",
                                                         "nearly there", "DONE: finished");

        RunResult result = harness(open(new Corridor(), BudgetLimits.unlimited()), RunStop.never(),
                                   agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(result.finalText()).contains("finished");
    }

    /**
     * A call the toolbox refused is not something that happened.
     *
     * <p>A round ends at the first thing that happened to the world so that the next one starts from
     * a true picture of it. Decided by the name of the tool, a commit that never got as far as the
     * gate ends the round exactly as surely as one that moved -- which spends a round of thinking on
     * a mistake, and tells the rest of the driver the world moved when it did not.</p>
     */
    @Test
    public void acommitThatWasRefusedIsNotSomethingHavingHappened() throws Exception {
        Corridor    world   = new Corridor();
        ToolSession session = open(world, BudgetLimits.unlimited());

        RunResult result = harness(session, RunStop.never(),
                                   ScriptedReasoner.saying(REFUSED, "DONE: nothing to do")).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(world.taken()).isEmpty();
        assertThat(session.ledger().size()).isEqualTo(1);
        assertThat(result.deliberations())
                .as("the refusal did not reach the world, so it did not end the round")
                .isEqualTo(1);
    }

    /**
     * Reaching the goal is a fact about the run that the rest of the same reply cannot undo.
     *
     * <p>A reply may ask for more than one thing. Read off the last transition alone, a reply that
     * got where it was going and then began a fresh episode looks like a run that has merely begun
     * one -- so the run carries on past the thing it was asked for, spending the rest of its
     * allowance on work nobody wanted.</p>
     */
    @Test
    public void agoalReachedPartWayThroughAreplyStillEndsTheRun() throws Exception {
        Corridor world = new Corridor();
        world.goalAt(1);
        ToolSession session = open(world, BudgetLimits.unlimited());

        RunResult result = harness(session, RunStop.never(), ScriptedReasoner.saying(
                STEP + System.lineSeparator()
                + "<call tool=\"reset\"><arg name=\"note\">starting over</arg></call>",
                "DONE: should never be asked")).run();

        assertThat(result.status()).isEqualTo(RunStatus.GOAL);
    }
}
