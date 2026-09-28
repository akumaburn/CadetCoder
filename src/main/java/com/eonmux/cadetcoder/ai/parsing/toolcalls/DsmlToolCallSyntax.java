package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek's DSML tool calls.
 *
 * <p>Documented in the model's own encoding reference. A call is written:</p>
 *
 * <pre>
 * &lt;｜DSML｜ calls&gt;
 * &lt;｜DSML｜ invoke name="read"&gt;
 * &lt;｜DSML｜ parameter name="file_path" string="true"&gt;src/Main.java&lt;/｜DSML｜ parameter&gt;
 * &lt;/｜DSML｜ invoke&gt;
 * &lt;/｜DSML｜ calls&gt;
 * </pre>
 *
 * <h2>Why the tags are matched loosely</h2>
 *
 * <p>Three things vary and none of them changes what the call says. V4.1 puts a space after the
 * {@code ｜DSML｜} token and V4 does not, so both spacings are in use and a model asked to be
 * concise mixes them. The bar is the fullwidth vertical line U+FF5C, but a reply that has been
 * round-tripped through a gateway, a log or a terminal that cannot render it arrives with the plain
 * ASCII bar instead. And the wrapping {@code calls} block is spelled {@code tool_calls} in V4. A
 * reader that insisted on one spelling of each would refuse a reply that names the command, the
 * arguments and the value types exactly.</p>
 */
final class DsmlToolCallSyntax implements ToolCallSyntax {

    /** The marker token, with either bar, and either spelling of the block that wraps the calls. */
    private static final String BAR = "[|\\uFF5C]";

    /** An opening DSML tag of a named kind, capturing its attributes. */
    private static final String OPEN = "<" + BAR + "DSML" + BAR + "\\s*";

    /** A closing DSML tag of a named kind. */
    private static final String CLOSE = "</" + BAR + "DSML" + BAR + "\\s*";

    private static final Pattern INVOKE = Pattern.compile(
            OPEN + "invoke\\b([^>]*)>(.*?)" + CLOSE + "invoke\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern PARAMETER = Pattern.compile(
            OPEN + "parameter\\b([^>]*)>(.*?)" + CLOSE + "parameter\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Enough of the notation to be worth reading: the marker token itself. */
    private static final Pattern MARKER = Pattern.compile(BAR + "DSML" + BAR,
                                                          Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "DSML";
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
     * The parameters an invocation's body names.
     *
     * <p>A parameter declares whether its value is a raw string or JSON. The declaration is the only
     * way to tell a path that happens to read as a number, such as {@code 2024}, from a count, and
     * the model is the one that knows which it meant. An undeclared parameter is taken as a string,
     * which is what every notation without the attribute means by it.</p>
     *
     * @param body the text between the invoke tags
     * @return the named parameters, in the order they were written
     */
    private static Map<String, Object> parametersIn(String body) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        Matcher             parameter  = PARAMETER.matcher(body);
        while (parameter.find()) {
            String attributes = parameter.group(1);
            String key        = TagAttributes.valueOf(attributes, "name");
            if (key == null) {
                continue;
            }
            String value = TagBody.valueIn(parameter.group(2));
            parameters.put(key, isJson(attributes) ? jsonOrText(value) : value);
        }
        return parameters;
    }

    /**
     * @param attributes an opening tag's attributes
     * @return whether the value is declared to be JSON rather than a raw string
     */
    private static boolean isJson(String attributes) {
        return "false".equalsIgnoreCase(TagAttributes.valueOf(attributes, "string"));
    }

    /**
     * A value declared as JSON, read as JSON when it is any.
     *
     * <p>A declaration is not a guarantee. A model that writes {@code string="false"} over a value
     * that is not JSON has made a clerical mistake about its own notation, and refusing the whole
     * call over it loses a command the reply otherwise stated plainly.</p>
     *
     * @param value the raw value
     * @return the parsed value, or the raw text when it does not parse
     */
    private static Object jsonOrText(String value) {
        Object parsed = JsonText.valueIn(value);
        return parsed == null ? value : parsed;
    }
}
