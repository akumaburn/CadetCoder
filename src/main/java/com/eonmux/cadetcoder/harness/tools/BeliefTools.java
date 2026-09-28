package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.belief.Belief;

/**
 * What the agent holds, what it has given up, and what it is still asking.
 *
 * <h2>Why an answer repeats what was recorded</h2>
 *
 * <p>A belief is written down so that a later turn -- possibly after everything before it has been
 * compacted away -- can be held to it. Answering with an identifier alone would mean the turn that
 * recorded a claim is the turn that forgets its wording. Saying it back costs a line and makes the
 * record and the transcript agree.</p>
 */
final class BeliefTools {

    private BeliefTools() {
    }

    /** Records something now held to be true, with the transitions that convinced the agent. */
    static String add(ToolSession session, ToolArgs args) {
        Belief belief = session.beliefs().claim(args.text("text"), session.ledger().size(),
                                                args.integers("evidence"), args.texts("tags"),
                                                args.text("supersedes", null));
        return "held " + belief.id() + ": " + belief.text();
    }

    /** Marks a belief dead, naming what killed it. */
    static String refute(ToolSession session, ToolArgs args) {
        Belief belief = session.beliefs().refute(args.text("id"), args.integers("evidence"),
                                                 args.text("reason"));
        return "refuted " + belief.id() + ": " + belief.text() + " -- " + belief.reason();
    }

    /** Records something the agent does not know yet and means to settle by experiment. */
    static String question(ToolSession session, ToolArgs args) {
        Belief belief = session.beliefs().question(args.text("text"), session.ledger().size(),
                                                   args.texts("tags"));
        return "asked " + belief.id() + ": " + belief.text();
    }

    /** Answers a question that was recorded earlier. */
    static String resolve(ToolSession session, ToolArgs args) {
        Belief belief = session.beliefs().resolve(args.text("id"), args.text("answer"),
                                                  args.integers("evidence"));
        return "answered " + belief.id() + ": " + belief.text() + " -> " + belief.answer();
    }

    /** Everything held, everything open and everything that died. */
    static String all(ToolSession session) {
        return session.beliefs().size() == 0
               ? "nothing is written down yet: no beliefs, no open questions"
               : session.beliefs().render();
    }
}
