package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.certify.Certificate;
import com.eonmux.cadetcoder.harness.commit.CommitResult;
import com.eonmux.cadetcoder.harness.model.WorldModel;

import java.util.List;

/**
 * The two tools that reach the world.
 *
 * <h2>Why a commit either names a plan or lists actions, never both loosely</h2>
 *
 * <p>A plan carries the model it was found in and the audit of which steps rest on rules nothing
 * has exercised. Retyping its actions into a commit throws both away and hands the gate a list it
 * knows nothing about. Naming the plan keeps the three together, and the model a plan was found in
 * is the model it is checked against unless the call deliberately says otherwise.</p>
 *
 * <h2>Why the observation comes back with the result</h2>
 *
 * <p>Acting is the only thing that moves the world, so it is the only moment at which the agent's
 * idea of where it is goes stale. Answering with the summary alone would cost a second tool call to
 * find out what the first one did, in a run whose context is the scarcest thing it has.</p>
 */
final class ActTools {

    /** How much of the observation after a commit an answer shows. */
    private static final int OBSERVATION_SHOWN = 3_000;

    private ActTools() {
    }

    /** Takes actions in the real world, checked against a model when there is one. */
    static String commit(ToolSession session, ToolArgs args) {
        PlanRecord   kept    = named(session, args);
        List<Object> actions = kept == null ? args.values("actions", List.of()) : kept.actions();
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("committing needs actions: either a list of them, "
                                               + "or the name of a plan the planner saved");
        }
        String       reference = args.text("model", kept == null ? null : kept.modelDigest());
        String       note      = args.text("note", "");
        CommitResult result    = reference == null
                                 ? session.gate().probe(actions, said(note))
                                 : planned(session, args, reference, actions, said(note));
        session.remember(result.certificate());

        String answer = result.summary();
        return result.executed() == 0
               ? answer
               : answer + System.lineSeparator() + "now: "
                 + Compact.value(session.gate().observation(), OBSERVATION_SHOWN);
    }

    /** Starts a new episode, which is recorded like anything else and costs like anything else. */
    static String reset(ToolSession session, ToolArgs args) {
        Object observation = session.gate().reset(said(args.text("note", "")));
        return "reset done; a new episode has begun and every certificate is now stale"
               + System.lineSeparator() + "now: "
               + Compact.value(observation, OBSERVATION_SHOWN);
    }

    private static CommitResult planned(ToolSession session, ToolArgs args, String reference,
                                        List<Object> actions, String note) {
        WorldModel  model       = session.model(reference);
        Certificate certificate = session.certified(model, false);
        return session.gate().commit(model, certificate, actions,
                                     args.flag("allow_untested", false), note);
    }

    private static PlanRecord named(ToolSession session, ToolArgs args) {
        if (!args.has("plan")) {
            return null;
        }
        String     name = args.text("plan");
        PlanRecord kept = session.plans().get(name);
        if (kept != null) {
            return kept;
        }
        throw new IllegalArgumentException("there is no plan called " + name
                                           + (session.plans().size() == 0
                                              ? "; this run has found none yet"
                                              : "; this run has found "
                                                + String.join(", ", session.plans().names())));
    }

    private static String said(String note) {
        return note == null || note.isEmpty() ? null : note;
    }
}
