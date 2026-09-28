package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The invoke-tag tool calls Claude writes, and the many notations modelled on them.
 *
 * <pre>
 * &lt;function_calls&gt;
 * &lt;invoke name="read"&gt;
 * &lt;parameter name="file_path"&gt;src/Main.java&lt;/parameter&gt;
 * &lt;/invoke&gt;
 * &lt;/function_calls&gt;
 * </pre>
 *
 * <h2>Why the wrapping block is not required</h2>
 *
 * <p>The invocations are what say which command to run; the block around them only groups a batch.
 * A model that writes one call commonly writes the {@code invoke} on its own, and several vendors'
 * variants of this notation name the wrapper differently or leave it out entirely. Requiring it
 * would refuse a call that is complete. The tag names may also carry a namespace prefix, which
 * names the schema the tags come from rather than the tool, so it is read past.</p>
 *
 * <h2>Why every value is a string</h2>
 *
 * <p>This notation, unlike DSML, has no way to say that a value is JSON, so its values are text.
 * Guessing otherwise would read a path of digits as a number and a parameter whose value happens to
 * begin with a brace as an object.</p>
 */
final class InvokeTagToolCallSyntax implements ToolCallSyntax {

    /** An optional namespace prefix on a tag name, such as the {@code antml:} in {@code antml:invoke}. */
    private static final String PREFIX = "(?:[A-Za-z_][A-Za-z0-9_.-]*:)?";

    private static final Pattern INVOKE = Pattern.compile(
            "<" + PREFIX + "invoke\\b([^>]*)>(.*?)</" + PREFIX + "invoke\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern PARAMETER = Pattern.compile(
            "<" + PREFIX + "parameter\\b([^>]*)>(.*?)</" + PREFIX + "parameter\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern MARKER = Pattern.compile("<" + PREFIX + "invoke[\\s>]",
                                                          Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "invoke tags";
    }

    @Override
    public boolean appearsIn(String text) {
        return MARKER.matcher(text).find();
    }

    @Override
    public List<ToolCall> readFrom(String text) {
        List<ToolCall> calls  = new ArrayList<>();
        Matcher        invoke = INVOKE.matcher(text);
        while (invoke.find()) {
            ToolCall call = ToolCall.of(TagAttributes.valueOf(invoke.group(1), "name"),
                                        parametersIn(invoke.group(2)), name());
            if (call != null) {
                calls.add(call);
            }
        }
        return calls;
    }

    /**
     * @param body the text between the invoke tags
     * @return the named parameters, in the order they were written
     */
    private static Map<String, Object> parametersIn(String body) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        Matcher             parameter  = PARAMETER.matcher(body);
        while (parameter.find()) {
            String key = TagAttributes.valueOf(parameter.group(1), "name");
            if (key != null) {
                parameters.put(key, TagBody.valueIn(parameter.group(2)));
            }
        }
        return parameters;
    }
}
