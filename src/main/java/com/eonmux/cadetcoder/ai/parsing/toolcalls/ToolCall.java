package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One tool call a model wrote in its own trained notation.
 *
 * <h2>Why this exists alongside the ACTION block</h2>
 *
 * <p>The system prompt asks for an {@code ACTION_START}/{@code ACTION_END} block, and most of the
 * time that is what arrives. A model that was trained to call tools through a notation of its own
 * reaches for that notation instead, most often on the first turn of a session and after a long
 * transcript. Nothing read those notations, so the turn was refused as a format error, re-prompted
 * twice, and the run ended -- over a reply that said exactly which command to run with which
 * arguments, in a form its own vendor documents.</p>
 *
 * <p>Every notation reduces to the same three facts: which tool, which named arguments, and which
 * notation it was written in. A reader per notation produces this record, and one parsing strategy
 * turns it into the action the dispatcher runs, so a new notation costs a reader and nothing
 * else.</p>
 *
 * @param name      the tool name, with any namespace qualifier already removed
 * @param arguments the named arguments, in the order they were written; never {@code null}
 * @param syntax    the notation it was read from, for diagnostics
 */
public record ToolCall(String name, Map<String, Object> arguments, String syntax) {

    /**
     * Separator DeepSeek uses between a tool's namespace and its name.
     *
     * <p>Its encoding documents {@code search::lookup} for a {@code lookup} tool in a {@code search}
     * namespace, and says the separator divides exactly one namespace from the name.</p>
     */
    private static final String NAMESPACE_SEPARATOR = "::";

    /**
     * Namespace prefixes the OpenAI Harmony notation puts in front of a tool name.
     *
     * <p>A Harmony call is addressed as {@code to=functions.read}. The prefix names the channel the
     * tool lives on rather than the tool, so it is not part of the name to look up.</p>
     */
    private static final String[] CHANNEL_PREFIXES = {"functions.", "tools.", "tool."};

    public ToolCall {
        arguments = arguments == null ? Map.of()
                                      : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * Reads one call, taking the bare tool name out of whatever qualified it.
     *
     * @param rawName   the tool name as written, possibly namespaced
     * @param arguments its named arguments; may be {@code null}
     * @param syntax    the notation it was read from
     * @return the call, or {@code null} when the name is blank and there is nothing to dispatch
     */
    public static ToolCall of(String rawName, Map<String, Object> arguments, String syntax) {
        String bare = bareName(rawName);
        return bare == null ? null : new ToolCall(bare, arguments, syntax);
    }

    /**
     * The tool name with any namespace or channel qualifier removed.
     *
     * @param rawName the name as written
     * @return the bare name, or {@code null} when nothing is left of it
     */
    static String bareName(String rawName) {
        if (rawName == null) {
            return null;
        }
        String name = rawName.trim();
        int    mark = name.lastIndexOf(NAMESPACE_SEPARATOR);
        if (mark >= 0) {
            name = name.substring(mark + NAMESPACE_SEPARATOR.length()).trim();
        }
        for (String prefix : CHANNEL_PREFIXES) {
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                name = name.substring(prefix.length()).trim();
                break;
            }
        }
        return name.isEmpty() ? null : name;
    }
}
