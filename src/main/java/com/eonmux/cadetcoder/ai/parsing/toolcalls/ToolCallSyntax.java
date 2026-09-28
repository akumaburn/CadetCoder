package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.List;

/**
 * One notation a model writes tool calls in.
 *
 * <p>A reader answers two questions about a reply: whether the notation is present at all, which is
 * asked of every reply and so has to be cheap, and which calls it holds, which is asked only of a
 * reply the first question accepted.</p>
 */
interface ToolCallSyntax {

    /**
     * @return the notation's name, as it appears in diagnostics
     */
    String name();

    /**
     * Whether this notation's markers appear in a reply.
     *
     * <p>A literal search, not a parse: this runs against every reply the model sends, including the
     * ones that are plain prose.</p>
     *
     * @param text the reply to examine; never {@code null}
     * @return whether it is worth reading for calls
     */
    boolean appearsIn(String text);

    /**
     * The calls this notation holds, in the order they were written.
     *
     * @param text the reply to read; never {@code null}
     * @return the calls found, empty when the notation's markers are present but hold no usable call
     */
    List<ToolCall> readFrom(String text);
}
