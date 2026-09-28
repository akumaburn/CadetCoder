package com.eonmux.cadetcoder.harness.fit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guessing the rule one hypothesis at a time, replaced by computing which rule the data admits.
 *
 * <p>An agent that suspects a win condition can spend its whole budget proposing conditions and
 * testing them one at a time. The cheaper move is to describe every state it has already seen as
 * features, label them, and ask which predicate separates the labels -- because the answer is a
 * search over a finite space the harness can run in milliseconds, and because the same search
 * reports the predicates it <em>cannot</em> tell apart, which is the agent's next experiment.</p>
 *
 * <p>These tests pin the two things that make the answer trustworthy rather than merely
 * confident. A separator found on four positives is probably a coincidence, and the result has to
 * say so. And no single feature may crowd the others out of the search: a step counter with three
 * hundred distinct values generates hundreds of thresholds, and a fit that spends its whole atom
 * budget there will never look at the two booleans that actually decide the label.</p>
 */
public class ThePredicateIsFittedToTheDataRatherThanGuessedTest {

    @Test
    public void aSeparatorIsFoundWhenTheDataAdmitsOne() {
        FitResult result = Fit.predicate(List.of(
                example(false, "pos", 0, "key", false),
                example(true, "pos", 1, "key", true),
                example(false, "pos", 2, "key", false),
                example(true, "pos", 3, "key", true)));

        Candidate best = result.best();
        assertThat(best.predicate()).isEqualTo("key == true");
        assertThat(best.perfect()).isTrue();
        assertThat(best.truePositives()).isEqualTo(2);
        assertThat(best.falsePositives()).isZero();
        assertThat(best.falseNegatives()).isZero();
        assertThat(best.trueNegatives()).isEqualTo(2);
        assertThat(result.positives()).isEqualTo(2);
        assertThat(result.negatives()).isEqualTo(2);
    }

    @Test
    public void aConjunctionIsFoundWhenNoSingleFeatureSeparatesTheLabels() {
        FitResult result = Fit.predicate(List.of(
                example(false, "a", false, "b", false),
                example(false, "a", true, "b", false),
                example(false, "a", false, "b", true),
                example(true, "a", true, "b", true)));

        assertThat(result.best().predicate()).isEqualTo("a == true and b == true");
        assertThat(result.best().perfect()).isTrue();
    }

    @Test
    public void aNumericSeparatorIsFoundAtTheBoundaryTheDataPutsItAt() {
        FitResult result = Fit.predicate(List.of(
                example(false, "pos", 0),
                example(false, "pos", 1),
                example(false, "pos", 2),
                example(true, "pos", 3),
                example(true, "pos", 4)));

        assertThat(result.best().predicate()).isEqualTo("pos >= 3");
        assertThat(result.best().perfect()).isTrue();
    }

    @Test
    public void aCandidateWithFewerMistakesOutranksASimplerOneWithMore() {
        FitResult result = Fit.predicate(List.of(
                example(false, "a", false, "b", false),
                example(false, "a", true, "b", false),
                example(false, "a", false, "b", true),
                example(true, "a", true, "b", true)));

        Candidate best = result.best();
        assertThat(best.atoms()).hasSize(2);
        assertThat(best.falsePositives() + best.falseNegatives()).isZero();
        assertThat(result.candidates().get(1).falsePositives()
                   + result.candidates().get(1).falseNegatives()).isPositive();
    }

    @Test
    public void predicatesTheDataCannotTellApartAreNamedTogetherRatherThanPickedBetween() {
        FitResult result = Fit.predicate(List.of(
                example(false, "a", false, "b", false),
                example(true, "a", true, "b", true),
                example(false, "a", false, "b", false),
                example(true, "a", true, "b", true)));

        Candidate best = result.best();
        assertThat(best.predicate()).isEqualTo("a == true");
        assertThat(best.equivalents()).contains("b == true");
        assertThat(best.equivalentCount()).isEqualTo(1);
    }

    @Test
    public void dataThatUnderdeterminesThePredicateSendsTheAgentToAnExperiment() {
        FitResult result = Fit.predicate(List.of(
                example(false, "a", false, "b", false),
                example(true, "a", true, "b", true),
                example(false, "a", false, "b", false),
                example(true, "a", true, "b", true)));

        assertThat(result.warnings()).anyMatch(warning -> warning.contains("underdetermines")
                                                          && warning.contains("experiment"));
    }

    @Test
    public void aSeparatorRestingOnTooFewPositivesIsCalledACoincidence() {
        FitResult result = Fit.predicate(List.of(
                example(false, "pos", 0),
                example(false, "pos", 1),
                example(true, "pos", 2)));

        assertThat(result.warnings()).anyMatch(warning -> warning.contains("1 positive")
                                                          && warning.contains("coincidental"));
    }

    @Test
    public void thereIsNothingToFitWithoutAPositiveExample() {
        FitResult result = Fit.predicate(List.of(
                example(false, "pos", 0),
                example(false, "pos", 1)));

        assertThat(result.warnings()).anyMatch(warning -> warning.contains("no positive examples"));
        assertThat(result.candidates()).isEmpty();
        assertThat(result.best()).isNull();
    }

    @Test
    public void everyPredicateFitsWhenNothingIsANegativeExample() {
        FitResult result = Fit.predicate(List.of(
                example(true, "pos", 0),
                example(true, "pos", 1)));

        assertThat(result.warnings()).anyMatch(warning -> warning.contains("no negative examples"));
        assertThat(result.candidates()).isNotEmpty();
    }

    @Test
    public void anAtomAboutAFeatureAnExampleDoesNotHaveIsFalseRatherThanVacuouslyTrue() {
        Map<String, Object> nothing = Map.of();
        assertThat(new Atom("a", AtomOp.EQ, 1).holds(nothing)).isFalse();
        assertThat(new Atom("a", AtomOp.NE, 1).holds(nothing)).isFalse();
        assertThat(new Atom("a", AtomOp.GE, 1).holds(nothing)).isFalse();
        assertThat(new Atom("a", AtomOp.LT, 1).holds(nothing)).isFalse();
    }

    @Test
    public void comparingValuesOfDifferentKindsIsFalseRatherThanAFailure() {
        Map<String, Object> text = Map.of("a", "x");
        assertThat(new Atom("a", AtomOp.GE, 1).holds(text)).isFalse();
        assertThat(new Atom("a", AtomOp.LT, 1).holds(text)).isFalse();
        assertThat(new Atom("a", AtomOp.NE, 1).holds(text)).isTrue();
    }

    @Test
    public void aFeatureWithHundredsOfValuesDoesNotCrowdTheDecidingOnesOutOfTheSearch() {
        FitResult result = Fit.predicate(noisy());

        assertThat(result.best().predicate()).isEqualTo("a == true and b == true");
        assertThat(result.best().perfect()).isTrue();
    }

    @Test
    public void aFeatureThinnedToLeaveRoomForTheOthersSaysWhichOneItWas() {
        FitResult result = Fit.predicate(noisy());

        assertThat(result.warnings()).anyMatch(warning -> warning.contains("noise")
                                                          && warning.contains("thinned"));
    }

    @Test
    public void aSearchTooBigToFinishSaysSoRatherThanRunningToTheEndOfTheBudget() {
        FitResult result = Fit.predicate(noisy(), FitLimits.standard().toCombinations(30));

        assertThat(result.warnings()).anyMatch(warning -> warning.contains("combinations"));
    }

    @Test
    public void onlyAsManyCandidatesAsWereAskedForAreReported() {
        FitResult result = Fit.predicate(noisy(), FitLimits.standard().toCandidates(3));

        assertThat(result.candidates()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    public void aConjunctionNeverGrowsPastTheSizeItWasAllowed() {
        FitResult result = Fit.predicate(List.of(
                example(false, "a", false, "b", false, "c", false),
                example(false, "a", true, "b", true, "c", false),
                example(true, "a", true, "b", true, "c", true)),
                                          FitLimits.standard().toAtoms(1));

        assertThat(result.candidates()).allMatch(candidate -> candidate.atoms().size() == 1);
    }

    @Test
    public void theSummaryCountsTheExamplesAndMarksTheSeparatorItFound() {
        String summary = Fit.predicate(List.of(
                example(false, "a", false),
                example(true, "a", true),
                example(false, "a", false),
                example(true, "a", true))).summary();

        assertThat(summary).contains("2 positive / 2 negative examples");
        assertThat(summary).contains("perfect separator");
        assertThat(summary).contains("* a == true");
        assertThat(summary).contains("TP=2 FP=0 FN=0 TN=2");
    }

    @Test
    public void aFitWithNoSeparatorSaysThereIsNoneRatherThanShowingTheBestGuessAsOne() {
        String summary = Fit.predicate(List.of(
                example(true, "a", true),
                example(false, "a", true),
                example(true, "a", false),
                example(false, "a", false))).summary();

        assertThat(summary).contains("no perfect separator");
        assertThat(summary).doesNotContain("* ");
    }

    @Test
    public void examplesAreBuiltFromStatesAndTheFeaturesAModelReadsOffThem() {
        List<Example> examples = Fit.examples(
                List.of(Map.of("pos", 1), Map.of("pos", 5)),
                List.of(false, true),
                state -> Map.of("far", ((Number) ((Map<?, ?>) state).get("pos")).intValue() > 3));

        assertThat(examples).hasSize(2);
        assertThat(examples.get(0).features()).containsEntry("far", false);
        assertThat(examples.get(1).label()).isTrue();
    }

    @Test
    public void statesAndLabelsThatDoNotLineUpAreRefusedRatherThanTruncated() {
        assertThatThrownBy(() -> Fit.examples(List.of(1, 2, 3), List.of(true), state -> Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 states")
                .hasMessageContaining("1 label");
    }

    @Test
    public void aFeatureFunctionThatDoesNotDescribeTheStateIsRefused() {
        assertThatThrownBy(() -> Fit.examples(List.of(1), List.of(true), state -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("features");
    }

    @Test
    public void theResultReadsBackAsAValueTheHarnessCanKeep() {
        Map<String, Object> value = Fit.predicate(List.of(
                example(false, "a", false),
                example(true, "a", true))).toValue();

        assertThat(value).containsKeys("positives", "negatives", "warnings", "candidates",
                                       "perfect");
        assertThat(value.get("positives")).isEqualTo(1);
        List<?> candidates = (List<?>) value.get("candidates");
        assertThat(candidates).isNotEmpty();
        @SuppressWarnings ("unchecked")
        Map<String, Object> first = (Map<String, Object>) candidates.get(0);
        assertThat(first).containsKeys("predicate", "atoms", "tp", "fp", "fn", "tn", "perfect",
                                       "equivalents");
    }

    /**
     * Three hundred examples whose label is decided by two booleans, alongside a counter that takes
     * a different value in every one of them.
     */
    private static List<Example> noisy() {
        List<Example> examples = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            boolean a = i % 2 == 0;
            boolean b = i % 3 == 0;
            examples.add(example(a && b, "noise", i, "a", a, "b", b));
        }
        return examples;
    }

    private static Example example(boolean label, Object... pairs) {
        Map<String, Object> features = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            features.put((String) pairs[i], pairs[i + 1]);
        }
        return new Example(features, label);
    }
}
