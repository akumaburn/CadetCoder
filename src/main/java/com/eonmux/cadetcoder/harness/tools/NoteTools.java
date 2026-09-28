package com.eonmux.cadetcoder.harness.tools;

/**
 * The agent's own working notes.
 *
 * <h2>Why notes are separate from beliefs</h2>
 *
 * <p>A belief is a claim with evidence behind it that later turns can hold the agent to, and it is
 * refuted rather than edited. A note is a scratch line -- a hunch, a thing to try next, a shape
 * half-seen -- and forcing every one of those through the belief store would fill the record with
 * claims nothing supports. Notes are for what has not earned a belief yet.</p>
 */
final class NoteTools {

    private NoteTools() {
    }

    /** Adds a line to the notes. */
    static String append(ToolSession session, ToolArgs args) {
        session.notes().append(args.text("text"));
        return "noted; the notes now run to " + session.notes().size() + " characters";
    }

    /** Reads the end of the notes back. */
    static String read(ToolSession session, ToolArgs args) {
        String tail = session.notes().tail(args.integer("tail_chars", Notes.STANDARD_TAIL));
        return tail.isEmpty() ? "there are no notes yet" : tail;
    }
}
