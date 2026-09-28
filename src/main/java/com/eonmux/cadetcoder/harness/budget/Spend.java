package com.eonmux.cadetcoder.harness.budget;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a run has spent, as at one moment.
 *
 * <h2>Why a snapshot and not the budget itself</h2>
 *
 * <p>Spending is reported in three places that must agree with each other -- the status block the
 * agent reads, the record a commit leaves behind, and whatever the driver prints when the run ends
 * -- and all three read it while the run is still going. A value taken once and passed around
 * cannot disagree with itself halfway through being formatted, which a live counter can.</p>
 *
 * @param actions       actions that changed the world
 * @param resets        episodes started over
 * @param tokensIn      tokens sent to the model
 * @param tokensOut     tokens the model sent back
 * @param toolCalls     calls that read the world without changing it
 * @param deliberations times the agent stopped and thought
 * @param surprises     times the world contradicted a prediction
 * @param elapsedMillis how long the run has been going
 */
public record Spend(int actions, int resets, long tokensIn, long tokensOut, int toolCalls,
                    int deliberations, int surprises, long elapsedMillis) {

    /** Tokens in both directions, which is the quantity the allowance is denominated in. */
    public long tokens() {
        return tokensIn + tokensOut;
    }

    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("actions", actions);
        value.put("resets", resets);
        value.put("tokens_in", tokensIn);
        value.put("tokens_out", tokensOut);
        value.put("tokens", tokens());
        value.put("tool_calls", toolCalls);
        value.put("deliberations", deliberations);
        value.put("surprises", surprises);
        value.put("elapsed_ms", elapsedMillis);
        return Map.copyOf(value);
    }

    public String render() {
        return actions + " actions, " + resets + " resets, " + tokens() + " tokens, "
               + toolCalls + " tool calls, " + deliberations + " deliberations, "
               + surprises + " surprises, " + elapsedMillis + "ms";
    }

    @Override
    public String toString() {
        return render();
    }
}
