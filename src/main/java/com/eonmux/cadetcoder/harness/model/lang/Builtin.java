package com.eonmux.cadetcoder.harness.model.lang;

import java.util.List;

/**
 * One function the language provides, and how many arguments it accepts.
 *
 * <p>The arity is declared rather than checked inside each body so that calling one wrongly reads
 * the same as calling a model's own function wrongly, and so the whole library can be listed in a
 * prompt without reading it.</p>
 *
 * @param name     how a model calls it
 * @param minArity the fewest arguments it accepts
 * @param maxArity the most it accepts, or {@link #ANY} for no upper bound
 * @param body     what it does
 */
record Builtin(String name, int minArity, int maxArity, Body body) {

    /** No upper bound on the number of arguments. */
    static final int ANY = Integer.MAX_VALUE;

    /** What a builtin does, given already-evaluated arguments and where it was called. */
    @FunctionalInterface
    interface Body {
        Object apply(List<Object> arguments, int line, int column);
    }

    Object invoke(List<Object> arguments, int line, int column) {
        if (arguments.size() < minArity || arguments.size() > maxArity) {
            throw new ModelRuntimeException(name + " expects " + expectation()
                                            + ", was given " + arguments.size(), line, column);
        }
        return body.apply(arguments, line, column);
    }

    private String expectation() {
        if (maxArity == ANY) {
            return "at least " + minArity + " arguments";
        }
        if (minArity == maxArity) {
            return minArity + " arguments";
        }
        return "between " + minArity + " and " + maxArity + " arguments";
    }
}
