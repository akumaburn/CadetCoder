package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finding and reading the JSON a model embeds in prose.
 *
 * <h2>Why the extent of the JSON is scanned rather than matched</h2>
 *
 * <p>Most tool-call notations are a marker followed by a JSON value: {@code [TOOL_CALLS]} then an
 * array, {@code <|python_tag|>} then an object, {@code <|message|>} then an object. Where the value
 * ends is a question about nesting, and a regular expression cannot count nesting -- a pattern that
 * stops at the first {@code }} truncates every call whose arguments hold an object, and one that
 * stops at the last swallows whatever prose follows. Both produce a JSON parse failure on a reply
 * that was perfectly well formed, so the extent is scanned with the brace depth counted and string
 * literals skipped.</p>
 */
final class JsonText {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonText() {
    }

    /**
     * The complete JSON object or array that begins at or after an offset.
     *
     * @param text the reply being read
     * @param from the offset to begin looking at
     * @return the JSON value's text, or {@code null} when none begins there or it is never closed
     */
    static String valueAt(String text, int from) {
        int start = from;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (start >= text.length()) {
            return null;
        }
        char opening = text.charAt(start);
        char closing = opening == '{' ? '}' : opening == '[' ? ']' : 0;
        if (closing == 0) {
            return null;
        }

        int     depth    = 0;
        boolean inString = false;
        boolean escaped  = false;
        for (int at = start; at < text.length(); at++) {
            char character = text.charAt(at);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == opening) {
                depth++;
            } else if (character == closing) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, at + 1);
                }
            }
        }
        return null;
    }

    /**
     * The first complete JSON object or array anywhere in a fragment.
     *
     * <p>For a notation that puts its arguments inside a fenced code block, where the JSON begins
     * after a marker whose length is not fixed.</p>
     *
     * @param text the fragment to search
     * @return the JSON value's text, or {@code null} when it holds none
     */
    static String firstValueIn(String text) {
        if (text == null) {
            return null;
        }
        for (int at = 0; at < text.length(); at++) {
            char character = text.charAt(at);
            if (character == '{' || character == '[') {
                return valueAt(text, at);
            }
        }
        return null;
    }

    /**
     * Reads JSON text as an object.
     *
     * @param json the text to read; may be {@code null}
     * @return its fields, or {@code null} when it is not a JSON object
     */
    static Map<String, Object> objectIn(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object value = MAPPER.readValue(json, Object.class);
            return asObject(value);
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }

    /**
     * Reads JSON text as a value of any shape.
     *
     * @param json the text to read
     * @return the value, or {@code null} when the text is not JSON
     */
    static Object valueIn(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }

    /**
     * A parsed value as a string-keyed map, when it is one.
     *
     * @param value a value read from JSON; may be {@code null}
     * @return its entries with string keys, or {@code null} when it is not a map
     */
    static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                fields.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return fields;
    }

    /**
     * Writes a value as JSON text.
     *
     * @param value the value to write
     * @return its JSON form
     * @throws IllegalArgumentException when the value cannot be written as JSON
     */
    static String written(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException notWritable) {
            throw new IllegalArgumentException("value cannot be written as JSON", notWritable);
        }
    }
}
