package com.eonmux.cadetcoder.harness.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the search for an experiment found, and why it stopped looking.
 *
 * <h2>Why "no experiment" is four different answers</h2>
 *
 * <p>{@link SearchStatus#EXHAUSTED} means the candidates cannot be told apart at all within the
 * horizon, which is a finding: they are the same theory as far as the agent can act, and the right
 * response is to keep the simpler one rather than to go on gathering evidence. A stopped search
 * means nothing of the kind, and an agent that read the two the same way would abandon a
 * distinction it could have made in one more step.</p>
 *
 * @param status       why the search stopped
 * @param actions      the sequence that reaches the parting, empty unless one was found
 * @param disagreement what the candidates said at the parting, {@code null} unless one was found
 * @param effort       what the search cost
 * @param error        what a candidate said when it broke, {@code null} unless the status is an error
 */
public record DiscriminationResult(SearchStatus status, List<Object> actions,
                                   Disagreement disagreement, SearchEffort effort, String error) {

    public DiscriminationResult {
        actions = List.copyOf(actions);
    }

    static DiscriminationResult found(List<Object> actions, Disagreement disagreement,
                                      SearchEffort effort) {
        return new DiscriminationResult(SearchStatus.FOUND, actions, disagreement, effort, null);
    }

    static DiscriminationResult stopped(SearchStatus status, SearchEffort effort) {
        return new DiscriminationResult(status, List.of(), null, effort, null);
    }

    static DiscriminationResult broken(String error, SearchEffort effort) {
        return new DiscriminationResult(SearchStatus.ERROR, List.of(), null, effort, error);
    }

    /** Whether there is an experiment here to run. */
    public boolean found() {
        return status == SearchStatus.FOUND;
    }

    /**
     * What to do about a search that came back without an experiment.
     *
     * <p>Only exhaustion has an answer worth giving. It says the candidates predict the same things
     * everywhere the agent can reach, so no action distinguishes them and no budget will change
     * that -- the choice between them is a choice of which theory to keep, not evidence to collect.</p>
     *
     * @return what it means, or {@code null} when the search was merely stopped or broken
     */
    public String hint() {
        if (status != SearchStatus.EXHAUSTED) {
            return null;
        }
        return "no action sequence within " + effort.depthReached()
               + " steps tells these candidates apart: they are observationally equivalent that far "
               + "out. Look further, or keep the simpler one and stop paying for the difference.";
    }

    /** The one line an agent is told. */
    public String summary() {
        StringBuilder text = new StringBuilder("discriminate ").append(status.label()).append(": ")
                .append(effort.render());
        if (found()) {
            text.append(" actions=").append(actions.size());
        }
        if (error != null) {
            text.append(" error=").append(error);
        }
        if (disagreement != null) {
            text.append(System.lineSeparator()).append("  ").append(disagreement.render());
        }
        String hint = hint();
        if (hint != null) {
            text.append(System.lineSeparator()).append("  hint: ").append(hint);
        }
        return text.toString();
    }

    /** The result as a value the harness can keep. */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", status.label());
        value.put("actions", actions);
        value.put("expanded", effort.expanded());
        value.put("distinct", effort.distinct());
        value.put("depth", effort.depthReached());
        value.put("elapsed_ms", effort.elapsedMillis());
        value.put("error", error);
        value.put("hint", hint());
        value.put("disagreement", disagreement == null ? null : disagreement.toValue());
        return value;
    }

    @Override
    public String toString() {
        return summary();
    }
}
