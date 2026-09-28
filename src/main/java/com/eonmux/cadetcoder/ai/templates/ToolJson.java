package com.eonmux.cadetcoder.ai.templates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Renders the tool definitions and tool calls that templates embed in a prompt.
 *
 * <h2>Why this is not string concatenation</h2>
 *
 * <p>A prompt says "you are provided with function signatures" and then hands the model a JSON
 * document. The model parses it. Building that document with {@code String.format} fails in three
 * ways that all look fine until they don't: a quote or newline in a description ends the string
 * early and the document stops parsing; an argument map interpolated with {@code %s} arrives as
 * {@code {k=v}}, which is Java's {@code toString} and not JSON; and a value that has no obvious
 * text form gets dropped. Jackson is already a dependency and gets all three right.</p>
 *
 * <h2>Values supplied as text</h2>
 *
 * <p>A schema or an argument list often arrives already serialized. Such a value is embedded as
 * the JSON it is, not as a quoted string containing JSON, so the model sees one document rather
 * than a document with a string in the middle of it. Text that is not JSON is quoted and escaped,
 * which is the only reading left once it has failed to parse.</p>
 */
final class ToolJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolJson() {
    }

    /**
     * Renders one tool definition.
     *
     * <p>{@code parameters} is the tool's JSON schema. It is omitted when absent rather than
     * replaced with an empty schema: a tool that declared nothing and a tool that takes no
     * arguments are different claims, and only the caller knows which one this is.</p>
     *
     * @param name        the tool's name
     * @param description what the tool does
     * @param parameters  the parameter schema, or {@code null} if none was supplied
     * @return a JSON object, always parseable
     */
    static String toolDefinition(String name, String description, Object parameters) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", name);
        node.put("description", description);
        if (parameters != null) {
            node.set("parameters", asJson(parameters));
        }
        return node.toString();
    }

    /**
     * Renders one tool call.
     *
     * <p>Absent arguments become an empty object, because {@code "arguments": null} reads as a
     * missing field to most callers whereas {@code {}} says plainly that the tool was called with
     * nothing.</p>
     *
     * @param name      the tool being called
     * @param arguments the arguments, or {@code null} if the call carried none
     * @return a JSON object, always parseable
     */
    static String toolCall(String name, Object arguments) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", name);
        node.set("arguments", arguments == null ? MAPPER.createObjectNode() : asJson(arguments));
        return node.toString();
    }

    /**
     * Converts one supplied value to a JSON node.
     *
     * <p>Never throws. This runs while a prompt is being assembled, and a value Jackson cannot map
     * is not a reason to fail the request; the value's own text form is a lossy but honest last
     * resort, and it keeps the surrounding document valid.</p>
     */
    private static JsonNode asJson(Object value) {
        if (value instanceof String text) {
            try {
                return MAPPER.readTree(text);
            } catch (Exception notJson) {
                return MAPPER.getNodeFactory().textNode(text);
            }
        }
        try {
            return MAPPER.valueToTree(value);
        } catch (Exception unmappable) {
            return MAPPER.getNodeFactory().textNode(String.valueOf(value));
        }
    }
}
