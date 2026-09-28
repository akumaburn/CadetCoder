package com.eonmux.cadetcoder.harness.model.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reading and rebuilding objects.
 *
 * <h2>Why keys come back sorted</h2>
 *
 * <p>{@code keys} and {@code values} are how a model walks an observation it did not write, and a
 * model that walks one in whatever order a hash table produced is a model whose replay is not
 * reproducible. Sorting here is the same choice canonical JSON makes, for the same reason.</p>
 */
final class ObjectBuiltins {

    private ObjectBuiltins() {
    }

    static List<Builtin> all() {
        return List.of(
                new Builtin("keys", 1, 1, (a, l, c) -> new ArrayList<Object>(
                        sorted(a.get(0), "keys", l, c).keySet())),
                new Builtin("values", 1, 1, (a, l, c) -> new ArrayList<>(
                        sorted(a.get(0), "values", l, c).values())),
                new Builtin("has", 2, 2, ObjectBuiltins::has),
                new Builtin("get", 3, 3, ObjectBuiltins::get),
                new Builtin("set", 3, 3, ObjectBuiltins::set),
                new Builtin("del", 2, 2, ObjectBuiltins::del));
    }

    private static Map<String, Object> sorted(Object given, String what, int line, int column) {
        return new TreeMap<>(Values.object(given, what, line, column));
    }

    private static Object has(List<Object> arguments, int line, int column) {
        Object given = arguments.get(0);
        if (given instanceof List<?> list) {
            int at = Values.index(arguments.get(1), "has", line, column);
            return at >= 0 && at < list.size();
        }
        return Values.object(given, "has", line, column)
                .containsKey(Values.text(arguments.get(1), "has", line, column));
    }

    /** The written-down way to read something that may be absent. */
    private static Object get(List<Object> arguments, int line, int column) {
        Object given = arguments.get(0);
        if (given instanceof List<?> list) {
            int at = Values.index(arguments.get(1), "get", line, column);
            return at >= 0 && at < list.size() ? list.get(at) : arguments.get(2);
        }
        Map<String, Object> object = Values.object(given, "get", line, column);
        String              key    = Values.text(arguments.get(1), "get", line, column);
        return object.containsKey(key) ? object.get(key) : arguments.get(2);
    }

    private static Object set(List<Object> arguments, int line, int column) {
        Map<String, Object> changed = new LinkedHashMap<>(
                Values.object(arguments.get(0), "set", line, column));
        changed.put(Values.text(arguments.get(1), "set", line, column), arguments.get(2));
        return changed;
    }

    /** Removing a field that is not there is a mistake, exactly as reading one is. */
    private static Object del(List<Object> arguments, int line, int column) {
        Map<String, Object> changed = new LinkedHashMap<>(
                Values.object(arguments.get(0), "del", line, column));
        String              key     = Values.text(arguments.get(1), "del", line, column);
        if (!changed.containsKey(key)) {
            throw new ModelRuntimeException("no field '" + key + "' to remove", line, column);
        }
        changed.remove(key);
        return changed;
    }
}
