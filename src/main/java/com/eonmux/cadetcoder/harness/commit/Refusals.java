package com.eonmux.cadetcoder.harness.commit;

import com.eonmux.cadetcoder.harness.certify.Certificate;
import com.eonmux.cadetcoder.harness.env.Environment;
import com.eonmux.cadetcoder.harness.env.Reversibility;
import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import com.eonmux.cadetcoder.harness.plan.PlanAudit;

import java.util.List;

/**
 * Every reason a plan is not allowed to reach the world, asked one at a time.
 *
 * <h2>Why the checks are here and not in the gate</h2>
 *
 * <p>The gate is a sequence: check, ground, simulate, audit, act. Keeping the checks as plain
 * questions that answer with a reason or with nothing keeps that sequence readable, and makes each
 * refusal a thing that can be read, tested and worded on its own rather than a branch buried in a
 * loop that is also doing the acting.</p>
 *
 * <h2>Why a reason is a sentence</h2>
 *
 * <p>Whatever is refused, something has to decide what to do next, and the only thing an agent can
 * act on is being told what would have made the difference. Every reason here names the remedy --
 * certify again, shorten the plan, test the rule on a reversible path -- because a refusal the agent
 * cannot answer is a loop that stops.</p>
 */
final class Refusals {

    private Refusals() {
    }

    /** Whether there is a plan at all, and whether it is a length this policy commits. */
    static String size(List<Object> actions, CommitPolicy policy) {
        if (actions == null || actions.isEmpty()) {
            return "a commit with no actions has nothing to do";
        }
        if (actions.size() > policy.maxActionsPerCommit()) {
            return actions.size() + " actions is more than this policy commits at once ("
                   + policy.maxActionsPerCommit() + ")";
        }
        return null;
    }

    /** How far anything may go with no model behind it. */
    static String blind(List<Object> actions, CommitPolicy policy) {
        if (actions.size() > policy.maxBlindActions()) {
            return "a blind commit is limited to " + policy.maxBlindActions()
                   + " actions; certify a model to commit a longer plan";
        }
        return null;
    }

    /** Whether the evidence offered is about this model, this ledger, and holds. */
    static String certificate(WorldModel model, Certificate certificate, Ledger ledger,
                              CommitPolicy policy) {
        if (certificate == null) {
            return "this model has no certificate: certify it against the ledger first";
        }
        if (!certificate.modelDigest().equals(model.digest())) {
            return "the certificate is not about this model: it is about " + certificate.modelDigest();
        }
        if (!certificate.covers(ledger.head())) {
            return "the certificate is stale (the ledger moved " + certificate.ledgerLength()
                   + "->" + ledger.size() + "): certify again";
        }
        if (policy.requireGreen() && !certificate.green()) {
            return "the certificate is RED (" + certificate.mismatched()
                   + " mispredictions): fix the model, or commit a blind probe of at most "
                   + policy.maxBlindActions() + " actions";
        }
        return null;
    }

    /**
     * Whether every step that will actually run is one this policy allows to happen.
     *
     * <p>Only the steps up to the truncation are asked about. A plan whose fourth step deletes
     * something is not a reason to refuse the first three when the first three are all that will
     * run -- and the fourth will be asked about again, on its own evidence, when it is proposed
     * next.</p>
     */
    static String reversibility(Environment env, List<Object> actions, int count,
                                CommitPolicy policy, WorldModel model, Certificate certificate,
                                PlanAudit audit) {
        for (int at = 0; at < count; at++) {
            Object        action = actions.get(at);
            Reversibility kind   = env.reversibility(action);
            if (kind == Reversibility.IRREVERSIBLE) {
                String refusal = irreversible(action, at, policy, model, certificate, audit);
                if (refusal != null) {
                    return "step " + at + " is irreversible: " + refusal;
                }
            } else if (kind == Reversibility.COSTLY && policy.costlyRequiresModel()
                       && model == null) {
                return "step " + at + " is costly and this policy requires a certified model "
                       + "behind anything that spends something real";
            }
        }
        return null;
    }

    private static String irreversible(Object action, int at, CommitPolicy policy, WorldModel model,
                                       Certificate certificate, PlanAudit audit) {
        return switch (policy.irreversible()) {
            case ALLOW -> null;
            case DENY -> "this policy denies irreversible actions outright";
            case CERTIFIED -> certified(at, model, certificate, audit);
            case APPROVAL -> approved(action, at, policy, model, certificate, audit);
        };
    }

    private static String certified(int at, WorldModel model, Certificate certificate,
                                    PlanAudit audit) {
        if (model == null || certificate == null || !certificate.green()) {
            return "it requires a green certified model";
        }
        List<String> arms = audit.untestedArms().get(at);
        if (arms != null) {
            return "this step exercises untested rules " + arms
                   + "; test them on a reversible path first";
        }
        return null;
    }

    private static String approved(Object action, int at, CommitPolicy policy, WorldModel model,
                                   Certificate certificate, PlanAudit audit) {
        if (policy.approval() == null) {
            return "this policy needs approval but nobody was given to approve it";
        }
        return policy.approval().approves(action, context(at, model, certificate, audit))
               ? null
               : "approval was refused";
    }

    /** What whoever is asked needs to know: which step, which model, and how much it is trusted. */
    private static String context(int at, WorldModel model, Certificate certificate,
                                  PlanAudit audit) {
        return "step " + at + ", model=" + (model == null ? "none" : model.digest())
               + ", certified=" + (certificate != null && certificate.green() ? "green" : "no")
               + ", untested=" + audit.untestedArms().containsKey(at);
    }
}
