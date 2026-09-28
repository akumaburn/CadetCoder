package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.Json;

import java.util.List;

/** The three that belong to no particular kind of value. */
final class CoreBuiltins {

    private CoreBuiltins() {
    }

    static List<Builtin> all() {
        return List.of(
                new Builtin("str", 1, 1, (a, l, c) -> Json.readable(a.get(0))),
                new Builtin("copy", 1, 1, (a, l, c) -> Json.deepCopy(a.get(0))),
                new Builtin("error", 1, 1, CoreBuiltins::raise));
    }

    /**
     * How a model says it cannot account for what it was given.
     *
     * <p>This is the alternative to the silent fallback: a grounding function that cannot read an
     * observation is supposed to stop, so certification records a model error rather than letting a
     * wrong state through as if it had been derived.</p>
     */
    private static Object raise(List<Object> arguments, int line, int column) {
        throw new ModelRuntimeException(Json.readable(arguments.get(0)), line, column);
    }
}
