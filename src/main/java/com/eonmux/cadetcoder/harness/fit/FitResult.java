package com.eonmux.cadetcoder.harness.fit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the data admits, what it does not, and what it is not enough to decide.
 *
 * <h2>Why there is at most one perfect separator</h2>
 *
 * <p>Candidates are grouped by extension -- by exactly which examples they cover -- and a predicate
 * is perfect precisely when its extension is the labelling. So the perfect predicates, if there are
 * any, are all one candidate, and the ones beyond the first are its {@code equivalents}. A result
 * that reported several perfect separators would be reporting several names for one answer as
 * though they were rival answers.</p>
 *
 * <h2>Why the warnings are part of the result rather than a log</h2>
 *
 * <p>Every warning here is a reason to distrust the candidates directly beneath it: too few
 * positives, a feature that was thinned, a search that stopped early. An agent reads the result; it
 * does not read the harness's logs, and a caveat it cannot see is a caveat that does not exist.</p>
 *
 * @param positives  how many examples were on the near side of the question
 * @param negatives  how many were on the far side
 * @param candidates the predicates the data admits, best first
 * @param warnings   why the candidates might be less than they look
 */
public record FitResult(int positives, int negatives, List<Candidate> candidates,
                        List<String> warnings) {

    /** How many candidates a summary shows. */
    public static final int SHOWN = 10;

    public FitResult {
        candidates = List.copyOf(candidates);
        warnings   = List.copyOf(warnings);
    }

    /** The best candidate, or {@code null} when the data admits none. */
    public Candidate best() {
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** The candidate that gets every example right, or {@code null} when there is none. */
    public Candidate perfect() {
        for (Candidate candidate : candidates) {
            if (candidate.perfect()) {
                return candidate;
            }
        }
        return null;
    }

    /** The report, showing the standard number of candidates. */
    public String summary() {
        return summary(SHOWN);
    }

    /**
     * The report.
     *
     * @param shown how many candidates to list
     * @return the report
     */
    public String summary(int shown) {
        List<String> lines = new ArrayList<>();
        lines.add("fit: " + positives + " positive / " + negatives + " negative examples");
        for (String warning : warnings) {
            lines.add("  ! " + warning);
        }
        int listed = Math.min(Math.max(shown, 0), candidates.size());
        lines.add("  " + (perfect() == null ? "no perfect separator" : "perfect separator found")
                  + "; showing " + listed + " of " + candidates.size() + " candidates:");
        for (Candidate candidate : candidates.subList(0, listed)) {
            lines.add("  " + (candidate.perfect() ? "* " : "  ") + candidate.render());
        }
        return String.join(System.lineSeparator(), lines);
    }

    /** The result as a value the harness can keep. */
    public Map<String, Object> toValue() {
        List<Object> written = new ArrayList<>();
        for (Candidate candidate : candidates) {
            written.add(candidate.toValue());
        }
        Candidate           perfect = perfect();
        Map<String, Object> value   = new LinkedHashMap<>();
        value.put("positives", positives);
        value.put("negatives", negatives);
        value.put("perfect", perfect == null ? null : perfect.predicate());
        value.put("candidates", written);
        value.put("warnings", List.copyOf(warnings));
        return value;
    }

    @Override
    public String toString() {
        return summary();
    }
}
