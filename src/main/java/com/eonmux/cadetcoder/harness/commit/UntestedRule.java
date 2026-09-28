package com.eonmux.cadetcoder.harness.commit;

/**
 * What to do about the first step of a plan that turns on a rule nothing has ever exercised.
 *
 * <p>Neither answer is more cautious in general. {@link #AFTER} treats the step as the experiment it
 * is: run it once, in isolation, and see what really happens -- which is how the rule stops being
 * untested. {@link #BEFORE} refuses to run it at all, which is right when even a single wrong step
 * costs more than the knowledge is worth.</p>
 */
public enum UntestedRule {

    /** Run through that step and stop, so its outcome is observed before anything depends on it. */
    AFTER,

    /** Stop before it, so an untested rule is never exercised by a commit. */
    BEFORE
}
