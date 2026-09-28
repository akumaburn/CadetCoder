package com.eonmux.cadetcoder.ai.templates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tool JSON embedded in a prompt is read by a model, so it has to be valid JSON and it has to
 * carry everything the model needs. These tests hold both halves of that: every rendering parses,
 * and nothing the caller supplied goes missing on the way in.
 */
class ToolJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return fail("Not valid JSON: " + json, e);
        }
    }

    // ---------------------------------------------------------------- definitions

    @Test
    void aDefinitionCarriesItsParameterSchema() {
        Map<String, Object> schema = Map.of("type", "object");

        JsonNode json = parse(ToolJson.toolDefinition("calculate", "Do sums", schema));

        assertEquals("calculate", json.get("name").asText());
        assertEquals("Do sums", json.get("description").asText());
        assertEquals("object", json.get("parameters").get("type").asText(),
                     "A tool signature without its parameters tells the model nothing about how to call it");
    }

    @Test
    void aSchemaSuppliedAsAJsonStringIsEmbeddedAsJson() {
        JsonNode json = parse(ToolJson.toolDefinition("t", "d", "{\"type\": \"object\"}"));

        assertTrue(json.get("parameters").isObject(),
                   "A schema handed over as text is still a schema, not a string literal");
        assertEquals("object", json.get("parameters").get("type").asText());
    }

    @Test
    void absentParametersAreOmittedRatherThanInvented() {
        JsonNode json = parse(ToolJson.toolDefinition("simple_tool", "None needed", null));

        assertEquals("simple_tool", json.get("name").asText());
        assertFalse(json.has("parameters"));
    }

    @Test
    void aQuoteInADescriptionDoesNotBreakTheDocument() {
        JsonNode json = parse(ToolJson.toolDefinition("t", "Wraps the \"value\" in quotes", null));

        assertEquals("Wraps the \"value\" in quotes", json.get("description").asText());
    }

    @Test
    void aNewlineInADescriptionDoesNotBreakTheDocument() {
        JsonNode json = parse(ToolJson.toolDefinition("t", "First line\nSecond line", null));

        assertEquals("First line\nSecond line", json.get("description").asText());
    }

    // ---------------------------------------------------------------- calls

    @Test
    void aCallKeepsArgumentsThatWereNotAlreadyText() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("path", "src/Main.java");
        arguments.put("limit", 20);

        JsonNode json = parse(ToolJson.toolCall("read", arguments));

        assertEquals("read", json.get("name").asText());
        assertEquals("src/Main.java", json.get("arguments").get("path").asText());
        assertEquals(20, json.get("arguments").get("limit").asInt());
    }

    @Test
    void aTextArgumentThatIsJsonIsEmbeddedAsJson() {
        JsonNode json = parse(ToolJson.toolCall("calc", "{\"x\": 5, \"y\": 10}"));

        assertTrue(json.get("arguments").isObject());
        assertEquals(5, json.get("arguments").get("x").asInt());
    }

    @Test
    void aTextArgumentThatIsNotJsonBecomesAQuotedString() {
        JsonNode json = parse(ToolJson.toolCall("echo", "just some prose"));

        assertTrue(json.get("arguments").isTextual());
        assertEquals("just some prose", json.get("arguments").asText());
    }

    @Test
    void absentArgumentsBecomeAnEmptyObject() {
        JsonNode json = parse(ToolJson.toolCall("no_args_tool", null));

        assertEquals("no_args_tool", json.get("name").asText());
        assertTrue(json.get("arguments").isObject());
        assertEquals(0, json.get("arguments").size());
    }

    @Test
    void aQuoteInAnArgumentDoesNotBreakTheDocument() {
        JsonNode json = parse(ToolJson.toolCall("grep", Map.of("pattern", "say \"hello\"")));

        assertEquals("say \"hello\"", json.get("arguments").get("pattern").asText());
    }

    @Test
    void aNameIsEscapedLikeAnyOtherValue() {
        JsonNode json = parse(ToolJson.toolCall("odd\"name", null));

        assertEquals("odd\"name", json.get("name").asText());
    }

    @Test
    void anObjectThatCannotBeSerializedDegradesToTextRatherThanThrowing() {
        Object unserializable = new Object() {
            @Override
            public String toString() {
                return "opaque";
            }
        };

        JsonNode json = parse(ToolJson.toolCall("t", unserializable));

        assertTrue(json.get("arguments").isTextual(),
                   "Prompt construction must not fail on an argument Jackson cannot map");
        assertEquals("opaque", json.get("arguments").asText());
    }
}
