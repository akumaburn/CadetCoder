package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.ledger.Transition;

import java.util.ArrayList;
import java.util.List;

/**
 * Looking at where the world is and at what really happened to get it there.
 *
 * <h2>Why looking is free</h2>
 *
 * <p>Nothing here reaches the environment. The current observation is whatever the last transition
 * recorded, so asking for it again cannot change it, and the ledger is append-only, so reading it
 * cannot either. That is what lets an agent re-read its own history as often as it needs to without
 * the world moving underneath the answer.</p>
 */
final class LedgerTools {

    /** How much of one observation a line of the tail spends. */
    private static final int OBSERVATION_IN_TAIL = 300;

    private LedgerTools() {
    }

    /** Where the world is, with everything standing about the run underneath it. */
    static String observe(ToolSession session) {
        return StatusBlock.render(session) + System.lineSeparator()
               + "now: " + Compact.value(session.gate().observation());
    }

    /** The last few transitions, newest last. */
    static String tail(ToolSession session, ToolArgs args) {
        int     count   = args.integer("n", ToolCatalog.TAIL_SHOWN);
        boolean withObs = args.flag("with_obs", false);
        if (count < 1) {
            throw new IllegalArgumentException("n has to be at least one transition");
        }
        List<Transition> shown = session.ledger().tail(count);
        if (shown.isEmpty()) {
            return "the ledger is empty; nothing has happened yet";
        }
        List<String> lines = new ArrayList<>(shown.size());
        for (Transition transition : shown) {
            lines.add(line(transition, withObs));
        }
        return String.join(System.lineSeparator(), lines);
    }

    /** One transition, in full. */
    static String get(ToolSession session, ToolArgs args) {
        int index = args.integer("index", 0);
        int size  = session.ledger().size();
        if (size == 0) {
            return "the ledger is empty; nothing has happened yet";
        }
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("there is no transition " + index + "; the ledger "
                                               + "runs from 0 to " + (size - 1));
        }
        return full(session.ledger().get(index));
    }

    private static String line(Transition transition, boolean withObs) {
        StringBuilder out = new StringBuilder("#").append(transition.index())
                                                  .append(" ep=").append(transition.episode())
                                                  .append(" ").append(Json.canonical(transition.action()));
        if (!transition.flags().isEmpty()) {
            out.append(" flags=").append(Json.canonical(transition.flags()));
        }
        if (transition.predictionHeld() != null) {
            out.append(transition.predictionHeld() ? " predicted" : " MISPREDICTED");
        }
        if (said(transition.note())) {
            out.append(" note=").append(transition.note());
        }
        if (withObs) {
            out.append(System.lineSeparator()).append("    -> ")
               .append(Compact.value(transition.obsAfter(), OBSERVATION_IN_TAIL));
        }
        return out.toString();
    }

    private static String full(Transition transition) {
        StringBuilder out = new StringBuilder("#").append(transition.index())
                                                  .append(" ep=").append(transition.episode())
                                                  .append(" at ").append(transition.timestamp());
        out.append(System.lineSeparator()).append("  before: ")
           .append(Compact.value(transition.obsBefore()));
        out.append(System.lineSeparator()).append("  action: ")
           .append(Json.canonical(transition.action()));
        out.append(System.lineSeparator()).append("  after:  ")
           .append(Compact.value(transition.obsAfter()));
        out.append(System.lineSeparator()).append("  flags:  ")
           .append(Json.canonical(transition.flags()));
        if (!transition.info().isEmpty()) {
            out.append(System.lineSeparator()).append("  info:   ")
               .append(Compact.value(transition.info()));
        }
        if (transition.modelHash() != null) {
            out.append(System.lineSeparator()).append("  model:  ").append(transition.modelHash())
               .append(" predicted ").append(Compact.value(transition.predicted()))
               .append(transition.predictionHeld() != null && transition.predictionHeld()
                       ? " and it held" : " and it did not hold");
        }
        if (said(transition.note())) {
            out.append(System.lineSeparator()).append("  note:   ").append(transition.note());
        }
        out.append(System.lineSeparator()).append("  hash:   ").append(transition.hash());
        return out.toString();
    }

    private static boolean said(String text) {
        return text != null && !text.isBlank();
    }
}
