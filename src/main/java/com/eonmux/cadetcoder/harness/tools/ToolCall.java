package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.Json;

import java.util.Map;

/**
 * One call the agent made and what it was answered with.
 *
 * <h2>Why the refusals are kept too</h2>
 *
 * <p>A run that keeps calling a tool the wrong way is a run whose prompt is wrong, and that is only
 * visible if the calls that came to nothing are written down beside the ones that worked. The log is
 * also what compaction reads: the recent calls are kept in full and the older ones are dropped, so
 * an answer that was never used costs nothing later.</p>
 *
 * @param tool      what was called
 * @param arguments what it was called with
 * @param answer    what the agent was told
 * @param refused   whether the call never ran, because something about it was wrong
 */
public record ToolCall(String tool, Map<String, Object> arguments, String answer, boolean refused) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        answer    = answer == null ? "" : answer;
    }

    /** The call and its answer, as a transcript reads. */
    public String render() {
        return "> " + tool + " " + Json.canonical(arguments) + System.lineSeparator()
               + Compact.indented(answer, "  ");
    }

    @Override
    public String toString() {
        return "> " + tool + " " + Json.canonical(arguments);
    }
}
