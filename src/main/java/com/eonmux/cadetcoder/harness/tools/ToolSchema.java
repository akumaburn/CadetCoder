package com.eonmux.cadetcoder.harness.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * One tool, as the agent is told about it and as its arguments are checked.
 *
 * <h2>Why the description and the check are the same object</h2>
 *
 * <p>A tool described one way and validated another is how an agent ends up refused for a rule
 * nobody told it about. The list of arguments here is what {@link #render()} puts in the prompt and
 * what {@link ToolArgs} enforces on the way back, so the two cannot drift apart.</p>
 *
 * @param name       what the agent calls it
 * @param purpose    what it does, as the agent is told
 * @param parameters what it takes, in the order an author meets them
 */
public record ToolSchema(String name, String purpose, List<ToolParam> parameters) {

    public ToolSchema {
        parameters = List.copyOf(parameters);
    }

    /**
     * A tool that takes nothing.
     *
     * @param name    what the agent calls it
     * @param purpose what it does
     * @return the tool
     */
    public static ToolSchema of(String name, String purpose) {
        return new ToolSchema(name, purpose, List.of());
    }

    /**
     * A tool and the arguments it takes.
     *
     * @param name       what the agent calls it
     * @param purpose    what it does
     * @param parameters what it takes
     * @return the tool
     */
    public static ToolSchema of(String name, String purpose, ToolParam... parameters) {
        return new ToolSchema(name, purpose, List.of(parameters));
    }

    /**
     * The argument of a given name, or {@code null} when this tool takes no such argument.
     *
     * @param argument what the caller named
     * @return the argument it names
     */
    public ToolParam parameter(String argument) {
        for (ToolParam parameter : parameters) {
            if (parameter.name().equals(argument)) {
                return parameter;
            }
        }
        return null;
    }

    /** Every argument this tool takes, named. */
    public List<String> names() {
        List<String> named = new ArrayList<>(parameters.size());
        for (ToolParam parameter : parameters) {
            named.add(parameter.name());
        }
        return named;
    }

    /** The call as it is written, with the arguments that may be left out in brackets. */
    public String signature() {
        List<String> written = new ArrayList<>(parameters.size());
        for (ToolParam parameter : parameters) {
            written.add(parameter.required() ? parameter.name() : "[" + parameter.name() + "]");
        }
        return name + "(" + String.join(", ", written) + ")";
    }

    /** How the tool reads where the agent is told about it. */
    public String render() {
        StringBuilder out = new StringBuilder(signature());
        out.append(System.lineSeparator()).append("    ").append(purpose);
        for (ToolParam parameter : parameters) {
            out.append(System.lineSeparator()).append("      ").append(parameter.render());
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return signature();
    }
}
