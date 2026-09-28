package com.eonmux.cadetcoder.commands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Detects an iterative run that has stopped making progress, and tries to restart it before giving
 * up.
 *
 * <p>{@link ActionLoopGuard} watches the <em>actions</em> a model proposes, which is the right signal
 * for the chat and agent loops. {@link IterativeExecutor} drives eleven other commands that never
 * propose an action: they exchange a prompt for a response, feed the response into the next step,
 * and repeat. What goes round in circles there is the response itself, so that is what this
 * watches.</p>
 *
 * <h2>Recovery before refusal</h2>
 *
 * <p>The first thing a repeated response calls for is not a stop but a change of input. A provider
 * asked the same bytes twice can legitimately answer the same bytes twice -- at temperature zero, or
 * behind a response cache, that is the correct behaviour, and the loop is then a property of the
 * request rather than of the model's reasoning. {@link #perturbation()} returns text to append to the
 * next request that makes it distinguishable, so an identical answer is no longer the deterministic
 * one, and says plainly why it is there so the model has something to act on.</p>
 *
 * <p>The text goes at the <b>end</b> of the user prompt and never enters the transcript. Everything
 * before it is byte-identical to the previous turn, so the shared prefix a provider caches is intact
 * and only the few tokens of the nudge itself are new.</p>
 *
 * <p>If the response still repeats after {@link #REPEATS_BEFORE_STOP} turns, varying the input has
 * not helped and the run stops.</p>
 */
public final class IterationProgressGuard {

    /** Turns of remembered responses; enough for the longest cycle checked, several times over. */
    static final int HISTORY_LIMIT = 12;

    /**
     * Cycle lengths treated as repetition. Period 1 is the same answer twice running; 2 and 3 catch
     * a model alternating between two or three answers, which is the same standstill spread out.
     */
    static final int[] CYCLE_PERIODS = {1, 2, 3};

    /** Consecutive repeating turns tolerated before the run is stopped. */
    static final int REPEATS_BEFORE_STOP = 3;

    /**
     * Characters of a response compared. Two answers that agree for this long are the same answer
     * for the purpose of deciding whether anything moved.
     */
    static final int FINGERPRINT_LIMIT = 8192;

    private final Supplier<String> tokenSource;
    private final Deque<String>    history = new ArrayDeque<>();

    private int consecutiveRepeats;

    public IterationProgressGuard() {
        this(IterationProgressGuard::randomToken);
    }

    /**
     * @param tokenSource supplies the token that makes a nudged request unique; injectable so a test
     *                    can assert on the text instead of matching a random value
     */
    IterationProgressGuard(Supplier<String> tokenSource) {
        this.tokenSource = tokenSource;
    }

    /**
     * Records what a turn produced.
     *
     * @param turnOutput the model's response, or whatever else the turn yielded; {@code null} counts
     *                   as an empty response, which repeats like any other value
     */
    public void record(String turnOutput) {
        String fingerprint = fingerprint(turnOutput);
        history.addLast(fingerprint);
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
        if (repeats()) {
            consecutiveRepeats++;
        } else {
            consecutiveRepeats = 0;
        }
    }

    /**
     * @return {@code true} once repetition has survived {@link #REPEATS_BEFORE_STOP} turns of being
     *         nudged, at which point the run is not going to recover on its own
     */
    public boolean isStuck() {
        return consecutiveRepeats >= REPEATS_BEFORE_STOP;
    }

    /** @return how many turns in a row have repeated an earlier response */
    public int consecutiveRepeats() {
        return consecutiveRepeats;
    }

    /**
     * @return a sentence naming what repeated and how often, for the line shown when a run stops
     */
    public String stopReason() {
        return "the model returned the same response " + (consecutiveRepeats + 1)
               + " times in a row and the task did not advance";
    }

    /**
     * Text to append to the next request, or an empty string when the run is progressing.
     *
     * <p>Whether a nudge is due is a pure function of what has been recorded; only the token inside
     * it varies, which is the entire point of it.</p>
     *
     * @return the text to append at the very end of the user prompt
     */
    public String perturbation() {
        if (consecutiveRepeats == 0) {
            return "";
        }
        return "\n\nYour previous reply repeated the one before it, so this task has not moved"
               + " forward. Do not send that reply again: either take a different, concrete step, or"
               + " state that the task is complete in the format required above."
               + "\nRequest id (ignore its contents, it only makes this message unique): "
               + tokenSource.get();
    }

    /** Forgets every recorded turn, as though the run had just started. */
    public void reset() {
        history.clear();
        consecutiveRepeats = 0;
    }

    /** @return how many turns have been recorded, bounded by {@link #HISTORY_LIMIT} */
    public int observedCount() {
        return history.size();
    }

    /**
     * Whether the most recent turns close a cycle of any checked period.
     *
     * <p>A cycle of period {@code p} needs {@code 2p} turns to be visible: the last {@code p} have to
     * match the {@code p} before them. Checking shortest period first means the common case -- the
     * same answer twice -- is found immediately.</p>
     */
    private boolean repeats() {
        List<String> recent = new ArrayList<>(history);
        for (int period : CYCLE_PERIODS) {
            if (recent.size() < period * 2) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < period; i++) {
                String latest   = recent.get(recent.size() - period + i);
                String previous = recent.get(recent.size() - (period * 2) + i);
                if (!latest.equals(previous)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reduces a response to what is compared: whitespace collapsed, so a reply that differs only in
     * line wrapping is still the same reply, and truncated so one enormous response cannot pin a
     * multiple of itself in memory.
     */
    private static String fingerprint(String turnOutput) {
        if (turnOutput == null) {
            return "";
        }
        String normalized = turnOutput.strip().replaceAll("\\s+", " ");
        return normalized.length() <= FINGERPRINT_LIMIT
               ? normalized
               : normalized.substring(0, FINGERPRINT_LIMIT);
    }

    private static String randomToken() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong());
    }
}
