package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.spec.Expect;

import java.util.List;
import java.util.function.Predicate;

/**
 * The beliefs a model may state, reached as methods on an expectation.
 *
 * <h2>Why these are bound here rather than added to the library</h2>
 *
 * <p>Every one of them acts on an expectation and returns another, so a model builds a prediction by
 * chaining -- {@code expect().eq("rc", 0).contains("output", "ok")} -- which reads as the belief it
 * is. As library functions they would each need the expectation passed back in, and a model author
 * would have to remember that the result is a new expectation rather than a change to the old one.</p>
 *
 * <h2>Why {@code where} needs the interpreter</h2>
 *
 * <p>A predicate a model writes is model code, and only the interpreter can run it. It is also run
 * long after the call that built it returned, so it is bounded by a budget of its own rather than by
 * whatever was left of the one the model spent building its prediction.</p>
 */
final class ExpectMethods {

    /** A belief about the value at a path. */
    @FunctionalInterface
    private interface Belief {
        Expect add(Expect target, String path, Object value, String name);
    }

    /** A belief about a path itself rather than about what is at it. */
    @FunctionalInterface
    private interface Presence {
        Expect add(Expect target, String path, String name);
    }

    private ExpectMethods() {
    }

    /**
     * The method of this name on this expectation, ready to be called.
     *
     * @param target      the expectation the belief is added to
     * @param method      what the model wrote after the dot
     * @param interpreter what runs a predicate the model supplies
     * @param line        where the model wrote it
     * @param column      where the model wrote it
     * @return the bound method
     * @throws ModelRuntimeException if the vocabulary has no such belief
     */
    static Builtin bind(Expect target, String method, Interpreter interpreter,
                        int line, int column) {
        switch (method) {
            case "eq":
                return valued(method, target, Expect::eq);
            case "ne":
                return valued(method, target, Expect::ne);
            case "gt":
                return valued(method, target, Expect::gt);
            case "ge":
                return valued(method, target, Expect::ge);
            case "lt":
                return valued(method, target, Expect::lt);
            case "le":
                return valued(method, target, Expect::le);
            case "contains":
                return valued(method, target, Expect::contains);
            case "matches":
                return matches(target);
            case "present":
                return about(method, target, Expect::present);
            case "absent":
                return about(method, target, Expect::absent);
            case "where":
                return where(target, interpreter);
            default:
                throw new ModelRuntimeException(
                        "an expectation has no belief called " + method + "; it has "
                        + String.join(", ", names()), line, column);
        }
    }

    /** Every belief a model may state, so a prompt can list them rather than leave them to guess. */
    static List<String> names() {
        return List.of("eq", "ne", "gt", "ge", "lt", "le", "contains", "matches",
                       "present", "absent", "where");
    }

    private static Builtin valued(String method, Expect target, Belief belief) {
        return new Builtin(method, 2, 3, (a, l, c) ->
                belief.add(target, path(a, l, c), a.get(1), name(a, 2, l, c)));
    }

    private static Builtin about(String method, Expect target, Presence belief) {
        return new Builtin(method, 1, 2, (a, l, c) ->
                belief.add(target, path(a, l, c), name(a, 1, l, c)));
    }

    private static Builtin matches(Expect target) {
        return new Builtin("matches", 2, 3, (a, l, c) ->
                target.matches(path(a, l, c),
                               Values.text(a.get(1), "a pattern", l, c),
                               name(a, 2, l, c)));
    }

    private static Builtin where(Expect target, Interpreter interpreter) {
        return new Builtin("where", 2, 2, (a, l, c) ->
                target.where(Values.text(a.get(0), "a name", l, c),
                             predicate(a.get(1), interpreter, l, c)));
    }

    private static Predicate<Object> predicate(Object value, Interpreter interpreter,
                                               int line, int column) {
        if (!(value instanceof Closure closure)) {
            throw new ModelRuntimeException("where needs a function, was given "
                                            + Values.describe(value), line, column);
        }
        return observation -> interpreter.runPredicate(closure, observation, line, column);
    }

    private static String path(List<Object> arguments, int line, int column) {
        return Values.text(arguments.get(0), "a path", line, column);
    }

    private static String name(List<Object> arguments, int position, int line, int column) {
        return arguments.size() > position
               ? Values.text(arguments.get(position), "a name", line, column)
               : null;
    }
}
