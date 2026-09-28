package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Harmony tool calls OpenAI's open-weight models write.
 *
 * <pre>
 * &lt;|start|&gt;assistant&lt;|channel|&gt;commentary to=functions.read &lt;|constrain|&gt;json&lt;|message|&gt;{"file_path":"src/Main.java"}&lt;|call|&gt;
 * </pre>
 *
 * <h2>Why the call is read from the address rather than from a name field</h2>
 *
 * <p>Harmony addresses a call the way a message is addressed: the recipient is the tool, written
 * after {@code to=} in the channel header, and the body is only the arguments. There is no name in
 * the JSON to find, so a reader looking for one finds nothing in a reply that named the tool
 * plainly. The {@code functions.} in front of the name says which channel the tool is reached on
 * and is not part of the name.</p>
 *
 * <h2>Why several terminators are accepted</h2>
 *
 * <p>A local server may stop the model on any of {@code <|call|>}, {@code <|end|>} or
 * {@code <|return|>}, and a server that stops on the token strips it, so the last call in a reply
 * frequently has no terminator at all. Ending the body at the end of the reply in that case reads a
 * complete call where insisting on the token read none.</p>
 */
final class HarmonyToolCallSyntax implements ToolCallSyntax {

    private static final Pattern CALL = Pattern.compile(
            "to\\s*=\\s*([A-Za-z0-9_.:-]+)"
            + "[^<]*(?:<\\|constrain\\|>[^<]*)?"
            + "<\\|message\\|>(.*?)"
            + "(?=<\\|call\\|>|<\\|end\\|>|<\\|return\\|>|<\\|start\\|>|\\z)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public String name() {
        return "Harmony";
    }

    @Override
    public boolean appearsIn(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("<|message|>") && lower.contains("to=");
    }

    @Override
    public List<ToolCall> readFrom(String text) {
        List<ToolCall> calls = new ArrayList<>();
        Matcher        call  = CALL.matcher(text);
        while (call.find()) {
            Map<String, Object> arguments = JsonText.objectIn(call.group(2).trim());
            ToolCall            read      = ToolCall.of(call.group(1), arguments, name());
            if (read != null) {
                calls.add(read);
            }
        }
        return calls;
    }
}
