package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The operations that read a list or a string, and the two that extend one.
 *
 * <p>None of them changes what it was handed. A model's {@code step} is supposed to be a function of
 * its argument, and a library that edited in place would make that impossible to hold to by
 * accident rather than by intent.</p>
 */
final class SequenceBuiltins {

    private SequenceBuiltins() {
    }

    static List<Builtin> all() {
        return List.of(
                new Builtin("len", 1, 1, SequenceBuiltins::len),
                new Builtin("contains", 2, 2, SequenceBuiltins::contains),
                new Builtin("find", 2, 2, SequenceBuiltins::find),
                new Builtin("slice", 2, 3, SequenceBuiltins::slice),
                new Builtin("sort", 1, 1, SequenceBuiltins::sort),
                new Builtin("push", 2, 2, SequenceBuiltins::push));
    }

    private static Object len(List<Object> arguments, int line, int column) {
        Object given = arguments.get(0);
        if (given instanceof String text) {
            return (double) text.length();
        }
        if (given instanceof List<?> list) {
            return (double) list.size();
        }
        if (given instanceof Map<?, ?> map) {
            return (double) map.size();
        }
        throw new ModelRuntimeException("len needs text, a list or an object, was given "
                                        + Values.describe(given), line, column);
    }

    private static Object contains(List<Object> arguments, int line, int column) {
        return find(arguments, line, column) instanceof Double at && at >= 0;
    }

    /** Where something sits, or {@code -1} -- the one place a miss is an answer rather than a fault. */
    private static Object find(List<Object> arguments, int line, int column) {
        Object haystack = arguments.get(0);
        Object needle   = arguments.get(1);
        if (haystack instanceof String text) {
            return (double) text.indexOf(Values.text(needle, "find", line, column));
        }
        List<Object> list = Values.list(haystack, "find", line, column);
        for (int i = 0; i < list.size(); i++) {
            if (Json.equal(list.get(i), needle)) {
                return (double) i;
            }
        }
        return -1.0;
    }

    private static Object slice(List<Object> arguments, int line, int column) {
        Object given = arguments.get(0);
        int    size  = given instanceof String text ? text.length()
                                                    : Values.list(given, "slice", line, column).size();
        int    from  = clamp(Values.index(arguments.get(1), "slice", line, column), size);
        int    to    = arguments.size() == 3
                       ? clamp(Values.index(arguments.get(2), "slice", line, column), size) : size;
        if (to < from) {
            to = from;
        }
        if (given instanceof String text) {
            return text.substring(from, to);
        }
        return new ArrayList<>(Values.list(given, "slice", line, column).subList(from, to));
    }

    /** A bound outside the value is the whole of it; a negative one counts from the end. */
    private static int clamp(int at, int size) {
        int resolved = at < 0 ? size + at : at;
        return Math.max(0, Math.min(size, resolved));
    }

    private static Object sort(List<Object> arguments, int line, int column) {
        List<Object> sorted = new ArrayList<>(Values.list(arguments.get(0), "sort", line, column));
        sorted.sort(Values.order());
        return sorted;
    }

    private static Object push(List<Object> arguments, int line, int column) {
        List<Object> extended = new ArrayList<>(Values.list(arguments.get(0), "push", line, column));
        extended.add(arguments.get(1));
        return extended;
    }
}
