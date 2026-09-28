package com.eonmux.cadetcoder.harness.loop;

/**
 * Whatever does the thinking: a backend, a script, or a person.
 *
 * <h2>Why the driver knows nothing else about it</h2>
 *
 * <p>A run is a sequence of questions and answers, and everything the driver decides -- when a
 * deliberation ends, what is compacted, when a stronger reasoner takes over -- is decided from the
 * answer alone. Naming a model, a provider or a set of parameters here would make those decisions
 * conditional on which backend happened to be behind them, and a harness whose loop changes with its
 * supplier is one whose behaviour cannot be stated.</p>
 *
 * <h2>Why failures are not caught here</h2>
 *
 * <p>A backend that is rate limited, disconnected or misconfigured is not a fact about the world the
 * agent is exploring, and turning it into one would write nonsense into the ledger. Retrying belongs
 * to whatever knows how to retry; anything that reaches the driver ends the run loudly.</p>
 */
public interface Reasoner {

    /** What to call it in a log, a transcript, or an escalation notice. */
    String name();

    /**
     * Answers one question.
     *
     * @param system     the standing instructions, which do not change within a run
     * @param transcript everything said so far, oldest first
     * @return what it said and what that cost
     */
    Reply think(String system, Transcript transcript);
}
