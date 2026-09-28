package com.eonmux.cadetcoder.harness.loop;

/**
 * What the world outside a run has to say to it while it is going.
 *
 * <h2>Why the driver asks rather than being told</h2>
 *
 * <p>Two things outside this package need to put a sentence in front of the agent at a particular
 * moment: a check-in the agent set itself earlier and is now owed, and a question about a completion
 * it has just claimed. Both are about how this tool is used rather than about how a run works, and
 * neither is something the loop could work out for itself. Reaching for them directly would put the
 * harness -- which is otherwise about ledgers, models and evidence, and knows nothing of this tool --
 * on top of half of it.</p>
 *
 * <p>So the loop asks, at the two moments it can act on an answer, and takes silence for the usual
 * case. A run built with {@link #none()} behaves exactly as it did before there was anything to
 * ask.</p>
 *
 * <h2>Why an implementation may remember things</h2>
 *
 * <p>{@link #questionDone} is asked the same question by every claim a run makes, and the answer has
 * to run out -- "is it really finished?" can be asked forever, and a run whose every claim is
 * refused never ends. Keeping that count here rather than in the loop is deliberate: how many times
 * a claim is worth questioning is a policy, and the loop has no opinion about it. An implementation
 * is therefore used by one run and not shared between them.</p>
 */
public interface RunSignals {

    /**
     * Anything that holds for the whole run rather than for one moment of it.
     *
     * <p>Asked once, when the run is built, and appended to the standing instructions. Said in a
     * turn instead it would be read once and compacted away halfway through, which is where a run
     * that was supposed to be driven to the end stops being driven at all.</p>
     *
     * @return what to add to what the agent is told, or an empty string to add nothing
     */
    String standingInstruction();

    /**
     * Anything to say to the agent before its next round of thinking.
     *
     * @return what to say, or an empty string for the usual case of nothing
     */
    String beforeRound();

    /**
     * Whether a run the agent says is finished should be questioned rather than ended.
     *
     * @param declaration what the agent said when it declared the work done
     * @return what to send it back with, or an empty string to accept the declaration
     */
    String questionDone(String declaration);

    /** A run nothing outside it has anything to say to. */
    static RunSignals none() {
        return new RunSignals() {
            @Override
            public String standingInstruction() {
                return "";
            }

            @Override
            public String beforeRound() {
                return "";
            }

            @Override
            public String questionDone(String declaration) {
                return "";
            }
        };
    }
}
