package com.eonmux.cadetcoder.harness.loop;

/**
 * How a run stands, and every way one can end.
 *
 * <h2>Why the world's verdict and the agent's are different endings</h2>
 *
 * <p>{@link #GOAL} is the environment saying the thing was achieved; {@link #DONE} is the agent
 * saying it believes so. They agree often enough that collapsing them is tempting, and the runs where
 * they disagree are exactly the ones worth looking at -- an agent that declares success the world
 * never flagged is the failure that a single status would hide.</p>
 *
 * <h2>Why a declaration has to be a word</h2>
 *
 * <p>An agent declares two of these in prose, so the driver reads them off the front of a reply. A
 * plain prefix test ends a run on any word that happens to begin with the same letters, which is a
 * run stopped by a sentence about being done rather than by a declaration that it is.</p>
 */
public enum RunStatus {

    /** Nothing has ended the run yet. */
    RUNNING,

    /** The environment said the goal was reached. */
    GOAL,

    /** The environment said the episode cannot continue. */
    TERMINAL,

    /** The agent said the work is finished. */
    DONE,

    /** The agent said it cannot get any further, or the driver stopped it for not trying. */
    STUCK,

    /** The run spent what it was allowed. */
    BUDGET,

    /** Whoever started the run called it off. */
    STOPPED;

    /** Whether there is anything left to ask. */
    public boolean over() {
        return this != RUNNING;
    }

    /**
     * What a reply declares, if anything.
     *
     * @param text what the agent said
     * @return {@link #DONE}, {@link #STUCK}, or {@link #RUNNING} when it declared neither
     */
    public static RunStatus declared(String text) {
        if (text == null) {
            return RUNNING;
        }
        String said = text.strip();
        if (begins(said, SystemPrompt.DONE)) {
            return DONE;
        }
        return begins(said, SystemPrompt.STUCK) ? STUCK : RUNNING;
    }

    private static boolean begins(String said, String word) {
        return said.startsWith(word)
               && (said.length() == word.length()
                   || !Character.isLetterOrDigit(said.charAt(word.length())));
    }
}
