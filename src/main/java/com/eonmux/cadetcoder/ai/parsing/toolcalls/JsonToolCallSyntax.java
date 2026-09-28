package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tool calls a model writes as JSON, behind whichever marker its vendor trained it on.
 *
 * <p>Four markers and one unmarked form, all carrying the same JSON:</p>
 * <ul>
 *   <li>{@code <tool_call>{"name": ..., "arguments": {...}}</tool_call>}, used by Qwen, Hermes and
 *       most models fine-tuned from them.</li>
 *   <li>{@code [TOOL_CALLS] [{"name": ..., "arguments": {...}}]}, used by Mistral.</li>
 *   <li>{@code <|python_tag|>{"name": ..., "parameters": {...}}}, used by Llama.</li>
 *   <li>{@code <function=read>{...}</function>}, where the marker names the tool and the JSON is
 *       only the arguments.</li>
 *   <li>A reply that is itself the JSON, which is how a model answers when the marker token was
 *       consumed as a stop token, and how {@code ToolCallText} hands on a call that arrived in a
 *       response field rather than in the reply text.</li>
 * </ul>
 *
 * <h2>Why the unmarked form is guarded</h2>
 *
 * <p>A reply that is a JSON object is not necessarily a tool call -- a model asked to produce JSON
 * answers with exactly that, and a document with a {@code name} field in it is ordinary. So the
 * unmarked form is read only when the object names a tool and carries an arguments field under one
 * of the documented keys. Without that guard, asking a model to write a {@code package.json} would
 * have run its {@code name} as a command.</p>
 */
final class JsonToolCallSyntax implements ToolCallSyntax {

    private static final Pattern TOOL_CALL_TAG = Pattern.compile(
            "<tool_call>(.*?)</tool_call>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** The marker Functionary-style notations use, which names the tool in the tag itself. */
    private static final Pattern FUNCTION_TAG = Pattern.compile(
            "<function\\s*=\\s*([^>]+)>(.*?)</function\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Markers that are followed by a bare JSON value rather than wrapping one. */
    private static final Pattern LEADING_MARKER = Pattern.compile(
            "\\[TOOL_CALLS\\]|<\\|python_tag\\|>", Pattern.CASE_INSENSITIVE);

    /** The key an OpenAI-shaped reply lists its calls under. */
    private static final String CALLS_KEY = "tool_calls";

    /** Keys that, with a name beside them, make an unmarked JSON object a tool call. */
    private static final String[] ARGUMENT_MARKERS = {"\"arguments\"", "\"parameters\"", "\"input\""};

    @Override
    public String name() {
        return "JSON tool call";
    }

    @Override
    public boolean appearsIn(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("<tool_call>")
               || lower.contains("[tool_calls]")
               || lower.contains("<|python_tag|>")
               || lower.contains("<function=")
               || looksLikeBareJsonCall(text, lower);
    }

    @Override
    public List<ToolCall> readFrom(String text) {
        List<ToolCall> calls = new ArrayList<>();
        readWrappedCalls(text, calls);
        readNamedFunctionTags(text, calls);
        readCallsAfterMarkers(text, calls);
        if (calls.isEmpty()) {
            readBareJson(text, calls);
        }
        return calls;
    }

    /** {@code <tool_call>...</tool_call>}: each block holds one call, or an array of them. */
    private void readWrappedCalls(String text, List<ToolCall> calls) {
        Matcher wrapped = TOOL_CALL_TAG.matcher(text);
        while (wrapped.find()) {
            addValue(JsonText.valueIn(wrapped.group(1).trim()), calls);
        }
    }

    /** {@code <function=read>{...}</function>}: the tag names the tool, the body is its arguments. */
    private void readNamedFunctionTags(String text, List<ToolCall> calls) {
        Matcher tagged = FUNCTION_TAG.matcher(text);
        while (tagged.find()) {
            Map<String, Object> arguments = JsonText.objectIn(tagged.group(2).trim());
            ToolCall            call      = ToolCall.of(tagged.group(1), arguments, name());
            if (call != null) {
                calls.add(call);
            }
        }
    }

    /** {@code [TOOL_CALLS]} and {@code <|python_tag|>}: a JSON value follows the marker. */
    private void readCallsAfterMarkers(String text, List<ToolCall> calls) {
        Matcher marker = LEADING_MARKER.matcher(text);
        while (marker.find()) {
            addValue(JsonText.valueIn(JsonText.valueAt(text, marker.end())), calls);
        }
    }

    /** A reply that is itself the JSON, with no marker in front of it. */
    private void readBareJson(String text, List<ToolCall> calls) {
        String json = JsonText.valueAt(text, 0);
        if (json == null || json.length() != text.trim().length()) {
            // Anything else in the reply means the JSON is being quoted or discussed, not written as
            // a call. A call the model actually made stands on its own or carries a marker.
            return;
        }
        addValue(JsonText.valueIn(json), calls);
    }

    /**
     * Reads whatever a JSON value turned out to be: one call, a list of them, or a reply listing
     * them under {@code tool_calls}.
     *
     * @param value the parsed JSON value; may be {@code null}
     * @param calls the list being built
     */
    private void addValue(Object value, List<ToolCall> calls) {
        if (value instanceof List<?> listed) {
            for (Object element : listed) {
                addObject(JsonText.asObject(element), calls);
            }
            return;
        }
        Map<String, Object> object = JsonText.asObject(value);
        if (object == null) {
            return;
        }
        if (object.get(CALLS_KEY) instanceof List<?> listed) {
            for (Object element : listed) {
                addObject(JsonText.asObject(element), calls);
            }
            return;
        }
        addObject(object, calls);
    }

    private void addObject(Map<String, Object> object, List<ToolCall> calls) {
        ToolCall call = ToolCallFields.readFrom(object, name());
        if (call != null) {
            calls.add(call);
        }
    }

    /**
     * Whether an unmarked reply is shaped like a tool call rather than like a JSON answer.
     *
     * @param text  the reply
     * @param lower the same reply, already lower-cased
     * @return whether it is worth reading as a call
     */
    private static boolean looksLikeBareJsonCall(String text, String lower) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false;
        }
        if (lower.contains("\"" + CALLS_KEY + "\"")) {
            return true;
        }
        if (!lower.contains("\"name\"")) {
            return false;
        }
        for (String marker : ARGUMENT_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
