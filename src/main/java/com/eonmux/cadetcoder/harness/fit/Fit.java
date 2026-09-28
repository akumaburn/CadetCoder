package com.eonmux.cadetcoder.harness.fit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Fitting the predicate to the data, instead of guessing predicates and testing them one at a time.
 *
 * <h2>Why this is a harness tool and not a prompt</h2>
 *
 * <p>An agent that suspects a rule -- what counts as a win, when a command fails, which files a
 * build touches -- can spend its whole budget proposing one rule per turn and watching most of them
 * die. The alternative is arithmetic: describe every state already in the ledger as features, label
 * them, and enumerate the predicates that separate the labels. The space is finite and small, the
 * search takes milliseconds, and the answer is not a guess, so it does not cost a turn to find out
 * that it was wrong.</p>
 *
 * <h2>Why the result argues against itself</h2>
 *
 * <p>The dangerous outcome is not failing to find a separator; it is finding one that is an artefact
 * of four positive examples. So a fit reports what it could not do as loudly as what it could: how
 * thin the evidence was, which features it had to thin, how many conjunctions it never tested, and
 * which other predicates the data cannot tell its answer apart from. That last one is not a caveat
 * but a lead: predicates that agree everywhere here disagree somewhere else, and finding where is
 * an experiment.</p>
 */
public final class Fit {

    /**
     * How many positives a separator needs, beyond the size of the conjunction, before it is worth
     * believing. A conjunction of two atoms can pick out any two examples by accident.
     */
    private static final int MARGIN = 1;

    private Fit() {
    }

    /**
     * Fits a predicate to labelled examples, at the standard cost.
     *
     * @param examples the states, described and labelled
     * @return the predicates the data admits, and why they might be less than they look
     */
    public static FitResult predicate(List<Example> examples) {
        return predicate(examples, FitLimits.standard());
    }

    /**
     * Fits a predicate to labelled examples.
     *
     * @param examples the states, described and labelled
     * @param limits   how much of the predicate space to look at
     * @return the predicates the data admits, and why they might be less than they look
     */
    public static FitResult predicate(List<Example> examples, FitLimits limits) {
        if (examples == null) {
            throw new IllegalArgumentException("there are no examples to fit a predicate to");
        }
        int positives = 0;
        for (Example example : examples) {
            if (example.label()) {
                positives++;
            }
        }
        int          negatives = examples.size() - positives;
        List<String> warnings  = new ArrayList<>();
        if (positives == 0) {
            warnings.add("no positive examples: there is nothing to fit");
            return new FitResult(0, negatives, List.of(), warnings);
        }
        if (negatives == 0) {
            warnings.add("no negative examples: every predicate that holds on the positives fits");
        }
        if (positives <= limits.maxAtoms() + MARGIN) {
            warnings.add("only " + positives + " positive example" + (positives == 1 ? "" : "s")
                         + " against conjunctions of up to " + limits.maxAtoms()
                         + " atoms: a perfect separator is likely coincidental");
        }
        Atoms.Harvest      harvest = Atoms.from(examples, limits);
        Conjunctions.Found found   = Conjunctions.search(harvest.atoms(), examples, limits);
        warnings.addAll(harvest.notes());
        if (found.gaveUp()) {
            warnings.add("the search stopped after " + limits.maxCombinations()
                         + " combinations without finishing; a predicate it never tested may fit"
                         + " better than any of these");
        }
        List<Candidate> ranked = found.candidates()
                                      .subList(0, Math.min(limits.maxCandidates(),
                                                           found.candidates().size()));
        warnings.addAll(underdetermination(ranked));
        return new FitResult(positives, negatives, ranked, warnings);
    }

    /**
     * Describes states as features and labels them.
     *
     * @param states   the states to describe
     * @param labels   which side of the question each state is on
     * @param features what to say about a state
     * @return the examples
     * @throws IllegalArgumentException if the states and labels do not line up, or the feature
     *                                  function does not describe a state
     */
    public static List<Example> examples(List<?> states, List<Boolean> labels,
                                         Function<Object, Map<String, Object>> features) {
        if (states == null || labels == null || features == null) {
            throw new IllegalArgumentException("states, labels and a features function are all "
                                               + "needed to build examples");
        }
        if (states.size() != labels.size()) {
            throw new IllegalArgumentException("there are " + states.size() + " states but "
                                               + labels.size() + " label"
                                               + (labels.size() == 1 ? "" : "s")
                                               + "; every state has to be on one side or the other");
        }
        List<Example> examples = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            Map<String, Object> described = features.apply(states.get(i));
            if (described == null) {
                throw new IllegalArgumentException("features() said nothing about state " + i
                                                   + "; it has to describe a state as named values");
            }
            examples.add(new Example(described, Boolean.TRUE.equals(labels.get(i))));
        }
        return List.copyOf(examples);
    }

    private static List<String> underdetermination(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            if (candidate.perfect() && candidate.equivalentCount() > 0) {
                return List.of("the data underdetermines the predicate: "
                               + candidate.equivalentCount() + " other formulation"
                               + (candidate.equivalentCount() == 1 ? "" : "s")
                               + " agree with it on every example here; design an experiment that "
                               + "tells them apart");
            }
        }
        return List.of();
    }
}
