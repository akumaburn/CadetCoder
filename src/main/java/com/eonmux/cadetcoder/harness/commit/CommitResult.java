package com.eonmux.cadetcoder.harness.commit;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.certify.Certificate;
import com.eonmux.cadetcoder.harness.plan.PlanAudit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What happened when a plan was put to the world.
 *
 * <h2>Why a refusal is a result and not an exception</h2>
 *
 * <p>Being refused is an ordinary outcome of the loop, and the agent has to read the reason and act
 * on it -- certify again, shorten the plan, ask for approval. An exception would make the one thing
 * the agent most needs to reason about the one thing it cannot see without catching something.</p>
 *
 * <h2>Why how far it got is derived</h2>
 *
 * <p>The ledger indices are the record of what reached the world, so counting them is the only
 * answer that cannot drift from it. A separate tally is a second version of the truth, and the first
 * time an early return forgets to advance it, the result claims a world that does not exist.</p>
 *
 * @param status            how it ended
 * @param ledgerIndices     the transitions this commit wrote, in order
 * @param finalObservation  what could be seen when it stopped
 * @param flags             the verdicts the environment reported on the last step
 * @param surprise          the step the world contradicted, or {@code null}
 * @param reason            why it was refused or stopped, or {@code null}
 * @param truncatedAt       the step whose untested rule cut the plan off, or {@code null}
 * @param audit             which steps of the plan reached rules nothing had exercised
 * @param certificate       the model's certificate as it now stands, or {@code null} with no model
 */
public record CommitResult(CommitStatus status, List<Integer> ledgerIndices,
                           Object finalObservation, Map<String, Object> flags, Surprise surprise,
                           String reason, Integer truncatedAt, PlanAudit audit,
                           Certificate certificate) {

    public CommitResult {
        ledgerIndices = List.copyOf(ledgerIndices);
        flags         = Map.copyOf(flags);
    }

    /** Nothing reached the world, and this is why. */
    static CommitResult refused(String reason) {
        return new CommitResult(CommitStatus.REFUSED, List.of(), null, Map.of(), null, reason, null,
                                PlanAudit.nothing(), null);
    }

    /** The very first step was the experiment, so under a cautious policy nothing ran. */
    static CommitResult nothingRan(int at, PlanAudit audit, Certificate certificate) {
        return new CommitResult(CommitStatus.TRUNCATED_UNTESTED, List.of(), null, Map.of(), null,
                                "the first step exercises untested rules "
                                + audit.untestedArms().get(at)
                                + "; commit it as an experiment to run it anyway",
                                at, audit, certificate);
    }

    /** How many actions reached the world. */
    public int executed() {
        return ledgerIndices.size();
    }

    /** The result as it is stored and read back. */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", status.label());
        value.put("executed", executed());
        value.put("ledger_indices", List.copyOf(ledgerIndices));
        value.put("reason", reason);
        value.put("truncated_at", truncatedAt);
        value.put("flags", Map.copyOf(flags));
        value.put("final_observation", finalObservation);
        value.put("surprise", surprise == null ? null : surprise.toValue());
        value.putAll(audit.toValue());
        value.put("certificate", certificate == null ? null : certificate.toValue());
        return value;
    }

    /** The one line the agent is shown, with whatever it has to act on underneath. */
    public String summary() {
        StringBuilder text = new StringBuilder("commit ").append(status.label())
                                                         .append(": executed=").append(executed());
        if (reason != null) {
            text.append(" reason=").append(reason);
        }
        if (truncatedAt != null) {
            text.append(" truncated_at=").append(truncatedAt)
                .append(" untested=").append(audit.untestedArms().get(truncatedAt));
        }
        if (!flags.isEmpty()) {
            text.append(" flags=").append(Json.canonical(flags));
        }
        if (surprise != null) {
            text.append(System.lineSeparator()).append("  ").append(surprise.render());
        }
        if (certificate != null) {
            text.append(System.lineSeparator()).append("  certificate: ")
                .append(certificate.summary());
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return summary();
    }
}
