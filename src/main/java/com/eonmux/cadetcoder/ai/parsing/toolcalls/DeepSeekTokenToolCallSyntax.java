package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The token-delimited tool calls DeepSeek V3 and R1 write.
 *
 * <p>The notation DSML replaced. Its chat template renders a call as:</p>
 *
 * <pre>
 * &lt;｜tool▁calls▁begin｜&gt;&lt;｜tool▁call▁begin｜&gt;function&lt;｜tool▁sep｜&gt;read
 * ```json
 * {"file_path": "src/Main.java"}
 * ```&lt;｜tool▁call▁end｜&gt;&lt;｜tool▁calls▁end｜&gt;
 * </pre>
 *
 * <h2>Why it is read as well as DSML</h2>
 *
 * <p>V3 and R1 are still what a great many endpoints serve, under their own names and behind
 * gateways that do not say which generation answered. A reader for V4's notation alone would refuse
 * the call the older models actually make, which is the same failure this whole package exists to
 * remove.</p>
 *
 * <h2>Why the tokens are matched loosely</h2>
 *
 * <p>The same reason DSML's are. The bar is U+FF5C and the word separator is U+2581, and both are
 * replaced by their ASCII lookalikes whenever a reply passes through something that cannot carry
 * them. The call is unchanged either way.</p>
 */
final class DeepSeekTokenToolCallSyntax implements ToolCallSyntax {

    /** The bar that opens and closes a special token, fullwidth or ASCII. */
    private static final String BAR = "[|\\uFF5C]";

    /** The separator between the words of a token name, U+2581 or an ASCII stand-in. */
    private static final String WORD = "[\\u2581_ ]";

    private static String token(String... words) {
        return "<" + BAR + String.join(WORD, words) + BAR + ">";
    }

    /**
     * One call: its type, its name, and the fenced JSON of its arguments.
     *
     * <p>The end token is optional because a server that stops on it strips it, which leaves the
     * last call in a reply without one.</p>
     */
    private static final Pattern CALL = Pattern.compile(
            token("tool", "call", "begin") + "\\s*[A-Za-z_]*\\s*" + token("tool", "sep")
            + "\\s*([^\\s<`]+)(.*?)(?=" + token("tool", "call", "begin") + "|"
            + token("tool", "call", "end") + "|" + token("tool", "calls", "end") + "|\\z)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern MARKER = Pattern.compile(token("tool", "sep"),
                                                          Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "DeepSeek tool tokens";
    }

    @Override
    public boolean appearsIn(String text) {
        return MARKER.matcher(text).find();
    }

    @Override
    public List<ToolCall> readFrom(String text) {
        List<ToolCall> calls = new ArrayList<>();
        Matcher        call  = CALL.matcher(text);
        while (call.find()) {
            Map<String, Object> arguments = JsonText.objectIn(JsonText.firstValueIn(call.group(2)));
            ToolCall            read      = ToolCall.of(call.group(1), arguments, name());
            if (read != null) {
                calls.add(read);
            }
        }
        return calls;
    }
}
