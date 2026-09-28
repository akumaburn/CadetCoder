package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.spec.Expect;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What the language means by a value, in one place.
 *
 * <h2>Why these rules and not Java's</h2>
 *
 * <p>Every value a model touches came out of an observation or is going back into a prediction, so
 * the language has to agree with the ledger about what a value is. Equality is
 * {@link Json#equal}, so a count a model reached by arithmetic and the same count read from an
 * observation are one value; truthiness is {@link Json#truthy}, so an empty grid is false the way an
 * empty list is; and ordering is total across types, so {@code sort} of a mixed list is the same
 * list on every machine and a replay is reproducible.</p>
 */
final class Values {

    /** How much of a value an error message shows before it stops being readable. */
    private static final int BRIEF_LENGTH = 120;

    /** Numbers first, then text, with the containers last, so a mixed sort stays deterministic. */
    private static final Comparator<Object> ORDER = Values::compare;

    private Values() {
    }

    static Comparator<Object> order() {
        return ORDER;
    }

    static boolean truthy(Object value) {
        return Json.truthy(value);
    }

    /** How a value reads inside an error: enough of it to recognise, never a whole observation. */
    static String brief(Object value) {
        String written = Json.canonical(value);
        return written.length() <= BRIEF_LENGTH ? written
                                                : written.substring(0, BRIEF_LENGTH) + "...";
    }

    /** What a value is, for an error a model author has to act on. */
    static String describe(Object value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof String) {
            return "text";
        }
        if (value instanceof Number) {
            return "a number";
        }
        if (value instanceof Boolean) {
            return "a boolean";
        }
        if (value instanceof List) {
            return "a list";
        }
        if (value instanceof Map) {
            return "an object";
        }
        if (value instanceof Expect) {
            return "an expectation";
        }
        if (value instanceof Closure || value instanceof Builtin) {
            return "a function";
        }
        return value.getClass().getSimpleName();
    }

    static double number(Object value, String what, int line, int column) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new ModelRuntimeException(what + " needs a number, was given " + describe(value),
                                        line, column);
    }

    static String text(Object value, String what, int line, int column) {
        if (value instanceof String s) {
            return s;
        }
        throw new ModelRuntimeException(what + " needs text, was given " + describe(value),
                                        line, column);
    }

    @SuppressWarnings ("unchecked")
    static List<Object> list(Object value, String what, int line, int column) {
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new ModelRuntimeException(what + " needs a list, was given " + describe(value),
                                        line, column);
    }

    @SuppressWarnings ("unchecked")
    static Map<String, Object> object(Object value, String what, int line, int column) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new ModelRuntimeException(what + " needs an object, was given " + describe(value),
                                        line, column);
    }

    /** An integer index or count, refusing a fraction rather than rounding one away. */
    static int index(Object value, String what, int line, int column) {
        double d = number(value, what, line, column);
        if (d != Math.floor(d) || Double.isInfinite(d)) {
            throw new ModelRuntimeException(what + " needs a whole number, was given " + Json.readable(value),
                                            line, column);
        }
        return (int) d;
    }

    private static int rank(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return 1;
        }
        if (value instanceof String) {
            return 2;
        }
        if (value instanceof Boolean) {
            return 3;
        }
        if (value instanceof List) {
            return 4;
        }
        return 5;
    }

    private static int compare(Object a, Object b) {
        int rank = Integer.compare(rank(a), rank(b));
        if (rank != 0) {
            return rank;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return x.compareTo(y);
        }
        return Json.canonical(a).compareTo(Json.canonical(b));
    }
}
