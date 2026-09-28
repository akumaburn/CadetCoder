package com.eonmux.cadetcoder.commands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Decides whether an agentic action is a genuine non-productive loop, and refuses only those.
 *
 * <h2>What was wrong before</h2>
 *
 * <p>The previous detector reached its verdict from <em>command names alone</em>, which made normal
 * work look like a loop and caused real actions to be silently skipped:</p>
 *
 * <ul>
 *   <li>Three greps in a row were a "loop" no matter how different the patterns were, because the
 *       check only counted how many recent history entries started with {@code "grep:"}.</li>
 *   <li>{@code read -> grep -> read -> grep -> read -> grep} was an "alternating pattern" even when
 *       all six touched different files: only the verb before the {@code ':'} was compared.</li>
 *   <li>A "similar filename" rule treated two same-extension files as the same work when they shared
 *       their first <em>or last</em> three characters. In a Java tree that matches almost everything:
 *       {@code ReadCommand.java} / {@code WriteCommand.java} (both end {@code "and"}),
 *       {@code ChatCommand.java} / {@code ChatContext.java} (both start {@code "Cha"}). Reading three
 *       files from one package was reported as an infinite loop.</li>
 *   <li>{@code read} was given a threshold of 2, so the ordinary read-edit-read verify cycle tripped
 *       it and the confirming read never ran.</li>
 * </ul>
 *
 * <h2>The rule now</h2>
 *
 * <p>A loop is <em>repetition that cannot produce new information</em>. Every rule here therefore
 * compares the full action -- command <em>and</em> normalized arguments -- and additionally requires
 * that the repeats already <em>produced the same result</em>. Two reads of one file are not a loop if
 * the file changed in between; three different greps are never a loop, however many there are.</p>
 *
 * <p>The guard is per-run state. It is deliberately a separate object rather than more fields on the
 * chat context so the same protection can be shared by every agentic loop; {@link AgentCommand} had
 * no loop protection at all.</p>
 *
 * <p>Not thread-safe: one agentic turn executes its actions sequentially.</p>
 */
public final class ActionLoopGuard {

    /** Separates the command from its arguments inside a signature. Never occurs in real input. */
    private static final char COMMAND_SEPARATOR = '\u0000';

    /** Separates arguments from each other inside a signature. Never occurs in real input. */
    private static final char ARGUMENT_SEPARATOR = '\u0001';

    /** How many observations are retained. Bounds memory and scopes every rule to recent work. */
    private static final int HISTORY_LIMIT = 24;

    /** Window for the exact-repetition rule. */
    private static final int EXACT_WINDOW = 8;

    /**
     * How many prior identical-and-identically-resulting executions justify refusing the next one.
     * Two is enough <em>because</em> the outcomes must match: the same input already produced the
     * same output twice, so a third attempt cannot tell the model anything new.
     */
    private static final int EXACT_REPEAT_LIMIT = 2;

    /** Cycle lengths checked by the repeating-sequence rule. */
    private static final int[] CYCLE_PERIODS = {2, 3, 4};

    /** How many consecutive uninformative observations count as a stall. */
    private static final int STALL_WINDOW = 10;

    /**
     * Commands that change state. They are exempt from the fuzziest rule (the stall rule) because
     * wrongly skipping a mutation loses work, whereas wrongly skipping an inspection costs only a
     * round trip. They remain subject to the exact-repetition and cycle rules, both of which require
     * identical results and so cannot fire on a mutation that is actually changing something.
     */
    private static final Set<String> MUTATING_COMMANDS = Set.of(
            "write", "edit", "multiedit", "notebookedit", "patch", "commit", "push", "undo",
            "todowrite", "bash", "job");

    /** Maximum number of output characters folded into an outcome fingerprint. */
    private static final int OUTPUT_FINGERPRINT_LIMIT = 8192;

    /**
     * How many refusals in a row mean the run itself should stop rather than be nudged again.
     *
     * <p>This is the backstop that replaced the fixed iteration ceilings. A count of steps is a poor
     * stop condition -- it cuts off long tasks that are making progress, and does nothing for a run
     * that is stuck on step 3. Consecutive refusals are the direct signal: the model has been told
     * what repeated and what to do instead, and has proposed non-productive work anyway, this many
     * times running. Any action that actually ran clears the counter, whether it succeeded or not:
     * this counts refusals, and an action that ran and failed is not one -- it produced an error the
     * model has not seen, which is exactly the new information a refusal withholds. An action that
     * keeps failing identically is caught by the exact-repetition rule instead, which compares
     * outcomes.</p>
     */
    private static final int MAX_CONSECUTIVE_BLOCKS = 3;

    private final Deque<Observation> history   = new ArrayDeque<>();
    private final Set<String>        seenPairs = new HashSet<>();

    private int consecutiveBlocks = 0;

    /** One executed action and what it produced. */
    private static final class Observation {
        private final String signature;
        private final String outcome;

        private Observation(String signature, String outcome) {
            this.signature = signature;
            this.outcome   = outcome;
        }

        private String pair() {
            return signature + COMMAND_SEPARATOR + outcome;
        }
    }

    /** The verdict for one prospective action. */
    public static final class Decision {

        private static final Decision PROCEED = new Decision(false, null, null);

        private final boolean blocked;
        private final String  reason;
        private final String  guidance;

        private Decision(boolean blocked, String reason, String guidance) {
            this.blocked  = blocked;
            this.reason   = reason;
            this.guidance = guidance;
        }

        /** @return {@code true} when the action must not be executed */
        public boolean isBlocked() {
            return blocked;
        }

        /** @return a short operator-facing explanation, or {@code null} when not blocked */
        public String getReason() {
            return reason;
        }

        /** @return concrete instructions to feed back to the model, or {@code null} when not blocked */
        public String getGuidance() {
            return guidance;
        }
    }

    /**
     * Decides whether {@code command}/{@code args} should be executed. Pure: call
     * {@link #observe(String, String[], boolean, String)} after the action actually runs.
     *
     * @param command the command about to be dispatched
     * @param args    its arguments (may be {@code null})
     * @return the decision, never {@code null}
     */
    public Decision check(String command, String[] args) {
        if (command == null || command.trim().isEmpty()) {
            return Decision.PROCEED;
        }
        String signature = signature(command, args);
        String label     = describe(command, args);

        // Cycles are checked first because a multi-step cycle and a plain repeat are both caught by
        // the exact rule, but "this whole sequence is stuck" is more useful guidance than "you ran
        // one command twice". checkCycle only claims genuinely multi-step patterns; a "cycle" of one
        // repeated action falls through to the exact rule and gets the clearer message.
        Decision cycle = checkCycle(signature);
        if (cycle.isBlocked()) {
            return cycle;
        }
        Decision exact = checkExactRepetition(signature, label);
        if (exact.isBlocked()) {
            return exact;
        }
        return checkStall(command, signature, label);
    }

    /**
     * Records what an executed action produced. Only executed actions are recorded; an action the
     * guard refused never happened and must not shape later verdicts.
     *
     * @param command   the command that ran
     * @param args      its arguments (may be {@code null})
     * @param succeeded whether it reported success
     * @param output    what it produced (may be {@code null})
     */
    public void observe(String command, String[] args, boolean succeeded, String output) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        Observation observation = new Observation(signature(command, args), outcome(succeeded, output));
        history.addLast(observation);
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
        seenPairs.add(observation.pair());
        // An action actually ran, so the run is not being refused. See MAX_CONSECUTIVE_BLOCKS for
        // why one that ran and failed still counts as having run.
        consecutiveBlocks = 0;
    }

    /**
     * Records that the caller honoured a refusal and did not execute the action.
     *
     * <p>Kept separate from {@link #check(String, String[])} so that check stays pure: a caller may
     * consult the guard without the consultation itself counting as a refusal.</p>
     *
     * @return the number of consecutive refusals including this one
     */
    public int recordBlocked() {
        return ++consecutiveBlocks;
    }

    /**
     * Whether the run should stop rather than be nudged again.
     *
     * @return {@code true} once {@link #MAX_CONSECUTIVE_BLOCKS} actions in a row have been refused
     *         with no successful action in between
     */
    public boolean isStuck() {
        return consecutiveBlocks >= MAX_CONSECUTIVE_BLOCKS;
    }

    /** @return how many actions have been refused in a row, with no successful action since */
    public int consecutiveBlocks() {
        return consecutiveBlocks;
    }

    /** Clears all recorded state, so a new request starts with no inherited history. */
    public void reset() {
        history.clear();
        seenPairs.clear();
        consecutiveBlocks = 0;
    }

    /** @return how many actions have been observed since the last {@link #reset()} */
    public int observedCount() {
        return history.size();
    }

    // ---------------------------------------------------------------- rules

    /**
     * Refuses an action that has already run at least {@link #EXACT_REPEAT_LIMIT} times in the recent
     * window <em>with the same result every time</em>. Differing results mean something changed
     * between the runs, which is progress, not a loop.
     */
    private Decision checkExactRepetition(String signature, String label) {
        List<Observation> matches = new ArrayList<>();
        for (Observation observation : tail(EXACT_WINDOW)) {
            if (observation.signature.equals(signature)) {
                matches.add(observation);
            }
        }
        if (matches.size() < EXACT_REPEAT_LIMIT) {
            return Decision.PROCEED;
        }
        String outcome = matches.get(0).outcome;
        for (Observation match : matches) {
            if (!match.outcome.equals(outcome)) {
                // The same call produced different results, so re-running it is informative.
                return Decision.PROCEED;
            }
        }
        return new Decision(true,
                "repeated '" + label + "' " + matches.size() + " times with an identical result",
                "The command `" + label + "` has already run " + matches.size()
                        + " times in this turn and produced exactly the same result each time, so running it "
                        + "again cannot reveal anything new. Use the output you already have, or choose a "
                        + "different command or different arguments to make progress.");
    }

    /**
     * Refuses a repeating sequence of actions -- the argument-aware replacement for the old
     * name-only A-B-A-B check. Every position must match on both the full signature and the result,
     * and the incoming action must continue that cycle; a model that has already varied its arguments
     * is making progress and is left alone.
     */
    private Decision checkCycle(String signature) {
        for (int period : CYCLE_PERIODS) {
            List<Observation> window = tail(period * 2);
            if (window.size() < period * 2) {
                continue;
            }
            boolean repeats = true;
            for (int i = 0; i < period; i++) {
                Observation earlier = window.get(i);
                Observation later   = window.get(i + period);
                if (!earlier.signature.equals(later.signature) || !earlier.outcome.equals(later.outcome)) {
                    repeats = false;
                    break;
                }
            }
            if (!repeats) {
                continue;
            }
            // Only refuse if this action would continue the cycle rather than break it.
            if (!window.get(period).signature.equals(signature)) {
                continue;
            }
            List<String> steps    = new ArrayList<>();
            Set<String>  distinct = new HashSet<>();
            for (int i = 0; i < period; i++) {
                steps.add(labelOf(window.get(i).signature));
                distinct.add(window.get(i).signature);
            }
            // One action repeated is not a "cycle"; let the exact-repetition rule name it plainly.
            if (distinct.size() < 2) {
                continue;
            }
            String sequence = String.join(" -> ", steps);
            return new Decision(true,
                    "repeating " + period + "-step cycle with identical results: " + sequence,
                    "The sequence `" + sequence + "` has already repeated twice in this turn, producing "
                            + "identical results both times, so continuing it cannot make progress. Break the "
                            + "cycle: use what these commands already returned, or try a different approach.");
        }
        return Decision.PROCEED;
    }

    /**
     * Refuses when a long run of consecutive actions has produced nothing that was not already seen.
     * This catches long-period churn without any name-only guesswork: a single new
     * command-plus-result pair anywhere in the window clears it.
     */
    private Decision checkStall(String command, String signature, String label) {
        if (MUTATING_COMMANDS.contains(command.trim().toLowerCase(Locale.ROOT))) {
            return Decision.PROCEED;
        }
        if (history.size() < STALL_WINDOW) {
            return Decision.PROCEED;
        }
        List<Observation> all    = new ArrayList<>(history);
        List<Observation> window = all.subList(all.size() - STALL_WINDOW, all.size());
        Set<String>       before = new HashSet<>();
        for (int i = 0; i < all.size() - STALL_WINDOW; i++) {
            before.add(all.get(i).pair());
        }
        for (Observation observation : window) {
            if (!before.contains(observation.pair())) {
                return Decision.PROCEED;
            }
        }
        // The stall is only actionable if the incoming action is itself more of the same.
        boolean seenBefore = false;
        for (Observation observation : all) {
            if (observation.signature.equals(signature)) {
                seenBefore = true;
                break;
            }
        }
        if (!seenBefore) {
            return Decision.PROCEED;
        }
        return new Decision(true,
                "the last " + STALL_WINDOW + " actions produced no new information; '" + label
                        + "' repeats earlier work",
                "The last " + STALL_WINDOW + " commands all returned results that had already been seen, "
                        + "and `" + label + "` repeats one of them. Stop gathering information and act on "
                        + "what you have, or state clearly what is blocking you.");
    }

    // ------------------------------------------------------------ internals

    private List<Observation> tail(int count) {
        List<Observation> all = new ArrayList<>(history);
        if (all.size() <= count) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - count, all.size()));
    }

    /**
     * Builds the identity of an action: the command plus every argument, normalized so that different
     * spellings of one path ({@code ./src/Foo.java} and {@code src/Foo.java}) are one action rather
     * than two. Arguments are never lower-cased -- paths are case-sensitive.
     *
     * @param command the command name
     * @param args    its arguments (may be {@code null})
     * @return an opaque signature string
     */
    static String signature(String command, String[] args) {
        StringBuilder signature = new StringBuilder(command.trim().toLowerCase(Locale.ROOT));
        signature.append(COMMAND_SEPARATOR);
        if (args != null) {
            for (String arg : args) {
                signature.append(normalizeArgument(arg)).append(ARGUMENT_SEPARATOR);
            }
        }
        return signature.toString();
    }

    /**
     * Normalizes one argument: collapses whitespace and canonicalizes path-ish spellings.
     *
     * @param arg the raw argument (may be {@code null})
     * @return the normalized form, never {@code null}
     */
    static String normalizeArgument(String arg) {
        if (arg == null) {
            return "";
        }
        String normalized = arg.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return "";
        }
        normalized = normalized.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        normalized = normalized.replaceAll("/{2,}", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Fingerprints what an action produced. Only a hash of the (normalized, capped) output is kept so
     * command output -- which can contain file contents -- is not retained by the guard.
     */
    private static String outcome(boolean succeeded, String output) {
        String normalized = output == null ? "" : output.trim().replaceAll("\\s+", " ");
        if (normalized.length() > OUTPUT_FINGERPRINT_LIMIT) {
            normalized = normalized.substring(0, OUTPUT_FINGERPRINT_LIMIT);
        }
        return (succeeded ? "ok" : "fail") + ":" + Objects.hash(normalized);
    }

    /**
     * Renders an action the way a user would type it, for messages.
     *
     * @param command the command name
     * @param args    its arguments (may be {@code null})
     * @return a readable one-line rendering
     */
    static String describe(String command, String[] args) {
        StringBuilder text = new StringBuilder(command.trim());
        if (args != null) {
            for (String arg : args) {
                String value = arg == null ? "" : arg.trim();
                text.append(' ');
                if (value.isEmpty() || value.contains(" ")) {
                    text.append('"').append(value).append('"');
                } else {
                    text.append(value);
                }
            }
        }
        return text.toString();
    }

    /** Renders a stored signature back into readable form for messages. */
    private static String labelOf(String signature) {
        return signature.replace(COMMAND_SEPARATOR, ' ').replace(ARGUMENT_SEPARATOR, ' ').trim();
    }
}
