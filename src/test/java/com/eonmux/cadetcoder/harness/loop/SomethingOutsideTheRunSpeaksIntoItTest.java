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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where something outside a run gets to put words in front of the agent.
 *
 * <p><b>The defect</b>: a check-in the agent set itself several steps ago, a question about a
 * completion it has just claimed, and how hard this run is to be driven, all have to reach it --
 * and none of them is anything the loop could work out for itself. One comes from a command the
 * agent ran and two from settings a person chose, so without somewhere to ask, the loop would have
 * to reach into half of the tool it is deliberately independent of.</p>
 *
 * <p><b>What is locked here</b>: that what is said between rounds reaches the agent, marked as the
 * driver speaking rather than as the world answering; that a completion can be sent back and the run
 * goes on; that the run still ends the moment nothing has a further question, so a bounded number of
 * questions is what bounds the run; that an instruction meant to hold for the whole run is part of
 * the standing instructions rather than of a turn that can be compacted away; and that a harness
 * built without signals behaves exactly as it did before there were any.</p>
 */
public class SomethingOutsideTheRunSpeaksIntoItTest {

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

    private Harness harness(RunSignals signals, Reasoner... reasoners) {
        return new Harness(session, List.of(reasoners), HarnessLimits.standard(),
                           RunWatch.silent(), RunStop.never(), signals);
    }

    /** Everything the driver said, which is what the agent reads as coming from the harness. */
    private static String toldTheAgent(Transcript transcript) {
        List<String> said = new ArrayList<>();
        for (Turn turn : transcript.turns()) {
            if (turn.kind() == TurnKind.HARNESS) {
                said.add(turn.text());
            }
        }
        return String.join(System.lineSeparator(), said);
    }

    /** Says one thing once, then has nothing further to say. */
    private static final class Saying implements RunSignals {

        private final String once;
        private final int    questions;
        private final String standing;

        private int said;
        private int asked;

        private Saying(String once, int questions) {
            this(once, questions, "");
        }

        private Saying(String once, int questions, String standing) {
            this.once      = once;
            this.questions = questions;
            this.standing  = standing;
        }

        @Override
        public String standingInstruction() {
            return standing;
        }

        @Override
        public String beforeRound() {
            return said++ == 0 ? once : "";
        }

        @Override
        public String questionDone(String declaration) {
            return asked++ < questions ? "are you sure about: " + declaration : "";
        }

        int asked() {
            return asked;
        }
    }

    @Test
    public void whatIsSaidBetweenRoundsReachesTheAgent() {
        ScriptedReasoner agent = ScriptedReasoner.saying(STEP, "DONE: it adds one");

        RunResult result = harness(new Saying("[timer] t1 fired", 0), agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(toldTheAgent(agent.last())).contains("[timer] t1 fired");
    }

    /** It has to read as the driver speaking; read as the world answering it would be evidence. */
    @Test
    public void whatIsSaidIsMarkedAsTheDriverSpeaking() {
        ScriptedReasoner agent = ScriptedReasoner.saying(STEP, "DONE: it adds one");

        harness(new Saying("[timer] t1 fired", 0), agent).run();

        for (Turn turn : agent.last().turns()) {
            if (turn.text().contains("[timer] t1 fired")) {
                assertThat(turn.kind()).isEqualTo(TurnKind.HARNESS);
                return;
            }
        }
        throw new AssertionError("the agent was never told about the timer");
    }

    @Test
    public void aclaimOfCompletionCanBeSentBackAndTheRunGoesOn() {
        ScriptedReasoner agent = ScriptedReasoner.saying("DONE: finished", STEP, "DONE: really");
        Saying           asks  = new Saying("", 1);

        RunResult result = harness(asks, agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(result.finalText()).contains("really");
        assertThat(asks.asked()).isEqualTo(2);
        assertThat(toldTheAgent(agent.last())).contains("are you sure about: DONE: finished");
    }

    /** A run whose every claim was refused would never end, so the questions have to run out. */
    @Test
    public void therunEndsAsSoonAsNothingHasAFurtherQuestion() {
        ScriptedReasoner agent = ScriptedReasoner.saying("DONE: finished", "DONE: still finished");

        RunResult result = harness(new Saying("", 1), agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(agent.asked()).isEqualTo(2);
    }

    /** A run nothing has anything to say to behaves exactly as it did before there was anything. */
    @Test
    public void arunWithNoSignalsIsUnchanged() {
        ScriptedReasoner agent = ScriptedReasoner.saying("DONE: finished");

        RunResult result = new Harness(session, List.of(agent), HarnessLimits.standard(),
                                       RunWatch.silent()).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(agent.asked()).isEqualTo(1);
        assertThat(RunSignals.none().beforeRound()).isEmpty();
        assertThat(RunSignals.none().questionDone("DONE: finished")).isEmpty();
    }

    @Test
    public void arunCannotBeBuiltWithNowhereToTakeSignalsFrom() {
        assertThatThrownBy(() -> new Harness(session, List.of(ScriptedReasoner.saying("DONE: x")),
                                             HarnessLimits.standard(), RunWatch.silent(),
                                             RunStop.never(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The third thing outside a run that has to reach the agent, and the only one that is not about
     * a moment.
     *
     * <p>A check-in and a question both belong to the turn they arrive in. How hard this run is to
     * be driven -- what counts as finished, and what does not -- is true of the whole of it, and an
     * instruction like that said in a turn is one the agent reads once and has compacted away by
     * the time it matters. It belongs in the standing instructions, which is the one part of what
     * the agent is told that does not change and is never dropped.</p>
     */
    @Test
    public void astandingInstructionIsPartOfWhatTheAgentIsTold() {
        ScriptedReasoner agent = ScriptedReasoner.saying("DONE: finished");

        harness(new Saying("", 0, "FINISH EVERY PART OF IT"), agent).run();

        assertThat(agent.systems()).isNotEmpty();
        assertThat(agent.systems().get(0))
                .as("it qualifies what the harness has just said, so it is read after it")
                .contains(SystemPrompt.preamble())
                .endsWith("FINISH EVERY PART OF IT");
    }

    /** A run with nothing standing is told exactly what it was told before there was anything. */
    @Test
    public void arunWithNothingStandingIsToldWhatItAlwaysWas() {
        ScriptedReasoner agent = ScriptedReasoner.saying("DONE: finished");

        new Harness(session, List.of(agent), HarnessLimits.standard(), RunWatch.silent()).run();

        assertThat(agent.systems().get(0)).isEqualTo(SystemPrompt.render(session.env()));
        assertThat(RunSignals.none().standingInstruction()).isEmpty();
    }
}
