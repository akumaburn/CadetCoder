package com.eonmux.cadetcoder.harness.certify;

import com.eonmux.cadetcoder.harness.model.lang.Arm;
import com.eonmux.cadetcoder.harness.model.lang.Coverage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a replay of the whole ledger through one model established.
 *
 * <h2>The sentence a certificate says</h2>
 *
 * <p>Model H, replayed over everything that really happened up to ledger head L, reproduced every
 * transition -- and these are the rules of H that reality actually put to the test. The second half
 * is what a backtest leaves out, and it is the half that matters when a plan is about to be
 * committed: a rule the ledger never exercised has never been checked, however green the run.</p>
 *
 * <h2>Why staleness is asked rather than stored</h2>
 *
 * <p>A certificate is about the ledger it replayed. Whether that is still the ledger there is now is
 * a question about the present, so {@link #covers} takes the current head rather than a flag being
 * set on a certificate after the fact -- a flag nothing would set if the agent forgot to.</p>
 *
 * @param modelDigest     which model was replayed
 * @param ledgerHead      the hash the ledger stood at
 * @param ledgerLength    how many transitions it held
 * @param checked         how many transitions the model was answerable for
 * @param ok              how many of them held
 * @param mismatched      how many did not, including the ones the model could not answer at all
 * @param drifted         how many times the rule and the representation disagreed
 * @param resets          how many episode starts were re-grounded rather than predicted
 * @param reports         what went wrong, capped so a certificate stays readable
 * @param coverage        which of the model's rules the replay exercised
 * @param strictGrounding whether drift was fatal for this run
 * @param elapsedMillis   how long the replay took
 * @param finalState      the rolled state at the ledger head, which planning starts from
 */
public record Certificate(String modelDigest, String ledgerHead, int ledgerLength,
                          int checked, int ok, int mismatched, int drifted, int resets,
                          List<TransitionReport> reports, Coverage coverage,
                          boolean strictGrounding, long elapsedMillis, Object finalState) {

    /** How much of the ledger head a summary shows. */
    private static final int HEAD_SHOWN = 8;

    public Certificate {
        reports = List.copyOf(reports);
    }

    /** Whether the model survived the whole ledger. */
    public boolean green() {
        return mismatched == 0 && (!strictGrounding || drifted == 0);
    }

    /**
     * Whether this certificate is still about the ledger there is now.
     *
     * @param head the ledger's current head
     * @return whether nothing has happened since the replay
     */
    public boolean covers(String head) {
        return ledgerHead.equals(head);
    }

    /**
     * The same certificate, carried forward over transitions that were checked as they happened.
     *
     * <h2>Why this is not cheating</h2>
     *
     * <p>A certificate is a claim about a replay, and re-running the whole replay after every
     * commit is how it stays honest. But the steps a commit just took were checked live against the
     * same model, by the same check, and they held -- so replaying them would ask a question that
     * has already been answered, at a cost that grows with the ledger. Extending is only sound
     * because it is refused in exactly the case where it would be wrong: a step that did not hold
     * ends the commit, and the certificate is left where it was, no longer covering the head.</p>
     *
     * @param head       the ledger's new head
     * @param length     how many transitions it now holds
     * @param steps      how many of them were checked live and held
     * @param armsHit    the rules those steps exercised
     * @param finalState the rolled state at the new head
     * @return the advanced certificate
     */
    public Certificate extended(String head, int length, int steps, Set<String> armsHit,
                                Object finalState) {
        Set<String> hit = new TreeSet<>(coverage.hit());
        hit.addAll(armsHit);
        return new Certificate(modelDigest, head, length, checked + steps, ok + steps, mismatched,
                               drifted, resets, reports, new Coverage(coverage.arms(), hit),
                               strictGrounding, elapsedMillis, finalState);
    }

    /** The certificate as it is stored and read back. */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("green", green());
        value.put("model", modelDigest);
        value.put("ledger_head", ledgerHead);
        value.put("ledger_length", ledgerLength);
        value.put("checked", checked);
        value.put("ok", ok);
        value.put("mismatched", mismatched);
        value.put("drifted", drifted);
        value.put("resets", resets);
        value.put("strict_grounding", strictGrounding);
        value.put("elapsed_ms", elapsedMillis);
        value.put("reports", reported());
        value.put("coverage", coverageValue());
        value.put("final_state", finalState);
        return value;
    }

    private List<Object> reported() {
        List<Object> written = new ArrayList<>();
        for (TransitionReport report : reports) {
            written.add(report.toValue());
        }
        return written;
    }

    private Map<String, Object> coverageValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("covered", coverage.covered());
        value.put("total", coverage.arms().size());
        value.put("hit", new ArrayList<Object>(new TreeSet<>(coverage.hit())));
        List<Object> untested = new ArrayList<>();
        for (Arm arm : coverage.uncovered()) {
            untested.add(armValue(arm));
        }
        value.put("uncovered", untested);
        return value;
    }

    private static Map<String, Object> armValue(Arm arm) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", arm.id());
        value.put("kind", arm.kind());
        value.put("line", arm.line());
        value.put("column", arm.column());
        value.put("snippet", arm.snippet());
        return value;
    }

    /** The one line a report leads with. */
    public String summary() {
        return (green() ? "GREEN" : "RED")
               + " model=" + modelDigest
               + " ledger=" + ledgerLength + "@" + ledgerHead.substring(0, Math.min(HEAD_SHOWN, ledgerHead.length()))
               + " checked=" + checked + " ok=" + ok + " mismatch=" + mismatched
               + " drift=" + drifted
               + " coverage=" + coverage.covered() + "/" + coverage.arms().size() + " arms";
    }

    /** The summary, then everything that went wrong. */
    public String render() {
        StringBuilder out = new StringBuilder(summary());
        for (TransitionReport report : reports) {
            out.append(System.lineSeparator()).append("  ").append(report.render());
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return summary();
    }
}
