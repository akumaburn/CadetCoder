package com.eonmux.cadetcoder.harness.model.lang;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A green replay only proves the rules reality happened to exercise.
 *
 * <p>The failure: a model passes its backtest, the agent plans on
 * it, and the plan turns on a rule the ledger never tested -- a branch for a door that was never
 * opened, a case for a file that was never missing. The replay had nothing to say about it, so its
 * silence read as agreement.</p>
 *
 * <p>The fix is to count. Every branch a model states is enumerated when it is parsed and marked
 * when it is taken, so "certified" can be qualified by "and here is what was never put to the
 * test", and the planner can stop a plan at the first step that turns on an untested rule.</p>
 */
public class EveryRuleAModelStatesIsCountedTest {

    private static final String SOURCE = """
            fn step(state, action) {
                if (action.type == "move") {
                    return {"pos": state.pos + 1};
                }
                if (action.type == "dig") {
                    return {"pos": state.pos, "hole": true};
                }
                return state;
            }
            """;

    private static Interpreter interpreter(String source) {
        return new Interpreter(Program.parse(source), ExecutionLimits.standard());
    }

    private static Map<String, Object> action(String type) {
        return Map.of("type", type);
    }

    private static Map<String, Object> state() {
        return Map.of("pos", 0);
    }

    @Test
    public void everyBranchIsEnumeratedWhenTheModelIsParsed() {
        List<Arm> arms = Program.parse(SOURCE).arms();

        assertThat(arms).extracting(Arm::id).containsExactly(
                "fn:step@1:1",
                "if@2:5/T", "if@2:5/F",
                "if@5:5/T", "if@5:5/F");
    }

    @Test
    public void anArmKnowsWhereItIsAndWhatItSays() {
        Arm branch = Program.parse(SOURCE).arms().stream()
                .filter(arm -> arm.id().equals("if@5:5/T"))
                .findFirst()
                .orElseThrow();

        assertThat(branch.line()).isEqualTo(5);
        assertThat(branch.snippet()).contains("action.type == \"dig\"");
        assertThat(branch.kind()).isEqualTo("if");
    }

    @Test
    public void takingABranchMarksItAndLeavesTheOtherUnmarked() {
        Interpreter interpreter = interpreter(SOURCE);
        interpreter.call("step", List.of(state(), action("move")));

        Coverage coverage = interpreter.coverage();

        assertThat(coverage.hit()).containsExactlyInAnyOrder("fn:step@1:1", "if@2:5/T");
        assertThat(coverage.uncovered()).extracting(Arm::id)
                .containsExactlyInAnyOrder("if@2:5/F", "if@5:5/T", "if@5:5/F");
        assertThat(coverage.ratio()).isEqualTo(2.0 / 5.0);
    }

    @Test
    public void coverageAccumulatesAcrossACompleteReplay() {
        Interpreter interpreter = interpreter(SOURCE);
        interpreter.call("step", List.of(state(), action("move")));
        interpreter.call("step", List.of(state(), action("dig")));
        interpreter.call("step", List.of(state(), action("wait")));

        assertThat(interpreter.coverage().uncovered())
                .as("every branch was put to the test at least once")
                .isEmpty();
        assertThat(interpreter.coverage().ratio()).isEqualTo(1.0);
    }

    /** A loop nobody entered is a rule nobody tested. */
    @Test
    public void aLoopBodyThatNeverRanIsUncovered() {
        String source = """
                fn count(items) {
                    let n = 0;
                    for (item in items) { n = n + 1; }
                    while (n > 100) { n = n - 1; }
                    return n;
                }
                """;

        Interpreter interpreter = interpreter(source);
        interpreter.call("count", List.of(List.of(1, 2)));

        assertThat(interpreter.coverage().hit())
                .contains("for@3:5/T", "for@3:5/F", "while@4:5/F");
        assertThat(interpreter.coverage().uncovered()).extracting(Arm::id)
                .containsExactly("while@4:5/T");
    }

    @Test
    public void aConditionalExpressionHasTwoArmsLikeAnyOtherBranch() {
        String source = "fn pick(n) { return n > 0 ? \"up\" : \"down\"; }";

        Interpreter interpreter = interpreter(source);
        interpreter.call("pick", List.of(1.0));

        assertThat(interpreter.coverage().hit()).contains("ifexp@1:21/T");
        assertThat(interpreter.coverage().uncovered()).extracting(Arm::id)
                .containsExactly("ifexp@1:21/F");
    }

    /** A short circuit is a branch: the right-hand rule was never reached. */
    @Test
    public void ashortCircuitedOperandIsAnUncoveredArm() {
        String source = "fn safe(s) { return has(s, \"pos\") && s.pos > 0; }";

        Interpreter interpreter = interpreter(source);
        interpreter.call("safe", List.of(Map.of()));

        assertThat(interpreter.coverage().hit()).contains("and@1:21/F");
        assertThat(interpreter.coverage().uncovered()).extracting(Arm::id)
                .containsExactly("and@1:21/T");
    }

    @Test
    public void aFunctionThatWasNeverCalledIsUncovered() {
        String source = """
                fn helper(n) { return n; }
                fn main(n) { return n; }
                """;

        Interpreter interpreter = interpreter(source);
        interpreter.call("main", List.of(1.0));

        assertThat(interpreter.coverage().uncovered()).extracting(Arm::id)
                .containsExactly("fn:helper@1:1");
    }

    @Test
    public void aModelWithoutBranchesIsFullyCoveredOnceItRuns() {
        Interpreter interpreter = interpreter("fn parse(obs) { return obs; }");
        interpreter.call("parse", List.of(Map.of()));

        assertThat(interpreter.coverage().ratio()).isEqualTo(1.0);
        assertThat(interpreter.coverage().render()).contains("1/1");
    }

    @Test
    public void coverageCanBeReportedForAReader() {
        Interpreter interpreter = interpreter(SOURCE);
        interpreter.call("step", List.of(state(), action("move")));

        String report = interpreter.coverage().render();

        assertThat(report).contains("2/5");
        assertThat(report).contains("if@5:5/T");
        assertThat(report).contains("line 5");
    }

    /** Coverage measured against one ledger must not leak into the next. */
    @Test
    public void coverageStartsEmptyForEachInterpreter() {
        Interpreter first = interpreter(SOURCE);
        first.call("step", List.of(state(), action("move")));

        assertThat(interpreter(SOURCE).coverage().hit()).isEmpty();
    }
}
