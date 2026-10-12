package com.eonmux.cadetcoder.harness.loop;

import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import com.eonmux.cadetcoder.harness.commit.CommitPolicy;
import com.eonmux.cadetcoder.harness.env.Corridor;
import com.eonmux.cadetcoder.harness.env.Environment;
import com.eonmux.cadetcoder.harness.env.Reversibility;
import com.eonmux.cadetcoder.harness.env.StepOutcome;
import com.eonmux.cadetcoder.harness.tools.ToolSession;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A harness run's transcript is compacted before a request outgrows the model's context window.
 *
 * <h2>The defect</h2>
 *
 * <p>Compaction was decided on the average tokens sent per round of thinking, and asked only
 * between rounds. The average trails the latest request by half a run, and a single round that
 * reads and reads without acting has no "between", so a request could pass the window with no
 * compaction at all. The provider then refused it, and the refusal ended the run.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the size of the next request decides, and that it is asked before every request; that a
 * request which cannot fit otherwise gives up the newer answers too; and that a refusal for length
 * compacts and asks again, until there is nothing left to give up.</p>
 */
public class AharnessRunStaysInsideTheContextWindowTest {

    private static final String LOOK = "<call tool=\"observe\"/>";

    private static final String STEP =
            "<call tool=\"commit\"><arg name=\"actions\">[{\"move\": 1}]</arg></call>";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ToolSession session;

    @Before
    public void open() throws Exception {
        session = opened();
    }

    private ToolSession opened() throws java.io.IOException {
        return ToolSession.open(new Verbose(), folder.newFolder().toPath(),
                                new Budget(BudgetLimits.unlimited()), CommitPolicy.standard());
    }

    /** A corridor that describes what it sees at length, as a real tool's output does. */
    private static final class Verbose implements Environment {

        private final Corridor corridor = new Corridor();

        @Override
        public Object reset() {
            corridor.reset();
            return observe();
        }

        @Override
        public Object observe() {
            return Map.of("pos", corridor.reached(), "view", "the corridor goes on. ".repeat(60));
        }

        @Override
        public StepOutcome act(Object action) {
            StepOutcome outcome = corridor.act(action);
            return new StepOutcome(observe(), outcome.flags(), outcome.info());
        }

        @Override
        public List<Object> actionSpace(Object observation) {
            return corridor.actionSpace(observation);
        }

        @Override
        public Reversibility reversibility(Object action) {
            return corridor.reversibility(action);
        }

        @Override
        public String describe() {
            return corridor.describe();
        }
    }

    /**
     * Says its script, and is charged what the request costs to send: the instructions and the
     * transcript, at a token a character, which is all a test needs. It refuses a request larger
     * than {@code refusedAbove}, as a provider refuses a prompt past the window.
     */
    private static final class Growing implements Reasoner {

        private final List<String> replies;
        private final long         refusedAbove;
        private       int          attemptsEach = 1;
        private final List<Long>   costs = new ArrayList<>();
        private final List<Transcript> seen = new ArrayList<>();
        private       int          at;
        private       int          refusals;

        Growing(long refusedAbove, String... replies) {
            this.replies      = List.of(replies);
            this.refusedAbove = refusedAbove;
        }

        @Override
        public String name() {
            return "growing";
        }

        @Override
        public Reply think(String system, Transcript transcript) {
            long cost = system.length() + transcript.characters();
            costs.add(cost);
            seen.add(transcript);
            if (cost > refusedAbove) {
                refusals++;
                throw new PromptTooLong(new IllegalStateException("prompt is too long"), 0);
            }
            String said = at < replies.size() ? replies.get(at++) : ScriptedReasoner.EXHAUSTED;
            return new Reply(said, cost * attemptsEach, 10L, cost);
        }

        /** Billed for every request as though it had taken {@code attempts} attempts. */
        Growing retrying(int attempts) {
            attemptsEach = attempts;
            return this;
        }
    }

    /** What the first request of a run costs, and how long the corridor's answer to a look is. */
    private record Probe(long opening, long observation) {
    }

    private Probe probe() {
        try {
            ToolSession probe = opened();
            Growing agent = new Growing(Long.MAX_VALUE, LOOK, "DONE: looked");
            new Harness(probe, List.of(agent), HarnessLimits.standard(), RunWatch.silent()).run();
            Transcript last = agent.seen.get(agent.seen.size() - 1);
            return new Probe(agent.costs.get(0),
                             last.turns().get(last.size() - 1).text().length());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Harness harness(HarnessLimits limits, RecordingWatch watch, Reasoner reasoner) {
        return new Harness(session, List.of(reasoner), limits, watch);
    }

    /** Seven reads in one round, with no action between them to end the round. */
    private static String[] readingThenDone() {
        return new String[] {LOOK, LOOK, LOOK, LOOK, LOOK, LOOK, LOOK, STEP, "DONE: enough"};
    }

    @Test
    public void aRoundThatOnlyReadsIsCompactedBeforeItsNextRequest() {
        Probe          probe = probe();
        Growing        agent = new Growing(Long.MAX_VALUE, readingThenDone());
        RecordingWatch watch = new RecordingWatch();
        long           limit = probe.opening() + 3 * probe.observation();

        harness(new HarnessLimits(limit, 1, 60, 3), watch, agent).run();

        assertThat(watch.compactions()).as("compacted inside the first round").isNotEmpty();
        assertThat(agent.costs)
                .as("no request carried more than the limit")
                .allSatisfy(cost -> assertThat(cost).isLessThanOrEqualTo(limit));
    }

    /** The average of a run that started small trails its latest request far behind. */
    @Test
    public void theLatestRequestDecidesNotTheAverage() {
        Probe          probe = probe();
        Growing        agent = new Growing(Long.MAX_VALUE, readingThenDone());
        long           limit = probe.opening() + 4 * probe.observation();

        harness(new HarnessLimits(limit, 1, 60, 3), new RecordingWatch(), agent).run();

        assertThat(agent.costs).anySatisfy(cost -> assertThat(cost).isGreaterThan(limit / 2));
        assertThat(agent.costs).allSatisfy(cost -> assertThat(cost).isLessThanOrEqualTo(limit));
    }

    /** Twelve newest answers can be the whole of what is too big. */
    @Test
    public void aRequestThatCannotFitOtherwiseGivesUpNewerAnswersToo() {
        Probe          probe = probe();
        Growing        agent = new Growing(Long.MAX_VALUE, readingThenDone());
        RecordingWatch watch = new RecordingWatch();
        long           limit = probe.opening() + 3 * probe.observation();

        harness(new HarnessLimits(limit, 12, 60, 3), watch, agent).run();

        assertThat(watch.compactions()).isNotEmpty();
        assertThat(agent.costs).allSatisfy(cost -> assertThat(cost).isLessThanOrEqualTo(limit));
    }

    /** A request retried once is billed twice, and its prompt is no larger for it. */
    @Test
    public void aRetriedRequestIsMeasuredByItsPromptNotItsBill() {
        Growing measure = new Growing(Long.MAX_VALUE, readingThenDone());
        harness(new HarnessLimits(Long.MAX_VALUE, 1, 60, 3), new RecordingWatch(), measure).run();
        long largest = measure.costs.stream().mapToLong(Long::longValue).max().orElseThrow();

        session = reopened();
        Growing        agent = new Growing(Long.MAX_VALUE, readingThenDone()).retrying(2);
        RecordingWatch watch = new RecordingWatch();
        // Room for the few characters a run's folder name can differ by; a bill of two attempts
        // is past it from the first request.
        harness(new HarnessLimits(largest * 11 / 10, 1, 60, 3), watch, agent).run();

        assertThat(watch.compactions()).as("every request fitted, so none was compacted").isEmpty();
    }

    private ToolSession reopened() {
        try {
            return opened();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void aRefusalForLengthCompactsAndAsksAgain() {
        Probe          probe = probe();
        Growing        agent = new Growing(probe.opening() + 4 * probe.observation(),
                                           readingThenDone());
        RecordingWatch watch = new RecordingWatch();

        RunResult result = harness(new HarnessLimits(Long.MAX_VALUE, 2, 60, 3), watch, agent).run();

        assertThat(agent.refusals).as("the provider refused a request").isPositive();
        assertThat(watch.compactions()).isNotEmpty();
        assertThat(result.status()).isEqualTo(RunStatus.DONE);
    }

    @Test
    public void aRefusalWithNothingLeftToGiveUpEndsTheRun() {
        Growing agent = new Growing(1L, readingThenDone());

        assertThatThrownBy(() -> harness(HarnessLimits.standard(), new RecordingWatch(), agent)
                .run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("prompt is too long");
    }
}
