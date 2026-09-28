package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.ContextWindow;
import com.eonmux.cadetcoder.ai.OutputWindow;
import com.eonmux.cadetcoder.ai.metrics.TokenEstimator;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Folds a long run's transcript down so it keeps fitting in the model's input window.
 *
 * <h2>Why this is rare rather than eager</h2>
 *
 * <p>The prompt is the transcript, rendered verbatim, and it is append-only precisely so a provider
 * can serve the unchanged head from cache. Compaction rewrites that head, so it throws the cache
 * away: on a measured 90-96% shared prefix, one compaction costs the equivalent of many ordinary
 * turns of new-token spend, paid in a single request.</p>
 *
 * <p>It is still worth having, because not compacting does not degrade gracefully. An over-length
 * prompt is a 400, which is terminal — the retry policy will not repeat it — so the run ends and the
 * whole task is lost. A rare, expensive request beats that.</p>
 *
 * <p>Two consequences follow directly and shape everything below: a verbatim HEAD is preserved, so
 * the system prompt plus that head stay cache-eligible rather than only the system prompt; and the
 * target sits far below the trigger, so a run compacts once every several turns rather than every
 * turn.</p>
 *
 * <h2>What it does, and does not, do</h2>
 *
 * <p>Entries are dropped, not summarised. Asking the model to summarise costs a request at exactly
 * the moment the run is largest and most valuable, can fail, and — worse — can succeed while quietly
 * dropping the one fact that mattered, laundering model prose into the transcript as though it were
 * record. Dropping is safe here for a specific reason: the anti-repeat memory does not live in the
 * transcript. {@code ActionLoopGuard} and the action history live in the context map, so "I already
 * ran that and it produced nothing new" survives a compaction even when the entry that showed it is
 * gone.</p>
 */
public final class TranscriptCompactor {

    /** Headroom left for the response and for estimator error, in tokens. */
    static final int SAFETY_MARGIN_TOKENS = 512;

    /** Smallest input budget worth reasoning about. */
    static final int MINIMUM_BUDGET_TOKENS = 1024;

    /**
     * Reduction below which a compaction is not worth repeating.
     *
     * <p>If folding the middle does not make the transcript meaningfully smaller, compaction is
     * switched off for the rest of the run. That is the answer to a transcript whose retained head
     * and tail are themselves most of its bulk: repeating a compaction that reclaims nothing would
     * rewrite the prefix on every turn, paying the cache cost over and over for no gain.</p>
     *
     * <h2>Why a small reclaim is still applied</h2>
     *
     * <p>It used to be discarded as well as latched off, which answered "this prompt does not fit"
     * with the prompt unchanged and nothing said. The two questions are separate: whether to fold
     * AGAIN is about cost, and whether to keep THIS fold is about whether the request can be sent
     * at all. A reduction too small to be worth repeating is still the difference between a request
     * and a 400 that ends the run, so it is kept whenever the prompt does not otherwise fit.</p>
     */
    static final double MINIMUM_RECLAIM = 0.20;

    private boolean latchedOff;
    private int     compactions;

    /** @return how many times this run has compacted */
    public int compactions() {
        return compactions;
    }

    /** @return whether compaction has given up for the rest of this run */
    public boolean isLatchedOff() {
        return latchedOff;
    }

    /**
     * The input budget a prompt has to fit inside.
     *
     * <p>The context window less what the model may generate, less a margin. The window is the total
     * the model accepts; the completion has to come out of the same allowance.</p>
     *
     * @return the budget in tokens
     */
    public static int effectiveBudgetTokens() {
        int window = ContextWindow.tokens();
        return Math.max(MINIMUM_BUDGET_TOKENS,
                        window - replyReserveTokens(window) - SAFETY_MARGIN_TOKENS);
    }

    /**
     * Room held back in the input window for a reply that has not been written yet.
     *
     * <p>Taken from {@link OutputWindow} rather than from {@code ai.maxTokens}. That setting is zero
     * by default, meaning no ceiling is sent, so reading it here would reserve nothing and let a
     * prompt grow into the whole window. The question the reserve asks is how much the model may
     * produce, which is what {@link OutputWindow} answers.</p>
     *
     * <p>Capped at half the window. A model whose published output limit is its whole context, which
     * several small local models report, would otherwise claim every token and leave the prompt the
     * floor.</p>
     *
     * @param window the input window in tokens
     * @return the reserve in tokens
     */
    static int replyReserveTokens(int window) {
        return Math.min(OutputWindow.tokens(), Math.max(0, window / 2));
    }

    /**
     * Folds {@code transcript} in place when the prompt has grown past the trigger.
     *
     * <h2>What the system prompt has to do with it</h2>
     *
     * <p>The budget is for the whole prompt, and the trigger was measured against the whole prompt
     * -- system half included -- while the fold itself worked to a limit that counted the
     * transcript alone. On a large system prompt the two disagree by exactly the size of it, so a
     * fold could reach its target, report the tokens it had reclaimed, and hand back a prompt still
     * over the window. Nothing measured the total again afterwards, so the first anyone heard of it
     * was the provider's 400, which is terminal. The system prompt is therefore counted in the
     * fold's limit and the total is checked once the fold is done.</p>
     *
     * @param transcript   the run's transcript; modified in place when compaction happens
     * @param systemPrompt the system half of the prompt, which counts against the same budget
     * @param safeToCompact whether the run is in a state where rewriting history is sensible
     * @return a description of what was folded, and of what it could not fit, or {@code null} when
     *         nothing was done
     */
    public String compactIfNeeded(List<String> transcript, String systemPrompt, boolean safeToCompact) {
        if (latchedOff || transcript == null || transcript.isEmpty() || !safeToCompact) {
            return null;
        }
        Configuration.CompactionConfig settings = settings();
        if (settings == null || !settings.isEnabled()) {
            return null;
        }

        int budget      = effectiveBudgetTokens();
        int system      = TokenEstimator.estimate(systemPrompt == null ? "" : systemPrompt);
        int before      = estimateTranscript(transcript);
        int promptToken = system + before;
        if (promptToken < settings.getTrigger() * budget) {
            return null;
        }

        int head = Math.max(0, settings.getKeepHeadEntries());
        int tail = Math.max(1, settings.getKeepTailEntries());
        if (transcript.size() <= head + tail) {
            // Nothing in the middle to fold: the head and tail alone are the whole transcript, so a
            // compaction cannot reclaim anything and repeating it every turn would be pure cost.
            return giveUp(promptToken, budget);
        }

        List<String> folded = fold(transcript, head, tail, budget, settings.getTarget(), system);
        int          after  = estimateTranscript(folded);
        boolean      fits   = system + after <= budget;

        if (after >= before || (after > before * (1.0 - MINIMUM_RECLAIM) && fits)) {
            // Nothing worth having: either the fold reclaimed nothing at all, or it reclaimed too
            // little to be worth repeating and the prompt fits without it. Rewriting the prefix
            // would throw the provider-side cache away to buy a saving nobody needs.
            return giveUp(promptToken, budget);
        }
        if (after > before * (1.0 - MINIMUM_RECLAIM)) {
            // Too little to be worth doing again, and the prompt does not fit without it, so it is
            // applied once and this run stops trying.
            latchedOff = true;
        }

        int dropped = transcript.size() - folded.size() + 1; // +1: the marker replaced them
        transcript.clear();
        transcript.addAll(folded);
        compactions++;

        String summary = dropped + " earlier step" + (dropped == 1 ? "" : "s") + " folded, ~"
                         + (before - after) + " tokens reclaimed";
        if (fits) {
            return summary;
        }
        // The fold has taken the transcript down to its head, its marker and one entry, so there is
        // nothing left for another turn to reclaim.
        latchedOff = true;
        return summary + "; still " + measured(system + after, budget);
    }

    /**
     * Stops trying, and says plainly when the prompt is still too big to send.
     *
     * <p>Silence here was the whole of the problem: a prompt that did not fit was handed back
     * unchanged with {@code null} meaning "nothing to report", and the next thing that happened was
     * a 400 the retry policy will not repeat, which ends the run and loses the task. Being over the
     * TRIGGER is ordinary and says nothing worth printing; being over the BUDGET is the request
     * that cannot be sent, and the person watching is the only one who can do anything about it.</p>
     *
     * <p>The window is named as well as the excess, because the two failures this line covers look
     * identical without it: a transcript that has genuinely grown too long, and a window that was
     * assumed because the model is served through a gateway the catalog does not list.</p>
     *
     * @param promptTokens the whole prompt as it stands
     * @param budget       what it has to fit inside
     * @return {@code null}, because nothing was folded
     */
    private String giveUp(int promptTokens, int budget) {
        latchedOff = true;
        if (promptTokens > budget) {
            ContextWindow.Window window = ContextWindow.current();
            OutputFormatter.printWarning(
                    "Cannot make this request fit: " + measured(promptTokens, budget) + ". "
                    + whereTheLimitComesFrom(window));
            OutputFormatter.printInfo(whatToDoAboutIt(window));
        }
        return null;
    }

    /**
     * The limit's provenance, in one sentence.
     *
     * @param window the input window and where its figure came from
     * @return the sentence to print under the measurement
     */
    static String whereTheLimitComesFrom(ContextWindow.Window window) {
        int reserve = replyReserveTokens(window.tokens());
        return "The limit is the " + count(window.tokens()) + "-token input window ("
               + window.source() + ") less " + count(reserve) + " held back for the reply and "
               + count(SAFETY_MARGIN_TOKENS) + " for measurement error.";
    }

    /**
     * What the person reading can do, named in the order they should try it.
     *
     * <p>A window that was assumed is a setting away from being right, and saying so is the whole
     * point of naming the source above. A window that is already the model's own leaves only the
     * transcript, so that is what is offered instead.</p>
     *
     * @param window the input window and where its figure came from
     * @return the line to print
     */
    static String whatToDoAboutIt(ContextWindow.Window window) {
        if (window.source().startsWith("assumed")) {
            return "Run '" + CommandUsage.prefix() + "models context <tokens>' with the window "
                   + "this model really has, or start a new session to begin from an empty "
                   + "transcript.";
        }
        return "Start a new session to begin from an empty transcript, or run '"
               + CommandUsage.prefix() + "models context <tokens>' if this model takes more than "
               + "the figure above.";
    }

    /**
     * The prompt and the limit it has to fit inside, as one phrase.
     *
     * <h2>Why both numbers are printed</h2>
     *
     * <p>This used to say only how far over the prompt was. "About 4,068 tokens over what the
     * model will take" is true of a run that has gone on too long and of a window the tool merely
     * assumed, and it gives the reader nothing to compare: 4,068 over a limit of 900,000 is a long
     * session, and 4,068 over a limit of 3,584 is a limit that is wrong. Printing the pair says
     * which of the two it is at a glance.</p>
     *
     * @param promptTokens the whole prompt as it stands
     * @param budget       what it has to fit inside
     * @return e.g. {@code "about 7,652 tokens of prompt against a limit of 3,584"}
     */
    static String measured(int promptTokens, int budget) {
        return "about " + count(promptTokens) + " tokens of prompt against a limit of "
               + count(budget);
    }

    /** A token count with thousands separated, because these numbers are read, not calculated. */
    private static String count(int tokens) {
        return String.format("%,d", tokens);
    }

    /**
     * Builds the folded transcript: a verbatim head, one marker, then a verbatim tail.
     *
     * <p>Entries are taken back from the OLDEST end of the middle while the result is still over
     * target, so the most recent context survives longest.</p>
     *
     * @param systemTokens the system prompt's share of the budget, which the transcript does not
     *                     get to spend; counting the transcript alone against the target left a
     *                     fold satisfied while the prompt it belonged to was still too big
     */
    private List<String> fold(List<String> transcript, int head, int tail, int budget, double target,
                              int systemTokens) {
        List<String> result  = new ArrayList<>(transcript.subList(0, Math.min(head, transcript.size())));
        int          tailFrom = Math.max(head, transcript.size() - tail);

        // Written with the count as it stands, and rewritten once the fold has finished. It goes in
        // now rather than at the end so the loop below weighs a transcript that includes it.
        //
        // It used to be written once, here, and the loop below then dropped further entries without
        // touching it: a fold that gave up 33 steps told the model 28 of them were still in front of
        // it, and contradicted the summary line the caller prints, which IS counted from the
        // finished sizes.
        result.add(marker(tailFrom - head));
        result.addAll(transcript.subList(tailFrom, transcript.size()));

        // Still over target: give up more of the tail's oldest entries, keeping the newest.
        int limit = Math.max(0, (int) (target * budget) - systemTokens);
        while (estimateTranscript(result) > limit && result.size() > head + 2) {
            result.remove(head + 1);
        }

        // result is: the head, one marker, and whatever survived of the tail -- so everything the
        // model can still see is result.size() - 1 of the original entries.
        result.set(head, marker(transcript.size() - (result.size() - 1)));
        return result;
    }

    /**
     * What stands in place of the entries the fold gave up.
     *
     * @param omitted how many entries are not in the folded transcript
     * @return the marker line
     */
    private static String marker(int omitted) {
        return "System: [" + omitted + " earlier step" + (omitted == 1 ? "" : "s")
               + " omitted to stay within the model's context window. The work they"
               + " describe was done; the steps below continue from it.]";
    }

    /** @return the estimated token cost of the transcript as it would be rendered */
    static int estimateTranscript(List<String> transcript) {
        int total = 0;
        for (String entry : transcript) {
            if (entry != null) {
                total += TokenEstimator.estimate(entry);
            }
        }
        return total;
    }

    private static Configuration.CompactionConfig settings() {
        try {
            return ConfigManager.getInstance().getConfig().getCompaction();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
