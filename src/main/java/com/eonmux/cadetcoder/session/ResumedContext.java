package com.eonmux.cadetcoder.session;

import com.eonmux.cadetcoder.ai.metrics.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a restored session's conversation history into the opening of a new run's transcript.
 *
 * <h2>Why resuming needs this at all</h2>
 *
 * <p>{@code --continue} and {@code --resume} restore a {@link SessionState}, and until now that was
 * the end of it: the history was written on every turn, persisted, read back — and then never used.
 * The session's identity and todo list survived; the conversation itself did not, so a resumed run
 * began with a model that had been told nothing about what it was resuming.</p>
 *
 * <h2>Why only the tail, and why marked</h2>
 *
 * <p>A long session's history is larger than the window it has to fit into, so all of it cannot come
 * back. The most recent turns are kept, because a resumed session continues from where it stopped;
 * dropping from the front is the same choice the transcript compactor makes for the same reason.</p>
 *
 * <p>What comes back is labelled as an earlier session rather than being pasted in as though it had
 * just happened. Unmarked, the model reads the last thing it said before the break as the thing it
 * just said, and continues mid-thought from a turn the user may have abandoned hours ago.</p>
 */
public final class ResumedContext {

    /**
     * Share of the prompt budget restored history may occupy.
     *
     * <p>A quarter. The point of resuming is to continue working, so most of the window has to stay
     * available for the work; history that crowds out the current task has defeated itself.</p>
     */
    static final double BUDGET_SHARE = 0.25;

    /** Below this there is no room to restore anything useful. */
    static final int MINIMUM_TOKENS = 256;

    private ResumedContext() {
    }

    /**
     * The restored conversation for this process, or empty when nothing was resumed.
     *
     * <p>Shared so every call that opens a resumed session gets the same context. It was previously
     * built only inside the iterative loop, which meant the FIRST request of a resumed run — the one
     * that decides what to do with the user's follow-up — was made with no idea what was being
     * followed up. Verified against a recording provider: that request carried none of the restored
     * conversation while the turns after it carried all of it.</p>
     *
     * @return entries to lead a prompt with, empty for a fresh session
     */
    public static List<String> forCurrentSession() {
        try {
            SessionManager sessions = SessionManager.getInstance();
            if (!sessions.isResumed()) {
                return List.of();
            }
            return seed(sessions.getConversationHistory(), budgetTokens());
        } catch (RuntimeException e) {
            // Restoring context is a convenience; failing to must never stop the run the user asked
            // for. A cold start is a worse conversation, not a broken one.
            return List.of();
        }
    }

    /** @return the prompt budget the restored share is measured against */
    private static int budgetTokens() {
        try {
            return com.eonmux.cadetcoder.commands.TranscriptCompactor.effectiveBudgetTokens();
        } catch (RuntimeException e) {
            return com.eonmux.cadetcoder.ai.ContextWindow.tokens();
        }
    }

    /**
     * Builds the transcript entries a resumed run should start from.
     *
     * @param history      the restored conversation, oldest first; may be null or empty
     * @param budgetTokens the prompt budget the run has to fit inside
     * @return entries to seed the transcript with, empty when there is nothing worth restoring
     */
    public static List<String> seed(List<String> history, int budgetTokens) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int allowance = (int) (Math.max(0, budgetTokens) * BUDGET_SHARE);
        if (allowance < MINIMUM_TOKENS) {
            return List.of();
        }

        // Walk backwards: the newest turns are the ones a resumed session continues from.
        List<String> kept  = new ArrayList<>();
        int          spent = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            String entry = history.get(i);
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int cost = TokenEstimator.estimate(entry);
            if (spent + cost > allowance) {
                break;
            }
            kept.add(0, entry);
            spent += cost;
        }
        if (kept.isEmpty()) {
            return List.of();
        }

        List<String> seeded = new ArrayList<>(kept.size() + 2);
        int dropped = history.size() - kept.size();
        seeded.add("System: [Resumed session. "
                   + (dropped > 0 ? dropped + " earlier turn" + (dropped == 1 ? "" : "s")
                                    + " are not shown; the " + kept.size() + " most recent follow."
                                  : "The conversation so far follows.")
                   + " This happened BEFORE the current request, which comes after it.]");
        seeded.addAll(kept);
        seeded.add("System: [End of the resumed conversation. What follows is new.]");
        return seeded;
    }
}
