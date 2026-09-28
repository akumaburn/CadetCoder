package com.eonmux.cadetcoder.harness.loop;

import java.util.List;

/**
 * What one reply turned out to contain: the calls that could be read, and what could not be.
 *
 * <h2>Why complaints travel with the calls</h2>
 *
 * <p>A reply can be half-written -- three good calls and a fourth cut off by a token limit. Reading
 * it as three calls loses the fourth silently, and reading it as a failure throws away three calls
 * the agent meant. Both are carried, so the good ones run and the agent is told about the rest in
 * the same turn.</p>
 *
 * @param calls      what the agent asked for, in the order it wrote them
 * @param complaints what could not be read, each phrased as something the agent can fix
 */
public record CallReading(List<ToolRequest> calls, List<String> complaints) {

    public CallReading {
        calls      = List.copyOf(calls);
        complaints = List.copyOf(complaints);
    }

    /** Whether the reply asked for nothing at all -- neither a call nor a mistake. */
    public boolean silent() {
        return calls.isEmpty() && complaints.isEmpty();
    }

    /** Every complaint as one block of text, for the turn that reports them. */
    public String complaint() {
        return String.join(System.lineSeparator(), complaints);
    }
}
