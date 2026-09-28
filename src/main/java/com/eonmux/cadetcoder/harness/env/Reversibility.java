package com.eonmux.cadetcoder.harness.env;

/**
 * How much of the world an action spends.
 *
 * <p>This is the single fact that lets one loop drive both a puzzle and a repository. In a puzzle
 * every action is {@link #REVERSIBLE}: guessing is free, so the agent should guess a great deal and
 * learn from what it observes. In a working directory {@code rm -rf} is {@link #IRREVERSIBLE} and
 * guessing is not an option. The commit gate reads this and nothing else to decide whether an
 * action may be taken on an uncertified theory.</p>
 */
public enum Reversibility {

    /** Undoable, or repeatable from a reset: probing costs only budget. */
    REVERSIBLE,

    /** Spends something real -- time, money, a rate limit -- but leaves nothing broken. */
    COSTLY,

    /** Cannot be taken back: a deletion, a push, a message someone reads. */
    IRREVERSIBLE
}
