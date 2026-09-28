package com.eonmux.cadetcoder.harness.spec;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exact match is the right bar for a grid and the wrong one for a repository.
 *
 * <p>A world model is only worth having if it can be contradicted, and the reference harness gets
 * that by predicting the next observation exactly. That works for a deterministic puzzle and fails
 * immediately anywhere else: a build log carries durations, a test run carries a seed, a directory
 * listing carries timestamps. A model asked to predict those exactly is wrong every step for
 * reasons that say nothing about whether it understands the environment, so the harness would
 * either halt constantly or stop checking.</p>
 *
 * <p>The alternative is a prediction made of named constraints. It is still falsifiable -- a
 * violated constraint halts the plan exactly as a mismatched grid would -- but a failure points at
 * the belief that was wrong rather than at a wall of diff.</p>
 */
public class AConstraintPredictionNamesTheBeliefThatFailedTest {

    private static Map<String, Object> run(Object exitCode, String output) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("rc", exitCode);
        observation.put("output", output);
        observation.put("tree", Map.of("hash", "abc123", "files", 12));
        return observation;
    }

    @Test
    public void anExpectationOfNothingIsSatisfiedByAnything() {
        assertThat(Expect.anything().check(run(0, "ok"))).isEmpty();
        assertThat(Expect.anything().isEmpty()).isTrue();
    }

    @Test
    public void constraintsThatHoldReportNothing() {
        Expect expectation = Expect.anything()
                .eq("rc", 0)
                .contains("output", "BUILD SUCCESS")
                .eq("tree.hash", "abc123");

        assertThat(expectation.check(run(0, "... BUILD SUCCESS in 3s"))).isEmpty();
    }

    @Test
    public void aViolationNamesTheConstraintAndWhatWasThereInstead() {
        List<Violation> violations = Expect.anything().eq("rc", 0).check(run(1, "boom"));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).name()).isEqualTo("rc == 0");
        assertThat(violations.get(0).detail()).contains("found 1");
        assertThat(violations.get(0).render()).contains("rc == 0");
    }

    /** Every failed belief is reported, so one halt says everything it knows. */
    @Test
    public void eachFailedConstraintIsReportedSeparatelyAndInOrder() {
        Expect expectation = Expect.anything()
                .eq("rc", 0)
                .contains("output", "BUILD SUCCESS")
                .present("tree.hash");

        assertThat(expectation.check(run(2, "compilation failure")))
                .extracting(Violation::name)
                .containsExactly("rc == 0", "output contains \"BUILD SUCCESS\"");
    }

    @Test
    public void aConstraintOnSomethingThatIsNotThereIsViolatedRatherThanSkipped() {
        assertThat(Expect.anything().eq("hud.score", 7).check(run(0, "ok")))
                .extracting(Violation::name)
                .containsExactly("hud.score == 7");
        assertThat(Expect.anything().eq("hud.score", 7).check(run(0, "ok")).get(0).detail())
                .contains("nothing");
    }

    @Test
    public void presenceAndAbsenceAreConstraintsOfTheirOwn() {
        assertThat(Expect.anything().present("tree.hash").absent("tree.error").check(run(0, "ok")))
                .isEmpty();
        assertThat(Expect.anything().absent("tree.hash").check(run(0, "ok")))
                .extracting(Violation::name)
                .containsExactly("tree.hash absent");
    }

    @Test
    public void numbersCanBeBoundedRatherThanPinned() {
        Map<String, Object> observation = run(0, "ok");

        assertThat(Expect.anything().ge("tree.files", 10).le("tree.files", 20).check(observation))
                .isEmpty();
        assertThat(Expect.anything().gt("tree.files", 12).check(observation))
                .extracting(Violation::name)
                .containsExactly("tree.files > 12");
        assertThat(Expect.anything().lt("tree.files", 20).ne("rc", 1).check(observation)).isEmpty();
    }

    @Test
    public void aComparisonAgainstSomethingThatIsNotANumberIsAViolation() {
        assertThat(Expect.anything().gt("output", 3).check(run(0, "ok")))
                .hasSize(1);
    }

    @Test
    public void textCanBeConstrainedByPattern() {
        assertThat(Expect.anything().matches("output", "Tests run: \\d+").check(run(0, "Tests run: 42")))
                .isEmpty();
        assertThat(Expect.anything().matches("output", "Tests run: \\d+").check(run(0, "no tests")))
                .hasSize(1);
    }

    /** {@code where} is the escape hatch, and it still has to be named. */
    @Test
    public void aModelCanStateAConstraintNoBuilderCovers() {
        Expect expectation = Expect.anything()
                .where("output is short", observation -> String.valueOf(observation).length() < 500);

        assertThat(expectation.check(run(0, "ok"))).isEmpty();
        assertThat(expectation.names()).containsExactly("output is short");
    }

    /** A check that blows up has not been satisfied. */
    @Test
    public void aConstraintThatThrowsIsAViolationRatherThanAPass() {
        Expect expectation = Expect.anything().where("impossible", observation -> {
            throw new IllegalStateException("no");
        });

        List<Violation> violations = expectation.check(run(0, "ok"));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).detail()).contains("no");
    }

    @Test
    public void aConstraintCanBeGivenTheNameTheBeliefIsKnownBy() {
        Expect expectation = Expect.anything().eq("rc", 0, "the build passes");

        assertThat(expectation.names()).containsExactly("the build passes");
        assertThat(expectation.check(run(1, "boom")).get(0).name()).isEqualTo("the build passes");
    }

    @Test
    public void anExpectationReadsAsTheBeliefsItIsMadeOf() {
        Expect expectation = Expect.anything().eq("rc", 0).contains("output", "ok");

        assertThat(expectation.describe()).isEqualTo("expect[rc == 0, output contains \"ok\"]");
        assertThat(expectation.size()).isEqualTo(2);
    }

    /** Building one never changes the one it was built from. */
    @Test
    public void addingAConstraintLeavesTheOriginalAlone() {
        Expect first  = Expect.anything().eq("rc", 0);
        Expect second = first.contains("output", "ok");

        assertThat(first.size()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(2);
        assertThat(first.check(run(0, "nothing here"))).isEmpty();
    }
}
