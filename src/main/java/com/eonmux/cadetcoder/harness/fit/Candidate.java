package com.eonmux.cadetcoder.harness.fit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One predicate the data admits, with the score that admitted it and the predicates it cannot be
 * told apart from.
 *
 * <h2>Why the indistinguishable ones are carried rather than discarded</h2>
 *
 * <p>Two predicates that agree on every example the agent has seen are the same predicate as far as
 * this data goes, and picking one of them is a guess presented as a result. Carrying them turns the
 * ambiguity into the agent's next move: they disagree somewhere, and finding where is an
 * experiment. A fit that silently returned the alphabetically first formulation would have hidden
 * the most useful thing it learned.</p>
 *
 * @param atoms            the conjunction, which holds when every atom holds
 * @param truePositives    positives it covers
 * @param falsePositives   negatives it covers
 * @param falseNegatives   positives it misses
 * @param trueNegatives    negatives it misses
 * @param equivalents      other formulations with the same extension, capped for readability
 * @param equivalentCount  how many there are in total
 */
public record Candidate(List<Atom> atoms, int truePositives, int falsePositives,
                        int falseNegatives, int trueNegatives, List<String> equivalents,
                        int equivalentCount) {

    /** How many equivalent formulations one line of a report shows. */
    public static final int EQUIVALENTS_SHOWN = 4;

    public Candidate {
        atoms       = List.copyOf(atoms);
        equivalents = List.copyOf(equivalents);
    }

    /** Whether it gets every example right. */
    public boolean perfect() {
        return falsePositives == 0 && falseNegatives == 0;
    }

    /** How many examples it gets wrong. */
    public int mistakes() {
        return falsePositives + falseNegatives;
    }

    /** The conjunction as it is written. */
    public String predicate() {
        return predicateOf(atoms);
    }

    /** A conjunction as it is written, for callers holding the atoms before the candidate exists. */
    public static String predicateOf(List<Atom> atoms) {
        List<String> written = new ArrayList<>();
        for (Atom atom : atoms) {
            written.add(atom.render());
        }
        return String.join(" and ", written);
    }

    /** The predicate, its score, and what the data cannot tell it apart from. */
    public String render() {
        String line = predicate() + "   [TP=" + truePositives + " FP=" + falsePositives
                      + " FN=" + falseNegatives + " TN=" + trueNegatives + "]";
        if (equivalentCount == 0) {
            return line;
        }
        List<String> shown = equivalents.subList(0, Math.min(EQUIVALENTS_SHOWN,
                                                             equivalents.size()));
        String more = equivalentCount > shown.size()
                      ? " (+" + (equivalentCount - shown.size()) + " more)" : "";
        return line + System.lineSeparator() + "      indistinguishable on this data from: "
               + String.join("; ", shown) + more;
    }

    /** The candidate as a value the harness can keep. */
    public Map<String, Object> toValue() {
        List<Object> written = new ArrayList<>();
        for (Atom atom : atoms) {
            written.add(atom.toValue());
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("predicate", predicate());
        value.put("atoms", written);
        value.put("tp", truePositives);
        value.put("fp", falsePositives);
        value.put("fn", falseNegatives);
        value.put("tn", trueNegatives);
        value.put("perfect", perfect());
        value.put("equivalents", List.copyOf(equivalents));
        value.put("equivalent_count", equivalentCount);
        return value;
    }

    @Override
    public String toString() {
        return render();
    }
}
