package com.eonmux.cadetcoder.harness.spec;

import com.eonmux.cadetcoder.harness.Json;

import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The constraints a model can state, in the one place their wording is decided.
 *
 * <p>Every builder on {@link Expect} arrives here, so a constraint's name, its description and the
 * check it performs are written next to each other and cannot drift apart. The names are the ones a
 * halt reports, so they read as the belief rather than as an assertion: {@code rc == 0}, not
 * {@code assertEquals(0, rc)}.</p>
 *
 * <h2>Why absence is a violation</h2>
 *
 * <p>Every constraint about a path requires that path to resolve. A prediction that
 * {@code hud.score == 7} against an observation with no {@code hud} at all has been contradicted:
 * the model believes in a field the environment does not have, which is exactly the grounding drift
 * the harness exists to catch. Only {@link #absent} reads a missing path as agreement.</p>
 */
final class Constraints {

    private Constraints() {
    }

    static Constraint eq(String path, Object value, String name) {
        return of(name, path, path + " == " + Json.canonical(value),
                  observation -> resolves(observation, path)
                                 && Json.equal(Json.at(observation, path), value));
    }

    static Constraint ne(String path, Object value, String name) {
        return of(name, path, path + " != " + Json.canonical(value),
                  observation -> resolves(observation, path)
                                 && !Json.equal(Json.at(observation, path), value));
    }

    static Constraint gt(String path, Object bound, String name) {
        return comparison(path, bound, ">", name, sign -> sign > 0);
    }

    static Constraint ge(String path, Object bound, String name) {
        return comparison(path, bound, ">=", name, sign -> sign >= 0);
    }

    static Constraint lt(String path, Object bound, String name) {
        return comparison(path, bound, "<", name, sign -> sign < 0);
    }

    static Constraint le(String path, Object bound, String name) {
        return comparison(path, bound, "<=", name, sign -> sign <= 0);
    }

    static Constraint contains(String path, Object needle, String name) {
        return of(name, path, path + " contains " + Json.canonical(needle),
                  observation -> resolves(observation, path)
                                 && holdsWithin(Json.at(observation, path), needle));
    }

    static Constraint present(String path, String name) {
        return of(name, path, path + " present", observation -> resolves(observation, path));
    }

    static Constraint absent(String path, String name) {
        return of(name, path, path + " absent", observation -> !resolves(observation, path));
    }

    static Constraint matches(String path, String regex, String name) {
        Pattern pattern = Pattern.compile(regex);
        return of(name, path, path + " matches " + Json.canonical(regex),
                  observation -> resolves(observation, path)
                                 && Json.at(observation, path) instanceof String text
                                 && pattern.matcher(text).find());
    }

    static Constraint where(String name, Predicate<Object> holds) {
        return new Constraint(name, null, name, holds);
    }

    /**
     * A bound comparison, which is a violation when the value is not a number at all.
     *
     * <p>Coercing text to a number would let a model state {@code tree.files > 12} about a field
     * that turned into an error message and still be told it was right.</p>
     */
    private static Constraint comparison(String path, Object bound, String symbol, String name,
                                         IntPredicate accept) {
        return of(name, path, path + " " + symbol + " " + Json.canonical(bound),
                  observation -> resolves(observation, path)
                                 && Json.at(observation, path) instanceof Number value
                                 && bound instanceof Number limit
                                 && accept.test(Double.compare(value.doubleValue(),
                                                               limit.doubleValue())));
    }

    private static Constraint of(String name, String path, String description,
                                 Predicate<Object> holds) {
        return new Constraint(name == null ? description : name, path, description, holds);
    }

    private static boolean resolves(Object observation, String path) {
        return Json.has(observation, path);
    }

    /** Containment as each kind of value understands it; anything else has not been satisfied. */
    private static boolean holdsWithin(Object haystack, Object needle) {
        if (haystack instanceof String text) {
            return needle instanceof String part && text.contains(part);
        }
        if (haystack instanceof List<?> elements) {
            return elements.stream().anyMatch(element -> Json.equal(element, needle));
        }
        if (haystack instanceof Map<?, ?> fields) {
            return needle instanceof String key && fields.containsKey(key);
        }
        return false;
    }
}
