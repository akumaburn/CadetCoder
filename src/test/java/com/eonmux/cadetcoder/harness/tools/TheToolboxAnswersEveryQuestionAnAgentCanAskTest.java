package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import com.eonmux.cadetcoder.harness.commit.CommitPolicy;
import com.eonmux.cadetcoder.harness.env.Corridor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The toolbox is the whole of what an agent can do, and every answer it gives is one the agent can
 * act on.
 *
 * <p>Two properties are worth more than the rest put together. The first is that nothing but a
 * commit or a reset ever reaches the world, so every other tool can be called freely without the
 * ledger moving underneath the agent. The second is that a refusal comes back as an answer rather
 * than as a thrown failure: an agent that is told what was wrong with its call can fix it, and a run
 * that dies on a mistyped argument cannot.</p>
 */
public class TheToolboxAnswersEveryQuestionAnAgentCanAskTest {

    private static final String CORRIDOR = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 3; }
            fn actions(state) { return [{"move": 1}]; }
            fn features(state) { return {"pos": state.pos}; }
            fn halfway(state) { return state.pos >= 2; }
            """;

    private static final String DOUBLES = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.move * 2}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 3; }
            fn actions(state) { return [{"move": 1}]; }
            """;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Corridor corridor;
    private Toolbox  toolbox;

    @Before
    public void open() throws Exception {
        corridor = new Corridor();
        corridor.goalAt(3);
        toolbox  = new Toolbox(ToolSession.open(corridor, folder.newFolder("run").toPath(),
                                                new Budget(BudgetLimits.unlimited()),
                                                CommitPolicy.standard()));
    }

    private String call(String tool, Object... pairs) {
        Map<String, Object> given = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            given.put((String) pairs[at], pairs[at + 1]);
        }
        return toolbox.dispatch(tool, given);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            value.put((String) pairs[at], pairs[at + 1]);
        }
        return value;
    }

    private static Object move(int steps) {
        return map("move", steps);
    }

    /**
     * A corridor whose rules the ledger has actually exercised: probe it to the end, start over,
     * and only then write the theory down, so that a replay has something to test it against.
     */
    private void exercise() {
        call("commit", "actions", List.of(move(1), move(1), move(1)), "note", "a probe");
        call("reset", "note", "back to the start");
        call("write_model", "source", CORRIDOR, "note", "first theory");
    }

    private String digestOf(String source, String note) {
        call("write_model", "source", source, "note", note);
        return toolbox.session().registry().latest();
    }

    @Test
    public void observeSaysWhereTheWorldIsAndWhatTheRunHasSpent() {
        String answer = call("observe");

        assertThat(answer).contains("pos").contains("ledger").contains("budget");
    }

    @Test
    public void aToolNobodyHasIsRefusedByNameRatherThanRunSilently() {
        String answer = call("run_python", "code", "print(1)");

        assertThat(answer).startsWith("refused:").contains("run_python").contains("observe");
    }

    @Test
    public void anArgumentThatMakesNoSenseComesBackAsAnAnswerRatherThanKillingTheRun() {
        assertThat(call("ledger_get")).startsWith("refused:").contains("index");
        assertThat(call("ledger_get", "index", "soon")).startsWith("refused:")
                                                       .contains("whole number");
        assertThat(call("ledger_tail", "n", 2, "unknown", true)).startsWith("refused:")
                                                               .contains("unknown");
    }

    @Test
    public void writingAModelReplaysTheWholeLedgerThroughItAtOnce() {
        call("commit", "actions", List.of(move(1), move(1)), "note", "a probe");

        String answer = call("write_model", "source", CORRIDOR, "note", "first theory");

        assertThat(answer).contains("GREEN").contains("checked=2")
                          .contains(toolbox.session().registry().latest());
        assertThat(toolbox.session().registry().size()).isEqualTo(1);
    }

    @Test
    public void aModelThatCannotBeReadIsRefusedWithWhatIsWrongWithIt() {
        String answer = call("write_model", "source", "fn parse(obs) { return obs; }");

        assertThat(answer).startsWith("refused:").contains("step");
        assertThat(toolbox.session().registry().size()).isZero();
    }

    @Test
    public void aModelThatDoesNotSurviveTheLedgerCannotBePlannedWith() {
        call("commit", "actions", List.of(move(1)), "note", "a probe");
        call("write_model", "source", DOUBLES, "note", "a wrong theory");

        String answer = call("plan");

        assertThat(answer).startsWith("refused:").contains("RED");
    }

    @Test
    public void aPlanIsFoundAndKeptSoItCanBeCommittedByTheNameItWasGiven() {
        exercise();

        String planned = call("plan");

        assertThat(planned).contains("found").contains("P1");

        String committed = call("commit", "plan", "P1");

        assertThat(committed).contains("executed=3");
        assertThat(corridor.reached()).isEqualTo(3);
    }

    @Test
    public void aPlanCanBeAskedForSomewhereOtherThanTheModelsOwnGoal() {
        exercise();

        String planned = call("plan", "goal", "halfway", "method", "breadth");

        assertThat(planned).contains("found");
        assertThat(call("commit", "plan", "P1")).contains("executed=2");
        assertThat(corridor.reached()).isEqualTo(2);
    }

    /**
     * A model that has been replayed against an empty ledger has been tested by nothing, so every
     * rule a plan inside it turns on is one reality has never exercised. The gate runs the first
     * such step and stops, which is how the agent finds out what really happens before it spends
     * the rest of a plan on the strength of an untested rule.
     */
    @Test
    public void aPlanRestingOnRulesNothingHasExercisedIsCutOffAtTheFirstOfThem() {
        call("write_model", "source", CORRIDOR, "note", "an untested theory");
        call("plan");

        String committed = call("commit", "plan", "P1");

        assertThat(committed).contains("truncated").contains("executed=1");
        assertThat(corridor.reached()).isEqualTo(1);
    }

    @Test
    public void aGoalTheModelDoesNotDefineIsRefusedRatherThanSilentlyIgnored() {
        call("write_model", "source", CORRIDOR, "note", "first theory");

        assertThat(call("plan", "goal", "nearly")).startsWith("refused:").contains("nearly");
    }

    /** The one property that makes every other tool safe to call as often as the agent likes. */
    @Test
    public void nothingButCommitAndResetEverAppendsToTheLedger() {
        call("write_model", "source", CORRIDOR, "note", "first theory");
        call("commit", "actions", List.of(move(1)), "note", "a probe");
        int before = toolbox.session().ledger().size();

        for (ToolSchema schema : ToolCatalog.TOOLS) {
            if (schema.name().equals("commit") || schema.name().equals("reset")) {
                continue;
            }
            call(schema.name());
        }

        assertThat(toolbox.session().ledger().size()).isEqualTo(before);
        assertThat(corridor.reached()).isEqualTo(1);
    }

    @Test
    public void everyToolInTheCatalogIsAnsweredBySomething() {
        for (ToolSchema schema : ToolCatalog.TOOLS) {
            String answer = call(schema.name());

            assertThat(answer).as(schema.name()).isNotBlank();
            assertThat(answer).as(schema.name()).doesNotContain("no tool called");
        }
    }

    @Test
    public void committingWithNoActionsAtAllIsRefusedRatherThanCountedAsANoOp() {
        assertThat(call("commit")).startsWith("refused:").contains("actions");
        assertThat(toolbox.session().ledger().size()).isZero();
    }

    @Test
    public void aBlindProbeIsCappedByThePolicyRatherThanByTheAgent() {
        String answer = call("commit", "actions",
                             List.of(move(1), move(1), move(1), move(1)), "note", "too far");

        assertThat(answer).contains("refused");
        assertThat(corridor.reached()).isZero();
    }

    @Test
    public void aSurpriseStopsTheCommitAndTheAnswerSaysWhereItHappened() {
        call("write_model", "source", DOUBLES, "note", "a wrong theory");

        String answer = call("commit", "actions", List.of(move(1), move(1)), "model", "latest");

        assertThat(answer).contains("surprise");
        assertThat(corridor.reached()).isEqualTo(1);
    }

    @Test
    public void resettingStartsANewEpisodeAndSaysSo() {
        call("commit", "actions", List.of(move(1)), "note", "a probe");

        String answer = call("reset", "note", "starting over");

        assertThat(answer).contains("pos");
        assertThat(corridor.reached()).isZero();
        assertThat(toolbox.session().ledger().tail(1).get(0).isReset()).isTrue();
    }

    @Test
    public void simulateShowsWhatTheModelExpectsWithoutTouchingTheWorld() {
        call("write_model", "source", CORRIDOR, "note", "first theory");

        String answer = call("simulate", "actions", List.of(move(1), move(1)));

        assertThat(answer).contains("pos");
        assertThat(corridor.reached()).isZero();
    }

    @Test
    public void discriminateNamesAnExperimentThatTellsTwoTheoriesApart() {
        String first  = digestOf(CORRIDOR, "one theory");
        String second = digestOf(DOUBLES, "another theory");

        String answer = call("discriminate", "models", List.of(first, second));

        assertThat(answer).contains("move");
        assertThat(corridor.reached()).isZero();
    }

    @Test
    public void discriminatingNeedsMoreThanOneTheoryToTellApart() {
        String only = digestOf(CORRIDOR, "one theory");

        assertThat(call("discriminate", "models", List.of(only))).startsWith("refused:")
                                                                 .contains("two");
    }

    @Test
    public void fitFindsTheRuleTheLedgerActuallySupports() {
        call("commit", "actions", List.of(move(1), move(1), move(1)), "note", "a probe");
        call("write_model", "source", CORRIDOR, "note", "first theory");

        String answer = call("fit_predicate", "features", "features");

        assertThat(answer).contains("pos");
    }

    @Test
    public void aFeatureFunctionTheModelDoesNotDefineIsRefused() {
        call("write_model", "source", CORRIDOR, "note", "first theory");

        assertThat(call("fit_predicate", "features", "colours")).startsWith("refused:")
                                                                .contains("colours");
    }

    @Test
    public void aBeliefAndItsRefutationAreBothKept() {
        String added = call("belief_add", "text", "every move goes one place",
                            "evidence", List.of(0), "tags", List.of("corridor"));

        assertThat(added).contains("every move goes one place");

        String id = toolbox.session().beliefs().all().get(0).id();

        assertThat(call("belief_refute", "id", id, "evidence", List.of(0), "reason", "it did not"))
                .contains(id);
        assertThat(call("beliefs")).contains("every move goes one place");
    }

    @Test
    public void aQuestionIsRecordedAndLaterAnswered() {
        call("belief_question", "text", "does the wall move", "tags", List.of("corridor"));
        String id = toolbox.session().beliefs().all().get(0).id();

        assertThat(call("belief_resolve", "id", id, "answer", "it does not")).contains(id);
        assertThat(call("beliefs")).contains("it does not");
    }

    @Test
    public void notesSurviveBetweenCallsAndComeBackFromTheEnd() {
        call("notes_append", "text", "the corridor is one dimensional");
        call("notes_append", "text", "the exit is at three");

        assertThat(call("notes_read")).contains("one dimensional").contains("exit is at three");
    }

    @Test
    public void everyVersionEverWrittenCanBeListedAndReadBack() {
        String first = digestOf(CORRIDOR, "one theory");

        assertThat(call("models")).contains(first).contains("one theory");
        assertThat(call("model_source", "model", first)).contains("fn is_goal");
    }

    @Test
    public void aVersionNobodyWroteIsRefusedWithWhatIsThere() {
        digestOf(CORRIDOR, "one theory");

        assertThat(call("model_source", "model", "beefbeef")).startsWith("refused:");
    }

    @Test
    public void theBudgetCountsEveryToolCallIncludingTheRefusedOnes() {
        call("observe");
        call("ledger_get");

        assertThat(toolbox.session().budget().spend().toolCalls()).isEqualTo(2);
        assertThat(call("budget")).contains("tool");
    }

    @Test
    public void everyCallIsWrittenDownWithWhatItWasAnsweredWith() {
        call("observe");
        call("ledger_get");

        List<ToolCall> log = toolbox.log();

        assertThat(log).hasSize(2);
        assertThat(log.get(0).tool()).isEqualTo("observe");
        assertThat(log.get(1).refused()).isTrue();
    }
}
