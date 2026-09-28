package com.eonmux.cadetcoder.ai.parsing.toolcalls;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reading one tool call out of a JSON object, whichever vendor's field names it uses.
 *
 * <h2>Why the field names are a list rather than a constant</h2>
 *
 * <p>Every vendor spells the same two facts differently. The tool's name sits under {@code name},
 * or nested under {@code function}, {@code functionCall} or {@code toolUse}. Its arguments sit under
 * {@code arguments}, {@code parameters}, {@code input} or {@code args}, and are sometimes a JSON
 * object and sometimes a string holding JSON -- the OpenAI wire sends a string, and a reader that
 * expects an object drops every argument of every call it makes. Reading all the spellings in one
 * place is what keeps each notation's reader down to the part that is actually particular to it.</p>
 */
final class ToolCallFields {

    /** Keys a call's arguments arrive under, in the order they are preferred. */
    private static final String[] ARGUMENT_KEYS = {"arguments", "parameters", "input", "args"};

    /** Keys a call's tool name arrives under. Cohere spells it {@code tool_name}. */
    private static final String[] NAME_KEYS = {"name", "tool_name"};

    /** Keys whose value is itself the call, wrapping the name and arguments one level down. */
    private static final String[] WRAPPER_KEYS = {"function", "functionCall", "function_call",
                                                  "toolUse", "tool_use"};

    private ToolCallFields() {
    }

    /**
     * Reads a call from a JSON object of any of the documented shapes.
     *
     * @param call   the object to read; may be {@code null}
     * @param syntax the notation it was read from, recorded on the result
     * @return the call, or {@code null} when the object names no tool
     */
    static ToolCall readFrom(Map<String, Object> call, String syntax) {
        if (call == null) {
            return null;
        }
        Map<String, Object> wrapped = unwrapped(call);
        String              name    = qualifiedName(call, wrapped);
        if (name == null) {
            return null;
        }
        return ToolCall.of(name, argumentsOf(wrapped), syntax);
    }

    /**
     * The object that actually carries the name and arguments.
     *
     * @param call the call object as written
     * @return the wrapped object when the call nests one, otherwise the call itself
     */
    private static Map<String, Object> unwrapped(Map<String, Object> call) {
        for (String key : WRAPPER_KEYS) {
            Map<String, Object> inner = JsonText.asObject(call.get(key));
            if (inner != null) {
                return inner;
            }
        }
        return call;
    }

    /**
     * The tool name, with an outer namespace put back in front of it.
     *
     * <p>DeepSeek carries the namespace beside the function rather than inside it, and the name to
     * look up is the pair. {@link ToolCall#bareName} strips the namespace again; joining them here
     * keeps that one rule in one place instead of leaving a namespaced call half-read.</p>
     *
     * @param call    the call object as written
     * @param wrapped the object carrying the name
     * @return the name, or {@code null} when neither object names one
     */
    private static String qualifiedName(Map<String, Object> call, Map<String, Object> wrapped) {
        String name = named(wrapped);
        if (name == null) {
            name = named(call);
        }
        if (name == null) {
            return null;
        }
        String namespace = namespaceOf(wrapped);
        if (namespace == null) {
            namespace = namespaceOf(call);
        }
        return namespace == null ? name : namespace + "::" + name;
    }

    /**
     * @param object the object to look in
     * @return the tool name it carries, or {@code null} when it names none
     */
    private static String named(Map<String, Object> object) {
        for (String key : NAME_KEYS) {
            String name = text(object.get(key));
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    /**
     * A call's namespace, written either as a name or as an object carrying one.
     *
     * @param object the object to look in
     * @return the namespace name, or {@code null} when it names none
     */
    private static String namespaceOf(Map<String, Object> object) {
        Object namespace = object.get("namespace");
        if (namespace instanceof Map<?, ?>) {
            return text(JsonText.asObject(namespace).get("name"));
        }
        return text(namespace);
    }

    /**
     * A call's arguments, whether they were written as an object or as a string holding one.
     *
     * @param wrapped the object carrying the arguments
     * @return the arguments; empty when the call takes none
     */
    private static Map<String, Object> argumentsOf(Map<String, Object> wrapped) {
        for (String key : ARGUMENT_KEYS) {
            Object value = wrapped.get(key);
            if (value == null) {
                continue;
            }
            Map<String, Object> arguments = JsonText.asObject(value);
            if (arguments != null) {
                return arguments;
            }
            if (value instanceof String written && !written.isBlank()) {
                arguments = JsonText.objectIn(written);
                if (arguments != null) {
                    return arguments;
                }
            }
        }
        return new LinkedHashMap<>();
    }

    /**
     * @param value a JSON value
     * @return it as trimmed text, or {@code null} when it is absent or blank
     */
    private static String text(Object value) {
        if (!(value instanceof String written)) {
            return null;
        }
        String trimmed = written.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
