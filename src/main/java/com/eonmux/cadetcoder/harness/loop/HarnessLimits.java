package com.eonmux.cadetcoder.harness.loop;

/**
 * What the driver is allowed to do between the agent and the world.
 *
 * <h2>Why these are separate from the budget</h2>
 *
 * <p>A budget bounds what a run may spend; these bound how it spends it. Nothing here stops a run --
 * a transcript that is compacted, a turn that is nudged and a deliberation that is cut off all leave
 * the run going -- so putting them in the budget would mix the two questions whose answers a plateau
 * has to tell apart: whether there is anything left, and whether more of the same would help.</p>
 *
 * <h2>Why a nudge is bounded</h2>
 *
 * <p>An agent that ends its turn without acting is told to act, which usually works. An agent for
 * which it does not work is one being asked the same question forever, at full price, and the run
 * ends when the tokens do with nothing to show. The bound turns that into an ending that says what
 * happened.</p>
 *
 * @param compactionTokens        the average tokens sent per deliberation above which the transcript
 *                                is cut down
 * @param keepRecentAnswers       how many of the newest tool answers a compaction leaves alone
 * @param maxCallsPerDeliberation how many calls a round of thinking may make before it is told to act
 * @param maxSilentReplies        how many turns in a row may end without acting before the run stops
 */
public record HarnessLimits(long compactionTokens, int keepRecentAnswers,
                            int maxCallsPerDeliberation, int maxSilentReplies) {

    /** Comfortably inside a large context, so compaction happens before a request is refused. */
    private static final long STANDARD_COMPACTION_TOKENS = 120_000L;

    /** Enough recent evidence to finish the argument the current deliberation is making. */
    private static final int STANDARD_KEEP_RECENT = 12;

    /** Room to read widely before acting, without room to read instead of acting. */
    private static final int STANDARD_CALLS = 60;

    /** Twice told is a misunderstanding; a third time is a refusal to act. */
    private static final int STANDARD_SILENT_REPLIES = 3;

    public HarnessLimits {
        if (compactionTokens < 1) {
            throw new IllegalArgumentException("compaction has to happen at some positive size");
        }
        if (keepRecentAnswers < 0) {
            throw new IllegalArgumentException("a compaction cannot keep a negative number of "
                                               + "answers");
        }
        if (maxCallsPerDeliberation < 1) {
            throw new IllegalArgumentException("a deliberation that may make no calls can never act");
        }
        if (maxSilentReplies < 1) {
            throw new IllegalArgumentException("a run that allows no silent reply is stopped by the "
                                               + "nudge that was meant to correct it");
        }
    }

    /** What a run gets when nobody has said otherwise. */
    public static HarnessLimits standard() {
        return new HarnessLimits(STANDARD_COMPACTION_TOKENS, STANDARD_KEEP_RECENT, STANDARD_CALLS,
                                 STANDARD_SILENT_REPLIES);
    }

    public HarnessLimits withCompactionTokens(long tokens) {
        return new HarnessLimits(tokens, keepRecentAnswers, maxCallsPerDeliberation,
                                 maxSilentReplies);
    }

    public HarnessLimits withKeepRecentAnswers(int answers) {
        return new HarnessLimits(compactionTokens, answers, maxCallsPerDeliberation,
                                 maxSilentReplies);
    }

    public HarnessLimits withMaxCallsPerDeliberation(int calls) {
        return new HarnessLimits(compactionTokens, keepRecentAnswers, calls, maxSilentReplies);
    }

    public HarnessLimits withMaxSilentReplies(int replies) {
        return new HarnessLimits(compactionTokens, keepRecentAnswers, maxCallsPerDeliberation,
                                 replies);
    }
}
