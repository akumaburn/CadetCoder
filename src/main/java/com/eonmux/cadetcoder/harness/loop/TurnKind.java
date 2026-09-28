package com.eonmux.cadetcoder.harness.loop;

/**
 * Who said one turn of the transcript, and therefore what may be done to it.
 *
 * <h2>Why the harness and the toolbox are told apart</h2>
 *
 * <p>Both are the harness speaking, so a backend sends both in the same role. They are kept apart
 * because only one of them may be given up under compaction: a tool answer is a copy of something
 * the ledger, the beliefs or the registry still hold, and can be fetched again, while a nudge or a
 * plateau notice is said once and exists nowhere else.</p>
 */
public enum TurnKind {

    /** What the agent said, which is never squashed and never rewritten. */
    AGENT,

    /** What the driver said: the opening observation, a nudge, a plateau notice, a compaction. */
    HARNESS,

    /** What a tool answered, which is a copy of something the run still holds. */
    ANSWER
}
