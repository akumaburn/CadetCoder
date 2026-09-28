package com.eonmux.cadetcoder.harness.model.lang;

/**
 * One rule a model states, which reality may or may not have put to the test.
 *
 * <p>An arm is a single outcome of a single branch: the true side of an {@code if}, the case where a
 * loop body never ran, a function nothing called. Certification reports which arms the ledger
 * exercised, and the commit gate refuses to walk a plan past a step that turns on one it never
 * did -- because a replay says nothing at all about a rule it never reached.</p>
 *
 * @param id      how it is named everywhere, for example {@code if@12:5/T}
 * @param kind    which construct it belongs to: {@code fn}, {@code if}, {@code while}, {@code for},
 *                {@code ifexp}, {@code and} or {@code or}
 * @param line    the 1-based line it is written on
 * @param column  the 1-based column it starts at
 * @param snippet that line of source, trimmed, so a report can be read without the model beside it
 */
public record Arm(String id, String kind, int line, int column, String snippet) {

    /** How it reads in a coverage report. */
    public String render() {
        return id + "  line " + line + ": " + snippet;
    }
}
