package com.eonmux.cadetcoder.harness.loop;

/**
 * One thing that was said in a run, and who said it.
 *
 * @param kind who spoke
 * @param text what was said
 */
public record Turn(TurnKind kind, String text) {

    public Turn {
        if (kind == null) {
            throw new IllegalArgumentException("a turn is said by someone");
        }
        text = text == null ? "" : text;
    }

    /** Something the agent said. */
    public static Turn agent(String text) {
        return new Turn(TurnKind.AGENT, text);
    }

    /** Something the driver said to the agent. */
    public static Turn harness(String text) {
        return new Turn(TurnKind.HARNESS, text);
    }

    /** Something a tool answered. */
    public static Turn answer(String text) {
        return new Turn(TurnKind.ANSWER, text);
    }

    @Override
    public String toString() {
        return kind + ": " + text;
    }
}
