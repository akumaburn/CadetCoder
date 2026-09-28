package com.eonmux.cadetcoder.harness.spec;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a prediction fails, the report has to say what was wrong with it.
 *
 * <p>A halt is only useful if the next thing the agent does is informed by it. "The model was
 * contradicted" sends it back to guessing; "hud.score: expected 7 actual 9" is the observation that
 * makes the next model better. The one case where naming every difference makes things worse is a
 * grid, where a shifted row is thousands of differences and none of them is the point, so that gets
 * counted and sampled instead of enumerated.</p>
 */
public class ADifferenceIsReportedWhereItHappenedTest {

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static List<Object> grid(int size, int markedRow) {
        List<Object> rows = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            List<Object> row = new ArrayList<>();
            for (int x = 0; x < size; x++) {
                row.add(y == markedRow ? 1 : 0);
            }
            rows.add(row);
        }
        return rows;
    }

    @Test
    public void aPredictionThatMatchesExactlyHolds() {
        PredictionOutcome outcome = Prediction.check(map("pos", 1), map("pos", 1.0));

        assertThat(outcome.kind()).isEqualTo(PredictionKind.EXACT);
        assertThat(outcome.held()).isTrue();
        assertThat(outcome.violations()).isEmpty();
    }

    @Test
    public void anExactMismatchNamesThePathAndBothValues() {
        PredictionOutcome outcome = Prediction.check(map("hud", map("score", 7)),
                                                     map("hud", map("score", 9)));

        assertThat(outcome.kind()).isEqualTo(PredictionKind.EXACT);
        assertThat(outcome.held()).isFalse();
        assertThat(outcome.violations()).hasSize(1);
        assertThat(outcome.violations().get(0).name()).isEqualTo("exact match");
        assertThat(outcome.violations().get(0).detail())
                .contains("hud.score").contains("7").contains("9");
    }

    @Test
    public void anExpectationIsCheckedConstraintByConstraint() {
        PredictionOutcome outcome = Prediction.check(Expect.anything().eq("rc", 0),
                                                     map("rc", 1));

        assertThat(outcome.kind()).isEqualTo(PredictionKind.CONSTRAINT);
        assertThat(outcome.held()).isFalse();
        assertThat(outcome.violations()).extracting(Violation::name).containsExactly("rc == 0");
    }

    @Test
    public void aMissingFieldIsADifferenceOnBothSides() {
        String report = Difference.between(map("a", 1, "b", 2), map("a", 1, "c", 3)).render();

        assertThat(report).contains("b").contains("c");
        assertThat(Difference.between(map("a", 1, "b", 2), map("a", 1, "c", 3)).count())
                .isEqualTo(2);
    }

    @Test
    public void listsThatAreDifferentLengthsSaySo() {
        Difference difference = Difference.between(List.of(1, 2, 3), List.of(1, 2));

        assertThat(difference.count()).isPositive();
        assertThat(difference.render()).contains("length");
    }

    @Test
    public void identicalValuesHaveNoDifferences() {
        Difference difference = Difference.between(map("a", List.of(1, 2)), map("a", List.of(1, 2)));

        assertThat(difference.count()).isZero();
        assertThat(difference.render()).contains("no difference");
    }

    /** A grid is counted, not enumerated: a shifted row is not sixty-four findings. */
    @Test
    public void aGridIsSummarisedRatherThanEnumerated() {
        Difference difference = Difference.between(grid(16, 3), grid(16, 4));

        assertThat(difference.count()).isEqualTo(32);
        assertThat(difference.render()).contains("cells differ");
        assertThat(difference.samples().size()).as("a grid reports a summary, not a sample per cell")
                .isLessThanOrEqualTo(Difference.SAMPLE_LIMIT);
    }

    @Test
    public void aLongListOfDifferencesIsCappedSoTheReportStaysReadable() {
        Map<String, Object> expected = new LinkedHashMap<>();
        Map<String, Object> actual   = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            expected.put("k" + i, i);
            actual.put("k" + i, i + 1);
        }

        Difference difference = Difference.between(expected, actual);

        assertThat(difference.count()).isEqualTo(40);
        assertThat(difference.samples()).hasSize(Difference.SAMPLE_LIMIT);
    }

    @Test
    public void aPredictionReadsBackShortEnoughToLogEitherWay() {
        assertThat(Prediction.describe(Expect.anything().eq("rc", 0)))
                .isEqualTo("expect[rc == 0]");
        assertThat(Prediction.describe(map("pos", 1))).isEqualTo("{\"pos\":1}");
        assertThat(Prediction.describe(map("output", "x".repeat(500))).length())
                .isLessThanOrEqualTo(Prediction.DESCRIPTION_LENGTH + "...".length());
    }
}
