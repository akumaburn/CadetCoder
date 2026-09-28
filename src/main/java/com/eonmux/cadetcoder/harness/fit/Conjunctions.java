package com.eonmux.cadetcoder.harness.fit;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every conjunction of atoms the budget allows, grouped by which examples it covers.
 *
 * <h2>Why extensions rather than formulas</h2>
 *
 * <p>Two predicates that cover the same examples are the same predicate as far as this data goes.
 * Grouping by the covered set therefore does three jobs at once: it scores each group once instead
 * of once per formula, it makes "indistinguishable on this data" an exact test rather than a
 * heuristic, and it stops the result from listing a hundred restatements of one answer.</p>
 *
 * <h2>Why a longer conjunction with nothing new to say is dropped</h2>
 *
 * <p>Adding an atom that is already implied covers exactly the same examples, so every predicate
 * has an unbounded number of longer forms. A conjunction is kept only when no strictly shorter one
 * already found covers the same examples, which leaves the shortest statement of each distinct
 * predicate and the genuinely different formulations of the same length beside it.</p>
 */
final class Conjunctions {

    /** How many equivalent formulations are kept for one candidate. */
    private static final int EQUIVALENTS_KEPT = 8;

    /** The candidates, best first, and whether the search ran out of budget before finishing. */
    record Found(List<Candidate> candidates, boolean gaveUp) {
    }

    private Conjunctions() {
    }

    /**
     * Searches the conjunctions.
     *
     * @param atoms    what conjunctions are built from
     * @param examples what they are scored against
     * @param limits   how far the search may go
     * @return what it found
     */
    static Found search(List<Atom> atoms, List<Example> examples, FitLimits limits) {
        int    size      = examples.size();
        BitSet positives = new BitSet(size);
        for (int i = 0; i < size; i++) {
            if (examples.get(i).label()) {
                positives.set(i);
            }
        }
        List<BitSet> extensions = extensions(atoms, examples, size);
        // Keys are freshly built and never touched again, which is what lets a mutable BitSet be
        // one: a key that could change would take its entry out of reach of the map that holds it.
        Map<BitSet, List<int[]>> byExtension = new LinkedHashMap<>();
        long                     tested      = 0;
        boolean                  gaveUp      = false;
        for (int width = 1; width <= limits.maxAtoms() && width <= atoms.size() && !gaveUp; width++) {
            int[] pick = new int[width];
            for (int i = 0; i < width; i++) {
                pick[i] = i;
            }
            do {
                if (++tested > limits.maxCombinations()) {
                    gaveUp = true;
                    break;
                }
                consider(pick, extensions, positives, byExtension);
            } while (advance(pick, atoms.size()));
        }
        return new Found(ranked(byExtension, positives, size, atoms), gaveUp);
    }

    private static List<BitSet> extensions(List<Atom> atoms, List<Example> examples, int size) {
        List<BitSet> extensions = new ArrayList<>();
        for (Atom atom : atoms) {
            BitSet covered = new BitSet(size);
            for (int i = 0; i < size; i++) {
                if (atom.holds(examples.get(i).features())) {
                    covered.set(i);
                }
            }
            extensions.add(covered);
        }
        return extensions;
    }

    private static void consider(int[] pick, List<BitSet> extensions, BitSet positives,
                                 Map<BitSet, List<int[]>> byExtension) {
        BitSet covered = (BitSet) extensions.get(pick[0]).clone();
        for (int i = 1; i < pick.length; i++) {
            covered.and(extensions.get(pick[i]));
        }
        if (!covered.intersects(positives)) {
            return;
        }
        List<int[]> already = byExtension.get(covered);
        if (already == null) {
            List<int[]> first = new ArrayList<>();
            first.add(pick.clone());
            byExtension.put(covered, first);
            return;
        }
        for (int[] shorter : already) {
            if (inside(shorter, pick)) {
                return;
            }
        }
        already.add(pick.clone());
    }

    /** Whether one ascending selection is a strictly smaller part of another. */
    private static boolean inside(int[] part, int[] whole) {
        if (part.length >= whole.length) {
            return false;
        }
        int at = 0;
        for (int index : whole) {
            if (at < part.length && part[at] == index) {
                at++;
            }
        }
        return at == part.length;
    }

    /** Steps an ascending selection to the next one, or reports that there is no next one. */
    private static boolean advance(int[] pick, int atoms) {
        int width = pick.length;
        int at    = width - 1;
        while (at >= 0 && pick[at] == atoms - width + at) {
            at--;
        }
        if (at < 0) {
            return false;
        }
        pick[at]++;
        for (int i = at + 1; i < width; i++) {
            pick[i] = pick[i - 1] + 1;
        }
        return true;
    }

    private static List<Candidate> ranked(Map<BitSet, List<int[]>> byExtension, BitSet positives,
                                          int size, List<Atom> atoms) {
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<BitSet, List<int[]>> entry : byExtension.entrySet()) {
            BitSet covered = entry.getKey();
            BitSet right   = (BitSet) covered.clone();
            right.and(positives);
            int truePositives  = right.cardinality();
            int falsePositives = covered.cardinality() - truePositives;
            int falseNegatives = positives.cardinality() - truePositives;
            int trueNegatives  = size - truePositives - falsePositives - falseNegatives;

            List<int[]> forms = new ArrayList<>(entry.getValue());
            forms.sort(Comparator.<int[]>comparingInt(form -> form.length)
                                 .thenComparing(form -> Candidate.predicateOf(chosen(form, atoms))));
            List<String> equivalents = new ArrayList<>();
            for (int i = 1; i < forms.size() && equivalents.size() < EQUIVALENTS_KEPT; i++) {
                equivalents.add(Candidate.predicateOf(chosen(forms.get(i), atoms)));
            }
            candidates.add(new Candidate(chosen(forms.get(0), atoms), truePositives, falsePositives,
                                         falseNegatives, trueNegatives, equivalents,
                                         forms.size() - 1));
        }
        candidates.sort(Comparator.comparingInt(Candidate::mistakes)
                                  .thenComparingInt(candidate -> candidate.atoms().size())
                                  .thenComparingInt(candidate -> -candidate.truePositives())
                                  .thenComparing(Candidate::predicate));
        return candidates;
    }

    private static List<Atom> chosen(int[] pick, List<Atom> atoms) {
        List<Atom> conjunction = new ArrayList<>();
        for (int index : pick) {
            conjunction.add(atoms.get(index));
        }
        return conjunction;
    }
}
