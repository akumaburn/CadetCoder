package com.eonmux.cadetcoder.harness.model.lang;

/**
 * A function as a value: what it does, and the names that were in view where it was written.
 *
 * <p>Named functions are closures over nothing, which keeps one call path for both them and the
 * anonymous functions a model writes when it needs to hand a predicate to {@code Expect.where}.</p>
 */
record Closure(FunctionDef definition, Scope captured) {

    @Override
    public String toString() {
        return definition.signature();
    }
}
