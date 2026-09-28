package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every tool-call notation this client reads, and the one way to read them.
 *
 * <h2>Why one reply is read in one notation</h2>
 *
 * <p>The notations are tried in turn and the first that yields a call is the answer. A reply is
 * written in the notation its model was trained on, so finding calls in two of them means one of
 * the two matched something that was being quoted rather than called -- a reply that explains DSML
 * while writing an ordinary JSON answer, say. Merging the two would run a command the model was
 * describing. Taking the first is also what makes the order meaningful: the notations with a
 * distinctive marker are asked before the ones that recognise a shape.</p>
 */
public final class ToolCalls {

    /**
     * The notations, most distinctive first.
     *
     * <p>DSML, DeepSeek's older tool tokens, the invoke tags and Harmony each have a marker no
     * other notation uses. The JSON reader is last because its unmarked form recognises a shape
     * rather than a marker, and a shape is the thing most likely to be met by accident.</p>
     */
    private static final List<ToolCallSyntax> SYNTAXES = List.of(
            new DsmlToolCallSyntax(),
            new DeepSeekTokenToolCallSyntax(),
            new InvokeTagToolCallSyntax(),
            new HarmonyToolCallSyntax(),
            new JsonToolCallSyntax());

    private ToolCalls() {
    }

    /**
     * Whether a reply is written in any notation this reads.
     *
     * @param text the model's reply; may be {@code null}
     * @return whether reading it for calls is worthwhile
     */
    public static boolean appearIn(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (ToolCallSyntax syntax : SYNTAXES) {
            if (syntax.appearsIn(text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The calls a reply holds.
     *
     * @param text the model's reply; may be {@code null}
     * @return the calls, in the order they were written; empty when the reply holds none
     */
    public static List<ToolCall> readFrom(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        for (ToolCallSyntax syntax : SYNTAXES) {
            if (!syntax.appearsIn(text)) {
                continue;
            }
            List<ToolCall> calls = syntax.readFrom(text);
            if (!calls.isEmpty()) {
                return List.copyOf(calls);
            }
        }
        return List.of();
    }

    /**
     * Writes the tool calls a provider returned as data into the text a reply would have carried.
     *
     * <h2>Why a call that arrived as data is turned back into text</h2>
     *
     * <p>Some providers return a tool call in a field of the response rather than in the reply text:
     * an OpenAI {@code tool_calls} array, an Anthropic {@code tool_use} block, a Bedrock
     * {@code toolUse} block, a Gemini {@code functionCall} part. Read only for text, those replies
     * carried none, so the turn failed as "the model answered with a tool call, which this client
     * never asks for" -- a true statement about a reply that said exactly what to run, and the end
     * of the run. The backends hand the call on as text instead, because text is the one thing the
     * parsing engine reads, and this is the form {@link JsonToolCallSyntax} reads back.</p>
     *
     * <p>Whatever in the list is not a call is left out, so a backend may pass its whole content
     * list without first deciding which blocks are calls.</p>
     *
     * @param nativeCalls the response objects that may hold calls; may be {@code null}
     * @return their JSON text, or an empty string when none of them is a call
     */
    public static String textFor(List<?> nativeCalls) {
        if (nativeCalls == null || nativeCalls.isEmpty()) {
            return "";
        }
        List<Object> written = new ArrayList<>();
        for (Object element : nativeCalls) {
            ToolCall call = ToolCallFields.readFrom(JsonText.asObject(element), "response field");
            if (call == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", call.name());
            entry.put("arguments", call.arguments());
            written.add(entry);
        }
        return written.isEmpty() ? "" : JsonText.written(Map.of("tool_calls", written));
    }
}
