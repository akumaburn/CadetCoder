package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.PromptSize;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import java.util.List;

/**
 * Keeps the classic agent's prompt inside the model's context window.
 *
 * <h2>What is given up</h2>
 *
 * <p>The prompt carries every action the run took, each with its output. The outputs are nearly all
 * of its size, and they are what can be given up: the action itself stays, with its exit code, so
 * the model still knows what it did and in what order, and the loop guard still knows what it ran.
 * The oldest go first. The latest stays, because the model decides its next step from it.</p>
 *
 * <h2>When</h2>
 *
 * <p>On the rule the chat loop's {@link TranscriptCompactor} follows: once the prompt passes the
 * trigger share of the budget, outputs are left out until it is back under the target share, so a
 * run gives up its outputs once every several steps rather than on every step. After a refusal for
 * length the same is done whatever the estimate says, because the provider's count is the one that
 * decides.</p>
 */
final class AgentHistoryFit {

    private AgentHistoryFit() {
    }

    /**
     * Leaves the oldest outputs out of the prompt when it has to be smaller.
     *
     * @param state        the run, which records how many outputs are left out
     * @param systemPrompt the system prompt as sent, which counts against the same budget
     * @param userPrompt   the user prompt as built
     * @param refused      whether the provider refused this prompt as too long
     * @return what was left out, or {@code null} when nothing was; the prompt then has to be built
     *         again
     */
    static String fit(AgentState state, String systemPrompt, String userPrompt, boolean refused) {
        Configuration.CompactionConfig settings = settings();
        if (settings == null || !settings.isEnabled()) {
            return null;
        }
        int budget = TranscriptCompactor.effectiveBudgetTokens();
        int prompt = PromptSize.tokens(systemPrompt) + PromptSize.tokens(userPrompt);
        if (!refused && prompt < settings.getTrigger() * budget) {
            return null;
        }

        List<AgentState.ActionResult> actions = state.getExecutedActions();
        String model  = PromptSize.configuredModel();
        int    excess = prompt - (int) (settings.getTarget() * budget);
        if (refused) {
            // The refused prompt is larger than the model's window, so half of it is near half the
            // window, where compaction aims. Without a count, the estimate says nothing.
            excess = Math.max(excess, prompt / 2);
        }
        int    first     = state.outputsLeftOut();
        int    next      = first;
        int    reclaimed = 0;
        while (next < actions.size() - 1 && reclaimed < excess) {
            reclaimed += PromptSize.tokens(model, AgentPrompts.outputShown(actions.get(next)));
            next++;
        }
        if (next == first) {
            return TranscriptCompactor.cannotFit(prompt, budget);
        }
        state.leaveOutOutputsBefore(next);

        int given = next - first;
        String note = given + " earlier output" + (given == 1 ? "" : "s") + " left out, ~"
                      + reclaimed + " tokens reclaimed";
        return prompt - reclaimed <= budget
               ? note
               : note + "; still " + TranscriptCompactor.measured(prompt - reclaimed, budget);
    }

    private static Configuration.CompactionConfig settings() {
        try {
            return ConfigManager.getInstance().getConfig().getCompaction();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
