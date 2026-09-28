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
 * The driver is the loop: it starts the world, asks the agent, runs what the agent asked for, and
 * decides when there is nothing left to ask.
 *
 * <p>Three of its decisions are the ones a run lives or dies by. A deliberation has to end at the
 * first thing that changed the world, or the agent goes on planning against a world that has moved.
 * A run has to end when the world says it has, when the agent says it has, and when the allowance is
 * gone -- and ending on an allowance means reporting it, not dying of it. And an agent that neither
 * acts nor says why has to be stopped, because a harness that nudges forever spends a whole budget
 * on encouragement.</p>
 */
public class TheHarnessDrivesTheLoopAndKnowsWhenToStopTest {

    /** A blind probe of one step, which is what a scripted agent commits when it wants to act. */
    private static final String STEP =
            "<call tool=\"commit\"><arg name=\"actions\">[{\"move\": 1}]</arg></call>";

    /** A blind probe long enough to cross the corridor in one commit. */
    private static final String THREE_STEPS =
            "<call tool=\"commit\"><arg name=\"actions\">"
            + "[{\"move\": 1}, {\"move\": 1}, {\"move\": 1}]</arg></call>";

    /** A call that reads the world without touching it. */
    private static final String LOOK = "<call tool=\"observe\"/>";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Corridor    corridor;
    private ToolSession session;

    @Before
    public void open() throws Exception {
        corridor = new Corridor();
        session  = ToolSession.open(corridor, folder.newFolder("run").toPath(),
                                    new Budget(BudgetLimits.unlimited()), CommitPolicy.standard());
    }

    private Harness harness(Reasoner... reasoners) {
        return new Harness(session, List.of(reasoners), HarnessLimits.standard(), RunWatch.silent());
    }

    private Harness harness(RunWatch watch, Reasoner... reasoners) {
        return new Harness(session, List.of(reasoners), HarnessLimits.standard(), watch);
    }

    private Harness harness(HarnessLimits limits, RunWatch watch, Reasoner... reasoners) {
        return new Harness(session, List.of(reasoners), limits, watch);
    }

    private void spending(BudgetLimits limits) throws Exception {
        session = ToolSession.open(corridor, folder.newFolder("spent").toPath(),
                                   new Budget(limits), CommitPolicy.standard());
    }

    /** Everything the harness itself said, as one block. */
    private static String toldTheAgent(Transcript transcript) {
        List<String> said = new ArrayList<>();
        for (Turn turn : transcript.turns()) {
            if (turn.kind() == TurnKind.HARNESS) {
                said.add(turn.text());
            }
        }
        return String.join(System.lineSeparator(), said);
    }

    /**
     * An agent asked what to do before anything has been observed is being asked about a world
     * nothing has looked at, and the first thing it commits is measured from nowhere.
     */
    @Test
    public void theWorldIsStartedBeforeTheAgentIsAskedAnything() {
        harness(ScriptedReasoner.saying("DONE: nothing to do here")).run();

        assertThat(session.ledger().size()).isEqualTo(1);
        assertThat(session.ledger().get(0).isReset()).isTrue();
    }

    /**
     * A run picked up against a ledger that already has something in it is the same run continuing.
     * Resetting there would throw away the episode the ledger is a record of, and every certificate
     * about it.
     */
    @Test
    public void aRunThatIsResumedDoesNotStartTheWorldOverAgain() {
        session.gate().reset("an earlier run began here");

        harness(ScriptedReasoner.saying("DONE: picking up where the ledger left off")).run();

        assertThat(session.ledger().size()).isEqualTo(1);
    }

    @Test
    public void theFirstThingTheAgentIsShownIsWhereTheWorldIs() {
        ScriptedReasoner agent = ScriptedReasoner.saying("DONE: seen enough");

        harness(agent).run();

        Transcript opening = agent.seen().get(0);
        assertThat(opening.size()).isEqualTo(1);
        assertThat(opening.turns().get(0).kind()).isEqualTo(TurnKind.HARNESS);
        assertThat(opening.turns().get(0).text()).contains("pos");
    }

    @Test
    public void theSystemPromptTheAgentIsGivenDescribesTheWorldItIsActingOn() {
        ScriptedReasoner agent = ScriptedReasoner.saying("DONE: seen enough");

        harness(agent).run();

        assertThat(agent.systems().get(0)).contains(corridor.describe());
    }

    /**
     * The environment is the only thing that gets to say the goal was reached. A run that carried on
     * past it would keep spending on a world that has already answered.
     */
    @Test
    public void aRunEndsTheMomentTheWorldSaysTheGoalIsReached() {
        corridor.goalAt(3);
        ScriptedReasoner agent = ScriptedReasoner.saying(THREE_STEPS, STEP);

        RunResult result = harness(agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.GOAL);
        assertThat(agent.asked()).isEqualTo(1);
        assertThat(corridor.reached()).isEqualTo(3);
    }

    @Test
    public void aRunTheAgentSaysIsFinishedEndsAsDone() {
        RunResult result = harness(ScriptedReasoner.saying(STEP, "DONE: the corridor adds one")).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(result.finalText()).startsWith(SystemPrompt.DONE);
    }

    @Test
    public void aRunTheAgentGivesUpOnEndsAsStuck() {
        RunResult result = harness(ScriptedReasoner.saying("STUCK: I cannot tell what move does")).run();

        assertThat(result.status()).isEqualTo(RunStatus.STUCK);
        assertThat(result.finalText()).contains("what move does");
    }

    /**
     * A turn that neither acts nor says why is the failure mode of every agent loop: it reads,
     * concludes, and reads again. Accepting it as the end of a deliberation would let a run spend its
     * whole budget without one thing ever happening to the world.
     */
    @Test
    public void endingATurnWithoutActingIsAnsweredWithANudgeRatherThanAccepted() {
        ScriptedReasoner agent = ScriptedReasoner.saying("I will have a think about the corridor.",
                                                         STEP, "DONE: that is the rule");

        RunResult result = harness(agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(toldTheAgent(agent.seen().get(1))).contains("commit");
        assertThat(agent.asked()).isEqualTo(3);
    }

    /**
     * Nudging is a correction, and a correction that has not worked three times over is not going to.
     * The reference harness nudged without a limit, which turns a stubborn agent into a run that
     * spends everything it has on being asked again.
     */
    @Test
    public void anAgentThatWillNotActIsStoppedRatherThanNudgedForever() {
        HarnessLimits    twice = HarnessLimits.standard().withMaxSilentReplies(2);
        ScriptedReasoner agent = ScriptedReasoner.saying("thinking", "still thinking",
                                                         "yet more thinking", "and more", STEP);

        RunResult result = harness(twice, RunWatch.silent(), agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.STUCK);
        assertThat(agent.asked()).isEqualTo(3);
        assertThat(corridor.taken()).isEmpty();
    }

    /**
     * A reply cut off by a token limit is the commonest malformed call there is. Dropping it silently
     * leaves the agent believing it acted; the complaint is what lets it write the call again.
     */
    @Test
    public void aReplyThatRanOutOfRoomIsComplainedAboutRatherThanQuietlyDropped() {
        ScriptedReasoner agent = ScriptedReasoner.saying("<call tool=\"observe\">",
                                                         STEP, "DONE: written properly this time");

        RunResult result = harness(agent).run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(toldTheAgent(agent.seen().get(1))).contains("never closed");
    }

    @Test
    public void everyCallInOneReplyIsRunInTheOrderItWasWritten() {
        RecordingWatch watch = new RecordingWatch();
        String         reply = LOOK
                               + "<call tool=\"notes_append\"><arg name=\"text\">it adds one</arg></call>"
                               + STEP;

        harness(HarnessLimits.standard(), watch,
                ScriptedReasoner.saying(reply, "DONE: noted")).run();

        assertThat(watch.calls()).containsExactly("observe", "notes_append", "commit");
    }

    /**
     * An allowance that is gone is a fact about the run, not a fault in it. The reference let the
     * exception out of the loop, so a run that spent what it was given produced no result at all --
     * and whoever asked for it could not tell that from a crash.
     */
    @Test
    public void aRunThatHasNoDeliberationsLeftEndsAsBudgetRatherThanRunningOn() throws Exception {
        spending(new BudgetLimits(BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED,
                                  BudgetLimits.UNLIMITED, 1));

        RunResult result = harness(ScriptedReasoner.saying(STEP, STEP, STEP)).run();

        assertThat(result.status()).isEqualTo(RunStatus.BUDGET);
        assertThat(result.deliberations()).isEqualTo(1);
        assertThat(result.finalText()).contains("deliberation allowance");
    }

    @Test
    public void aRunThatHasNoTokensLeftEndsAsBudgetRatherThanThrowing() throws Exception {
        spending(new BudgetLimits(BudgetLimits.UNLIMITED, 500L, BudgetLimits.UNLIMITED,
                                  BudgetLimits.UNLIMITED));

        RunResult result = harness(ScriptedReasoner.saying(STEP, STEP)).run();

        assertThat(result.status()).isEqualTo(RunStatus.BUDGET);
        assertThat(result.finalText()).contains("token allowance");
        assertThat(corridor.taken()).isEmpty();
    }

    /**
     * Compaction is what makes a run longer than a context window. What has to survive it is the
     * agent's own reasoning and a fresh statement of where the world is; what is given up is the tool
     * answers, which the ledger and the beliefs still hold.
     */
    @Test
    public void aTranscriptThatOutgrowsWhatTheReasonerHoldsIsCompactedWhileTheRunGoesOn() {
        HarnessLimits    tight = new HarnessLimits(500L, 0, 60, 3);
        RecordingWatch   watch = new RecordingWatch();
        ScriptedReasoner agent = ScriptedReasoner.saying(LOOK, LOOK, STEP, "DONE: enough");

        Harness   harness = harness(tight, watch, agent);
        RunResult result  = harness.run();

        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(watch.compactions()).isNotEmpty();
        assertThat(harness.transcript().squashed()).isGreaterThanOrEqualTo(2);

        Turn notice = agent.last().turns().get(agent.last().size() - 1);
        assertThat(notice.kind()).isEqualTo(TurnKind.HARNESS);
        assertThat(notice.text()).contains("compacted").contains("budget:");
    }

    /**
     * A plateau is the measurement that says more of the same reasoner will not help. Handing the
     * session over is the only move left that is not spending the rest of the budget to find that out
     * again.
     */
    @Test
    public void aPlateauHandsTheRunToTheNextReasoner() {
        ScriptedReasoner first  = ScriptedReasoner.saying(STEP, STEP, STEP, STEP, STEP)
                                                  .named("first");
        ScriptedReasoner second = ScriptedReasoner.saying("DONE: I see what it does").named("second");

        RunResult result = harness(first, second).run();

        assertThat(result.escalations()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(RunStatus.DONE);
        assertThat(first.asked()).isEqualTo(5);
        assertThat(second.asked()).isEqualTo(1);
        assertThat(toldTheAgent(second.last())).contains("no model improvement");
    }

    /**
     * With nobody stronger left, a plateau is still true and still worth saying -- the status block
     * says it every turn -- but there is no hand-over to count. Counting one would report a run as
     * having escalated to itself.
     */
    @Test
    public void aPlateauWithNobodyStrongerLeftIsNotAnEscalation() {
        ScriptedReasoner only = ScriptedReasoner.saying(STEP, STEP, STEP, STEP, STEP,
                                                        "DONE: it adds one");

        RunResult result = harness(only).run();

        assertThat(result.escalations()).isZero();
        assertThat(result.status()).isEqualTo(RunStatus.DONE);
    }

    /**
     * A run that has stalled with nobody to hand to is the one case where the person who started it
     * can do something the run cannot: name a stronger model. Saying nothing left that run to spend
     * the rest of its budget in silence, and left the escalation the harness is built around
     * undiscoverable -- a feature nobody is ever told exists is one nobody configures.
     *
     * <p>Said once. A plateau is measured over a window, so it is still true on every deliberation
     * that follows, and repeating it would bury the run's actual work under the same sentence.</p>
     */
    @Test
    public void aRunThatStalledWithNobodyToHandToSaysSoOnce() {
        ScriptedReasoner only = ScriptedReasoner.saying(STEP, STEP, STEP, STEP, STEP, STEP, STEP,
                                                        "DONE: it adds one");
        RecordingWatch watch = new RecordingWatch();

        harness(watch, only).run();

        assertThat(watch.stalls()).hasSize(1);
        assertThat(watch.stalls().get(0)).contains("no model improvement");
    }

    /** Being handed over is already announced, so the same run is not also reported as stuck. */
    @Test
    public void aRunThatStalledAndHadSomewhereToGoIsReportedAsAHandOverAndNotAlsoAsStuck() {
        RecordingWatch watch = new RecordingWatch();

        harness(watch, ScriptedReasoner.saying(STEP, STEP, STEP, STEP, STEP).named("first"),
                ScriptedReasoner.saying("DONE: I see what it does").named("second")).run();

        assertThat(watch.escalations()).hasSize(1);
        assertThat(watch.stalls()).isEmpty();
    }

    @Test
    public void whatARunReportsIsWhatActuallyHappened() {
        RunResult result = harness(ScriptedReasoner.saying(STEP, STEP, "DONE: twice is enough")).run();

        assertThat(result.deliberations()).isEqualTo(3);
        assertThat(result.actions()).isEqualTo(2);
        assertThat(result.ledgerLength()).isEqualTo(3);
        assertThat(result.escalations()).isZero();
    }

    /**
     * A run is a long-lived thing somebody is waiting on, and the watch is the only window into it. A
     * driver that did the right thing while telling nobody is one nothing can be shown of.
     */
    @Test
    public void everythingThatHappensIsReportedToWhoeverIsWatching() {
        RecordingWatch watch = new RecordingWatch();

        RunResult result = harness(HarnessLimits.standard(), watch,
                                   ScriptedReasoner.saying(LOOK + STEP, "DONE: told")).run();

        assertThat(watch.thoughts()).hasSize(2);
        assertThat(watch.calls()).containsExactly("observe", "commit");
        assertThat(watch.endings()).isEqualTo(1);
        assertThat(watch.ending()).isEqualTo(result);
    }

    @Test
    public void aRunNeedsSomethingToDoItsThinking() {
        assertThatThrownBy(() -> new Harness(session, List.of(), HarnessLimits.standard(),
                                             RunWatch.silent()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoner");
    }
}
