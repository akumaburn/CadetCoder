package com.eonmux.cadetcoder.harness.spec;

/**
 * One belief a prediction stated that the world did not agree with.
 *
 * @param name   what the belief is called, so a halt points at a belief rather than at a wall of diff
 * @param detail what was expected and what was there instead
 */
public record Violation(String name, String detail) {

    /** How it reads in a report. */
    public String render() {
        return name + ": " + detail;
    }

    @Override
    public String toString() {
        return render();
    }
}
