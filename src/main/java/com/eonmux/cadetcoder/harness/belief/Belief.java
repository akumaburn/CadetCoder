package com.eonmux.cadetcoder.harness.belief;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One durable claim, with everything that says whether it is still worth acting on.
 *
 * <h2>Why the evidence is ledger indices rather than prose</h2>
 *
 * <p>"I noticed this a while back" is what an agent writes about a claim it cannot support, and it
 * reads exactly like a claim it can. Naming the transitions instead makes the difference checkable:
 * the indices are lines of the ledger, so a reader can go and look, and a claim with none of them is
 * visibly a guess.</p>
 *
 * <h2>Why a change makes a new belief</h2>
 *
 * <p>A belief is handed out to callers and rendered into prompts. If refuting one changed the object
 * a caller was already holding, a report could describe a claim in a state it was never in when the
 * report was made. Every change returns a new belief and the store swaps it in.</p>
 *
 * @param id           what the claim is called, so evidence and refutations can name it
 * @param text         the claim itself
 * @param status       what has become of it
 * @param ledgerLength how long the ledger was when it was made
 * @param evidence     the ledger indices that support it
 * @param refutedBy    the ledger indices that killed it, empty unless it was refuted
 * @param reason       why those indices killed it, {@code null} unless it was refuted
 * @param supersedes   the claim this one replaced, {@code null} when it replaced nothing
 * @param supersededBy the claim that replaced this one, {@code null} unless it was replaced
 * @param tags         what the claim is about, for an agent narrowing a long list
 * @param answer       what answered it, {@code null} unless it was an answered question
 */
public record Belief(String id, String text, BeliefStatus status, int ledgerLength,
                     List<Integer> evidence, List<Integer> refutedBy, String reason,
                     String supersedes, String supersededBy, List<String> tags, String answer) {

    public Belief {
        evidence = List.copyOf(evidence);
        refutedBy = List.copyOf(refutedBy);
        tags = List.copyOf(tags);
    }

    /** The same claim, carrying ledger indices it did not already name. */
    public Belief supported(List<Integer> found) {
        List<Integer> all = new ArrayList<>(evidence);
        for (Integer index : found) {
            if (!all.contains(index)) {
                all.add(index);
            }
        }
        return new Belief(id, text, status, ledgerLength, all, refutedBy, reason, supersedes,
                          supersededBy, tags, answer);
    }

    /** The same claim, killed by what the ledger says. */
    public Belief refuted(List<Integer> killedBy, String why) {
        return new Belief(id, text, BeliefStatus.REFUTED, ledgerLength, evidence, killedBy, why,
                          supersedes, supersededBy, tags, answer);
    }

    /** The same question, answered. */
    public Belief answered(String found, List<Integer> from) {
        return new Belief(id, text, BeliefStatus.RESOLVED, ledgerLength, from, refutedBy, reason,
                          supersedes, supersededBy, tags, found);
    }

    /** The same claim, retired in favour of a better one. */
    public Belief replacedBy(String replacement) {
        return new Belief(id, text, BeliefStatus.SUPERSEDED, ledgerLength, evidence, refutedBy,
                          reason, supersedes, replacement, tags, answer);
    }

    /** The claim as a value the harness can keep. */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("text", text);
        value.put("status", status.label());
        value.put("ledger_length", ledgerLength);
        value.put("evidence", evidence);
        value.put("refuted_by", refutedBy);
        value.put("reason", reason);
        value.put("supersedes", supersedes);
        value.put("superseded_by", supersededBy);
        value.put("tags", tags);
        value.put("answer", answer);
        return value;
    }

    @Override
    public String toString() {
        return id + " [" + status.label() + "] " + text;
    }
}
