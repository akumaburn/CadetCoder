package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.belief.BeliefStatus;
import com.eonmux.cadetcoder.harness.certify.Certificate;

import java.util.ArrayList;
import java.util.List;

/**
 * The standing facts about a run, put in front of the agent every time it looks.
 *
 * <h2>Why the same block every time</h2>
 *
 * <p>Everything here is a question the agent would otherwise have to spend a turn asking, and every
 * one of them is a question whose answer changes what it should do next: how much is left to spend,
 * whether the standing theory still covers the ledger, whether the recent versions look like
 * learning or like patching. Repeating them is cheap; an agent planning against a certificate that
 * went stale four steps ago is not.</p>
 */
final class StatusBlock {

    private StatusBlock() {
    }

    /**
     * Where the run stands.
     *
     * @param session the run
     * @return the block, one fact to a line
     */
    static String render(ToolSession session) {
        List<String> lines = new ArrayList<>();
        lines.add(session.ledger().stats().render());
        lines.add("budget: " + session.budget().render());
        lines.add(models(session));
        String epicycles = session.registry().epicycleWarning();
        if (epicycles != null) {
            lines.add("epicycles: " + epicycles);
        }
        lines.add(beliefs(session));
        if (session.plans().size() > 0) {
            lines.add("plans: " + String.join(", ", session.plans().names()));
        }
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * What the run has been allowed and what it has spent.
     *
     * <p>The allowance and the tally are two different questions -- how close the run is to being
     * stopped, and what it has actually done -- and an agent deciding whether to probe again needs
     * both.</p>
     *
     * @param session the run
     * @return the two lines
     */
    static String budget(ToolSession session) {
        return session.budget().render() + System.lineSeparator()
               + "spent: " + session.budget().spend().render();
    }

    private static String models(ToolSession session) {
        String latest = session.registry().latest();
        if (latest == null) {
            return "models: none written yet; nothing can be planned or committed on a model until "
                   + "write_model has replayed one";
        }
        return "models: " + session.registry().size() + ", latest " + latest + " ("
               + standing(session, latest) + ")";
    }

    private static String standing(ToolSession session, String digest) {
        Certificate certificate = session.standing(digest);
        if (certificate == null) {
            return "no current certificate; certify replays it against the ledger there is now";
        }
        return (certificate.green() ? "green" : "RED") + ", coverage "
               + certificate.coverage().covered() + "/" + certificate.coverage().arms().size()
               + " arms";
    }

    private static String beliefs(ToolSession session) {
        return "beliefs: " + session.beliefs().byStatus(BeliefStatus.ACTIVE).size() + " held, "
               + session.beliefs().byStatus(BeliefStatus.OPEN).size() + " open questions, "
               + session.beliefs().byStatus(BeliefStatus.REFUTED, BeliefStatus.SUPERSEDED).size()
               + " dead";
    }
}
