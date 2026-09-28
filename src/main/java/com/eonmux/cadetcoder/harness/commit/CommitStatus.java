package com.eonmux.cadetcoder.harness.commit;

import java.util.Locale;

/** How a commit ended. */
public enum CommitStatus {

    /** Every step the gate allowed was taken and nothing stopped it. */
    COMPLETED,

    /** The environment reported the goal, so there was nothing further worth doing. */
    GOAL,

    /** The environment reported that the episode cannot continue. */
    TERMINAL,

    /** The world contradicted the model, so the rest of the plan was discarded. */
    SURPRISE,

    /** The plan was stopped at the first step that turns on a rule nothing has tested. */
    TRUNCATED_UNTESTED,

    /** Nothing reached the world; the reason says what would have been wrong with it. */
    REFUSED,

    /** The run spent an allowance partway through, and stopped where it was. */
    BUDGET;

    /** How it is written in a record. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
