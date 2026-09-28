package com.eonmux.cadetcoder.harness.spec;

import com.eonmux.cadetcoder.harness.Json;

import java.util.function.Predicate;

/**
 * One named thing the next observation has to satisfy.
 *
 * <p>The name and the description are kept apart on purpose. A model may call a constraint by the
 * belief it stands for -- "the build passes" -- and that is the name a halt reports, but a reader
 * still needs to know that the belief was checked as {@code rc == 0}.</p>
 *
 * <p>A constraint that throws has not been satisfied. Treating a crashing check as a pass is the
 * silent fallback in its most damaging form: the harness would report that a prediction held when
 * in fact nothing was checked at all.</p>
 *
 * @param name        what the belief is called
 * @param path        what it is about, or {@code null} when it is about the observation as a whole
 * @param description how the check reads
 * @param holds       whether an observation satisfies it
 */
public record Constraint(String name, String path, String description, Predicate<Object> holds) {

    /** How much of an observed value a violation quotes back. */
    private static final int FOUND_LENGTH = 120;

    /** The violation this constraint finds, or {@code null} when the observation satisfies it. */
    Violation inspect(Object observation) {
        try {
            return holds.test(observation)
                   ? null
                   : new Violation(name, "expected " + description + found(observation));
        } catch (RuntimeException failure) {
            return new Violation(name, "expected " + description
                                       + ", but the check failed: " + failure);
        }
    }

    private String found(Object observation) {
        if (path == null) {
            return "";
        }
        if (!Json.has(observation, path)) {
            return ", found nothing at " + path;
        }
        return ", found " + brief(Json.at(observation, path));
    }

    private static String brief(Object value) {
        String written = Json.canonical(value);
        return written.length() <= FOUND_LENGTH ? written
                                                : written.substring(0, FOUND_LENGTH) + "...";
    }
}
