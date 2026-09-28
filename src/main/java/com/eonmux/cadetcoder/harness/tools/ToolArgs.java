package com.eonmux.cadetcoder.harness.tools;

import com.eonmux.cadetcoder.harness.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arguments of one tool call, read against the tool that was called.
 *
 * <h2>Why an unknown argument is a refusal</h2>
 *
 * <p>An argument a tool does not take is almost always a tool call meant for a different tool, or a
 * name the agent invented. Ignoring it runs the call anyway, with a default in place of whatever
 * was intended, and the agent is told nothing. Naming it costs one turn and tells the agent exactly
 * what to write instead.</p>
 *
 * <h2>Why a number written as text is still a number</h2>
 *
 * <p>Tool calls reach the harness as text. Depending on how a model wrote its call, {@code 7} may
 * arrive as a number or as {@code "7"}, and both plainly mean seven. Reading either is not a guess:
 * it is one unambiguous reading of a value whose type was lost in transit. Anything that has no
 * such reading -- {@code "later"} where a ledger index belongs -- is refused with what was given, so
 * the agent can see what it wrote.</p>
 */
public final class ToolArgs {

    private final ToolSchema          schema;
    private final Map<String, Object> given;

    /**
     * Reads a call against the tool it names.
     *
     * @param schema the tool that was called
     * @param given  what the call carried
     * @throws IllegalArgumentException if an argument is not one this tool takes, or a required one
     *                                  is missing
     */
    public ToolArgs(ToolSchema schema, Map<String, Object> given) {
        if (schema == null) {
            throw new IllegalArgumentException("there is no such tool to read arguments for");
        }
        this.schema = schema;
        this.given  = given == null ? Map.of() : new LinkedHashMap<>(given);
        refuseUnknown();
        refuseMissing();
    }

    /** The tool these arguments were written for. */
    public ToolSchema schema() {
        return schema;
    }

    /** Whether the call named an argument at all. */
    public boolean has(String name) {
        return given.get(name) != null;
    }

    /**
     * A required piece of text.
     *
     * @param name which argument
     * @return what was written
     */
    public String text(String name) {
        return written(name, value(name));
    }

    /**
     * A piece of text, or what the tool chose when the call left it out.
     *
     * @param name     which argument
     * @param fallback what the tool does without it
     * @return what was written, or the fallback
     */
    public String text(String name, String fallback) {
        return has(name) ? text(name) : fallback;
    }

    /**
     * A whole number, or what the tool chose when the call left it out.
     *
     * @param name     which argument
     * @param fallback what the tool does without it
     * @return the number, or the fallback
     */
    public int integer(String name, int fallback) {
        return has(name) ? whole(name, value(name)) : fallback;
    }

    /**
     * A number, or what the tool chose when the call left it out.
     *
     * @param name     which argument
     * @param fallback what the tool does without it
     * @return the number, or the fallback
     */
    public double decimal(String name, double fallback) {
        if (!has(name)) {
            return fallback;
        }
        Object value = value(name);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(written(name, value).trim());
        } catch (NumberFormatException notANumber) {
            throw refusal(name + " has to be a number, not " + describe(value), notANumber);
        }
    }

    /**
     * A flag, or what the tool chose when the call left it out.
     *
     * @param name     which argument
     * @param fallback what the tool does without it
     * @return the flag, or the fallback
     */
    public boolean flag(String name, boolean fallback) {
        if (!has(name)) {
            return fallback;
        }
        Object value = value(name);
        if (value instanceof Boolean flag) {
            return flag;
        }
        String said = written(name, value).trim();
        if ("true".equalsIgnoreCase(said)) {
            return true;
        }
        if ("false".equalsIgnoreCase(said)) {
            return false;
        }
        throw refusal(name + " has to be true or false, not " + describe(value), null);
    }

    /**
     * A required list.
     *
     * @param name which argument
     * @return what was written, element by element
     */
    public List<Object> values(String name) {
        Object value = value(name);
        if (!(value instanceof List)) {
            throw refusal(name + " has to be a list, not " + describe(value), null);
        }
        return List.copyOf((List<?>) value);
    }

    /**
     * A list, or what the tool chose when the call left it out.
     *
     * @param name     which argument
     * @param fallback what the tool does without it
     * @return the list, or the fallback
     */
    public List<Object> values(String name, List<Object> fallback) {
        return has(name) ? values(name) : fallback;
    }

    /**
     * A list read as words, so that a tag written as a number is still a tag.
     *
     * @param name which argument
     * @return the words
     */
    public List<String> texts(String name) {
        List<String> words = new ArrayList<>();
        for (Object element : values(name, List.of())) {
            words.add(written(name, element));
        }
        return List.copyOf(words);
    }

    /**
     * A list read as ledger indices.
     *
     * @param name which argument
     * @return the indices
     */
    public List<Integer> integers(String name) {
        List<Integer> numbers = new ArrayList<>();
        for (Object element : values(name, List.of())) {
            numbers.add(whole(name, element));
        }
        return List.copyOf(numbers);
    }

    /**
     * One argument as it arrived, whatever kind of value it is.
     *
     * @param name which argument
     * @return the value
     */
    public Object value(String name) {
        Object value = given.get(name);
        if (value == null) {
            throw refusal(schema.name() + " needs " + name + " (" + purposeOf(name) + ")", null);
        }
        return value;
    }

    /** The call as it would be written down again, for an audit trail. */
    public Map<String, Object> asValue() {
        return Map.copyOf(given);
    }

    private void refuseUnknown() {
        for (String name : given.keySet()) {
            if (schema.parameter(name) == null) {
                throw refusal(schema.parameters().isEmpty()
                              ? schema.name() + " takes no arguments, and was given " + name
                              : schema.name() + " takes no argument called " + name
                                + "; it takes " + String.join(", ", schema.names()), null);
            }
        }
    }

    private void refuseMissing() {
        List<String> missing = new ArrayList<>();
        for (ToolParam parameter : schema.parameters()) {
            if (parameter.required() && given.get(parameter.name()) == null) {
                missing.add(parameter.name() + " (" + parameter.purpose() + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw refusal(schema.name() + " needs " + String.join(", ", missing), null);
        }
    }

    private String purposeOf(String name) {
        ToolParam parameter = schema.parameter(name);
        return parameter == null ? "an argument this tool does not take" : parameter.purpose();
    }

    private int whole(String name, Object value) {
        if (value instanceof Number number) {
            double exact = number.doubleValue();
            if (exact != Math.rint(exact)) {
                throw refusal(name + " has to be a whole number, not " + describe(value), null);
            }
            return (int) exact;
        }
        try {
            return Integer.parseInt(written(name, value).trim());
        } catch (NumberFormatException notANumber) {
            throw refusal(name + " has to be a whole number, not " + describe(value), notANumber);
        }
    }

    private String written(String name, Object value) {
        if (value instanceof String said) {
            return said;
        }
        if (value == null) {
            throw refusal(name + " has to say something", null);
        }
        return Json.canonical(value);
    }

    private static String describe(Object value) {
        return value == null ? "nothing" : Json.canonical(value);
    }

    private static IllegalArgumentException refusal(String reason, Throwable cause) {
        return new IllegalArgumentException(reason, cause);
    }
}
