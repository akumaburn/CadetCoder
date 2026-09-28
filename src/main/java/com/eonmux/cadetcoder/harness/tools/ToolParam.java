package com.eonmux.cadetcoder.harness.tools;

/**
 * One argument a tool takes.
 *
 * @param name     what the argument is called in a call
 * @param type     what kind of value it holds
 * @param required whether a call without it is refused
 * @param purpose  what it is for, as the agent is told
 */
public record ToolParam(String name, ToolType type, boolean required, String purpose) {

    /**
     * An argument a tool can do without.
     *
     * @param name    what it is called
     * @param type    what kind of value it holds
     * @param purpose what it is for
     * @return the argument
     */
    public static ToolParam optional(String name, ToolType type, String purpose) {
        return new ToolParam(name, type, false, purpose);
    }

    /**
     * An argument a tool cannot run without.
     *
     * @param name    what it is called
     * @param type    what kind of value it holds
     * @param purpose what it is for
     * @return the argument
     */
    public static ToolParam required(String name, ToolType type, String purpose) {
        return new ToolParam(name, type, true, purpose);
    }

    /** How the argument reads where the agent is told about it. */
    public String render() {
        return name + " (" + type.label() + (required ? ", required" : "") + ") -- " + purpose;
    }

    @Override
    public String toString() {
        return render();
    }
}
