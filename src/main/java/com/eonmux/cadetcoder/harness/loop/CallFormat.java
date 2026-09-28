package com.eonmux.cadetcoder.harness.loop;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.tools.ToolCatalog;
import com.eonmux.cadetcoder.harness.tools.ToolParam;
import com.eonmux.cadetcoder.harness.tools.ToolSchema;
import com.eonmux.cadetcoder.harness.tools.ToolType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a tool call is written into a reply, and how one is read back out.
 *
 * <h2>Why calls are not JSON</h2>
 *
 * <p>There is no tool channel underneath this harness: a reply is one string of text. The argument
 * that decides the format is {@code write_model}, whose value is a whole program -- braces, quotes
 * and newlines throughout. Escaped into a JSON string it becomes the thing a language model most
 * reliably gets wrong, and every time it does, a deliberation is spent on a refusal that says
 * nothing about the world. Between tags, a program is written exactly as it is written anywhere
 * else.</p>
 *
 * <h2>Why reading decides nothing but reading</h2>
 *
 * <p>The only judgement made here is which kind of value an argument holds, and that is taken from
 * the tool's own declaration. Whether the tool exists, whether it takes that argument and whether
 * the value will do are all answered by the toolbox, in a refusal the agent can act on. A reader
 * that ruled on them too would rule differently the first time the catalog changed.</p>
 *
 * <h2>Why an opening tag alone is not a call</h2>
 *
 * <p>An agent explaining itself writes about calls as well as writing them, so a {@code <call>} that
 * names no tool is prose. What must never be read as prose is a reply cut off by a token limit,
 * because "no calls" is exactly what an agent that has finished looks like, and a harness that reads
 * it that way nudges an agent that never stopped talking. Anything opened and left unclosed is
 * therefore complained about, and so is a close that shuts nothing.</p>
 */
public final class CallFormat {

    /** Where a call begins, before the tool it names. */
    private static final String CALL_OPEN = "<call";

    /** Where a call ends. */
    private static final String CALL_CLOSE = "</call>";

    /** Where an argument begins, before the name it carries. */
    private static final String ARG_OPEN = "<arg";

    /** Where an argument ends, and therefore the one sequence a value cannot contain. */
    private static final String ARG_CLOSE = "</arg>";

    /** The attribute naming the tool a call is for. */
    private static final String TOOL_ATTRIBUTE = "tool";

    /** The attribute naming the argument a value is for. */
    private static final String NAME_ATTRIBUTE = "name";

    private static final String DESCRIPTION = """
            HOW TO CALL A TOOL
              There is no tool channel here. You act by writing calls into your reply:

                <call tool="write_model">
                <arg name="note">the corridor may run further than it looks</arg>
                <arg name="source">
                fn parse(obs) { return {"pos": obs.pos}; }
                // ... the rest of the model
                </arg>
                </call>

                <call tool="observe"/>

              * One <arg> for each argument, named exactly as the tool declares it, and no
                argument written twice.
              * A value is everything between the tags, with the surrounding blank space
                removed. Braces, quotes and newlines survive it untouched, which is why a
                model is written here rather than escaped into JSON. The one thing a value
                cannot contain is the closing tag itself.
              * An argument the tool declares as a list is written as JSON:
                <arg name="actions">[{"move": 1}, {"move": -1}]</arg>
              * A tool that takes no arguments is written as the second call above.
              * Everything outside a call block is your own thinking, and costs nothing.
              * Several calls in one reply run in the order you wrote them, and the harness
                answers all of them before you write again.""";

    private CallFormat() {
    }

    /** The format as the agent is told it. */
    public static String describe() {
        return DESCRIPTION;
    }

    /**
     * Reads every call an agent wrote, and everything about the reply that could not be read.
     *
     * @param reply what the agent said, in full
     * @return the calls, and the complaints to put to the agent
     */
    public static CallReading read(String reply) {
        if (reply == null || reply.isEmpty()) {
            return new CallReading(List.of(), List.of());
        }
        List<ToolRequest> calls      = new ArrayList<>();
        List<String>      complaints = new ArrayList<>();
        int               at         = 0;
        int               prose      = 0;
        while (at < reply.length()) {
            int open = reply.indexOf(CALL_OPEN, at);
            if (open < 0) {
                break;
            }
            int tagEnd = reply.indexOf('>', open);
            if (tagEnd < 0) {
                complaints.add("a call was opened and never finished; a call is written "
                               + "<call tool=\"name\"> ... " + CALL_CLOSE);
                return new CallReading(calls, complaints);
            }
            String tag  = reply.substring(open + CALL_OPEN.length(), tagEnd);
            String tool = attribute(tag, TOOL_ATTRIBUTE);
            if (tool == null) {
                at = open + CALL_OPEN.length();
                continue;
            }
            strayClose(reply.substring(prose, open), complaints);
            if (selfClosing(tag)) {
                calls.add(new ToolRequest(tool, Map.of()));
                at = tagEnd + 1;
                prose = at;
                continue;
            }
            int close = reply.indexOf(CALL_CLOSE, tagEnd);
            if (close < 0) {
                complaints.add("the call to " + tool + " was never closed with " + CALL_CLOSE
                               + "; if your reply ran out of room, write it again and shorter");
                return new CallReading(calls, complaints);
            }
            ToolRequest call = body(tool, reply.substring(tagEnd + 1, close), complaints);
            if (call != null) {
                calls.add(call);
            }
            at    = close + CALL_CLOSE.length();
            prose = at;
        }
        strayClose(reply.substring(Math.min(prose, reply.length())), complaints);
        return new CallReading(calls, complaints);
    }

    /**
     * Reads the arguments of one call.
     *
     * @param tool       the tool the call names
     * @param body       everything between the call's tags
     * @param complaints where anything unreadable is reported
     * @return the call, or {@code null} when part of it could not be read
     */
    private static ToolRequest body(String tool, String body, List<String> complaints) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        int                 at        = 0;
        while (at < body.length()) {
            int open = body.indexOf(ARG_OPEN, at);
            if (open < 0) {
                break;
            }
            int tagEnd = body.indexOf('>', open);
            if (tagEnd < 0) {
                complaints.add("an argument of the call to " + tool + " was opened and never "
                               + "finished; an argument is written <arg name=\"which\">value"
                               + ARG_CLOSE);
                return null;
            }
            String name = attribute(body.substring(open + ARG_OPEN.length(), tagEnd),
                                    NAME_ATTRIBUTE);
            if (name == null) {
                complaints.add("an argument of the call to " + tool + " says which argument it is "
                               + "for; write <arg name=\"which\">value" + ARG_CLOSE);
                return null;
            }
            int close = body.indexOf(ARG_CLOSE, tagEnd);
            if (close < 0) {
                complaints.add("the argument " + name + " of the call to " + tool + " was never "
                               + "closed with " + ARG_CLOSE);
                return null;
            }
            if (arguments.containsKey(name)) {
                complaints.add("the call to " + tool + " gives " + name + " twice, so there is no "
                               + "telling which one you meant; write it once");
                return null;
            }
            arguments.put(name, typed(tool, name, body.substring(tagEnd + 1, close).strip()));
            at = close + ARG_CLOSE.length();
        }
        return new ToolRequest(tool, arguments);
    }

    /**
     * One argument as the kind of value its tool declares.
     *
     * <h2>Why a list that will not read is passed on as it was written</h2>
     *
     * <p>A list is the one kind the toolbox will not take as text, so it is read here or not at
     * all. When it will not read, what the agent wrote is handed on unchanged: the toolbox then
     * refuses it by name, quoting it back, which is the answer that says what to write instead.
     * Reading it as an empty list, or as one element, would run the call on something the agent
     * never asked for.</p>
     */
    private static Object typed(String tool, String argument, String written) {
        ToolSchema schema    = ToolCatalog.of(tool);
        ToolParam  parameter = schema == null ? null : schema.parameter(argument);
        if (parameter == null || parameter.type() != ToolType.LIST) {
            return written;
        }
        try {
            return Json.parse(written);
        } catch (IllegalArgumentException notJson) {
            return written;
        }
    }

    /** Whether a call's opening tag is the whole call, as {@code <call tool="observe"/>} is. */
    private static boolean selfClosing(String tag) {
        return tag.stripTrailing().endsWith("/");
    }

    /**
     * Reports a close that shuts nothing, which is the shape a call that named no tool leaves
     * behind.
     *
     * @param text       a stretch of the reply that belongs to no call
     * @param complaints where it is reported
     */
    private static void strayClose(String text, List<String> complaints) {
        if (text.contains(CALL_CLOSE)) {
            complaints.add("a " + CALL_CLOSE + " here closes nothing; a call opens with "
                           + "<call tool=\"name\">, naming the tool it is for");
        }
    }

    /**
     * The value of one attribute of a tag, or {@code null} when the tag does not carry it.
     *
     * @param tag  everything between the tag's name and its {@code >}
     * @param name which attribute
     * @return what it was set to
     */
    private static String attribute(String tag, String name) {
        int at = -1;
        while ((at = tag.indexOf(name + "=", at + 1)) >= 0) {
            if (at == 0 || Character.isWhitespace(tag.charAt(at - 1))) {
                return quoted(tag, at + name.length() + 1);
            }
        }
        return null;
    }

    /**
     * What sits between the quotes an attribute's value opens with.
     *
     * @param tag     the tag being read
     * @param opening where the opening quote should be
     * @return the value, or {@code null} when it is not quoted or never closed
     */
    private static String quoted(String tag, int opening) {
        if (opening >= tag.length()) {
            return null;
        }
        char quote = tag.charAt(opening);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        int closing = tag.indexOf(quote, opening + 1);
        return closing < 0 ? null : tag.substring(opening + 1, closing);
    }
}
