package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.spec.Expect;
import com.eonmux.cadetcoder.harness.spec.Violation;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A prediction the model cannot express is a prediction the model will not make.
 *
 * <p>Named constraints are the answer to exact match being the wrong bar outside a puzzle, but they
 * are only an answer if a model can state one. A world model is written in the model language and
 * returns its prediction from {@code predict}; if expectations could only be built from Java, every
 * model an LLM writes would be back to naming the whole next observation -- timestamps, durations
 * and all -- and would be contradicted at every step for reasons that say nothing about whether it
 * understands the environment.</p>
 */
public class AModelCanPredictWhatItKnowsRatherThanEverythingTest {

    private static Map<String, Object> observation(Object exitCode, String output) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("rc", exitCode);
        value.put("output", output);
        return value;
    }

    private static Interpreter interpreter(String source) {
        return new Interpreter(Program.parse(source), ExecutionLimits.standard());
    }

    private static Expect predictionOf(String body) {
        return predictionOf(interpreter("fn predict(state, action) " + body));
    }

    private static Expect predictionOf(Interpreter interpreter) {
        Object prediction = interpreter.call("predict", List.of(Map.of(), Map.of()));
        assertThat(prediction).isInstanceOf(Expect.class);
        return (Expect) prediction;
    }

    @Test
    public void aModelThatKnowsNothingSpecificStillReturnsAPrediction() {
        Expect prediction = predictionOf("{ return expect(); }");

        assertThat(prediction.isEmpty()).isTrue();
        assertThat(prediction.check(observation(0, "ok"))).isEmpty();
    }

    @Test
    public void aModelBuildsAnExpectationOneBeliefAtATime() {
        Expect prediction = predictionOf("""
                {
                    return expect()
                        .eq("rc", 0)
                        .contains("output", "BUILD SUCCESS");
                }
                """);

        assertThat(prediction.names())
                .containsExactly("rc == 0", "output contains \"BUILD SUCCESS\"");
        assertThat(prediction.check(observation(0, "... BUILD SUCCESS in 3s"))).isEmpty();
        assertThat(prediction.check(observation(1, "boom")))
                .extracting(Violation::name)
                .containsExactly("rc == 0", "output contains \"BUILD SUCCESS\"");
    }

    /**
     * A model computes in {@code double} and an observation carries whatever the environment wrote.
     *
     * <p>If those two were different values the whole vocabulary would be useless: every constraint
     * a model stated about a count it had done arithmetic on would fail.</p>
     */
    @Test
    public void aNumberAModelComputedIsTheNumberTheEnvironmentReported() {
        Expect prediction = predictionOf("{ return expect().eq(\"rc\", 6 / 3 - 2); }");

        assertThat(prediction.check(observation(0, "ok"))).isEmpty();
    }

    @Test
    public void everyKindOfBeliefIsAvailableToAModel() {
        Expect prediction = predictionOf("""
                {
                    return expect()
                        .ne("rc", 1)
                        .gt("rc", -1)
                        .ge("rc", 0)
                        .lt("rc", 1)
                        .le("rc", 0)
                        .present("output")
                        .absent("error")
                        .matches("output", "o.");
                }
                """);

        assertThat(prediction.size()).isEqualTo(8);
        assertThat(prediction.check(observation(0, "ok"))).isEmpty();
    }

    @Test
    public void aBeliefCanBeGivenTheNameTheModelKnowsItBy() {
        Expect prediction = predictionOf("{ return expect().eq(\"rc\", 0, \"the build passes\"); }");

        assertThat(prediction.names()).containsExactly("the build passes");
        assertThat(prediction.describe()).isEqualTo("expect[rc == 0]");
    }

    @Test
    public void aModelCanStateABeliefNoBuilderCovers() {
        Expect prediction = predictionOf("""
                {
                    return expect().where("output is short", fn(obs) {
                        return len(obs.output) < 10;
                    });
                }
                """);

        assertThat(prediction.check(observation(0, "ok"))).isEmpty();
        assertThat(prediction.check(observation(0, "a very long line of output")))
                .extracting(Violation::name)
                .containsExactly("output is short");
    }

    /**
     * The predicate runs long after the call that built it returned.
     *
     * <p>{@code predict} is called before the action is taken and the observation it is about only
     * arrives afterwards, so the step budget the model spent building the expectation cannot be the
     * budget the predicate is checked under -- and it has to be checkable more than once.</p>
     */
    @Test
    public void aBeliefCanBeCheckedAfterTheModelCallThatBuiltItHasFinished() {
        Expect prediction = predictionOf("""
                {
                    return expect().where("rc is zero", fn(obs) { return obs.rc == 0; });
                }
                """);

        assertThat(prediction.check(observation(0, "ok"))).isEmpty();
        assertThat(prediction.check(observation(0, "again"))).isEmpty();
        assertThat(prediction.check(observation(3, "no"))).hasSize(1);
    }

    /** A predicate is model code, so it is bounded like model code. */
    @Test
    public void aBeliefThatWillNotStopIsAViolationRatherThanAHang() {
        Interpreter interpreter = new Interpreter(
                Program.parse("""
                        fn predict(state, action) {
                            return expect().where("spins", fn(obs) {
                                while (true) { let n = 1; }
                                return true;
                            });
                        }
                        """),
                new ExecutionLimits(2_000, 1_000));

        List<Violation> violations = predictionOf(interpreter).check(observation(0, "ok"));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).detail()).contains("steps");
    }

    /** A rule inside a predicate is a rule the model stated, so it is counted like any other. */
    @Test
    public void aRuleInsideABeliefIsCountedWhenTheBeliefIsChecked() {
        Interpreter interpreter = interpreter("""
                fn predict(state, action) {
                    return expect().where("rc is zero", fn(obs) {
                        if (has(obs, "rc")) { return obs.rc == 0; }
                        return false;
                    });
                }
                """);
        Expect prediction = predictionOf(interpreter);

        assertThat(interpreter.coverage().uncovered()).isNotEmpty();
        prediction.check(observation(0, "ok"));

        assertThat(interpreter.coverage().hit()).contains("if@3:9/T");
    }

    /** Adding a belief inside a model must not change an expectation the model already holds. */
    @Test
    public void addingABeliefLeavesTheExpectationItWasBuiltFromAlone() {
        Interpreter interpreter = interpreter("""
                fn predict(state, action) {
                    let base = expect().eq("rc", 0);
                    let more = base.contains("output", "ok");
                    return [base, more];
                }
                """);

        List<?> both = (List<?>) interpreter.call("predict", List.of(Map.of(), Map.of()));

        assertThat(((Expect) both.get(0)).size()).isEqualTo(1);
        assertThat(((Expect) both.get(1)).size()).isEqualTo(2);
    }

    @Test
    public void aBeliefTheVocabularyDoesNotHaveIsRefusedWhereItIsWritten() {
        Interpreter interpreter = interpreter("fn predict(s, a) { return expect().roughly(\"rc\", 0); }");

        assertThatThrownBy(() -> interpreter.call("predict", List.of(Map.of(), Map.of())))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("roughly")
                .hasMessageContaining("line 1");
    }

    @Test
    public void aBeliefBuiltWrongIsRefusedWhereItIsWritten() {
        Interpreter interpreter = interpreter("fn predict(s, a) { return expect().eq(\"rc\"); }");

        assertThatThrownBy(() -> interpreter.call("predict", List.of(Map.of(), Map.of())))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("eq");
    }

    @Test
    public void aPathHasToBeTextLikeEveryOtherPath() {
        Interpreter interpreter = interpreter("fn predict(s, a) { return expect().eq(1, 0); }");

        assertThatThrownBy(() -> interpreter.call("predict", List.of(Map.of(), Map.of())))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("needs text");
    }

    @Test
    public void anExpectationIsListedAmongTheThingsAModelCanBuild() {
        assertThat(Builtins.render()).contains("expect");
    }
}
