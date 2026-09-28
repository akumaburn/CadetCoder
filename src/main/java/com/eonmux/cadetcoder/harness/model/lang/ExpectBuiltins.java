package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.spec.Expect;

import java.util.List;

/**
 * How a model starts saying what it believes about the next observation.
 *
 * <p>The beliefs themselves are added by calling methods on what this returns, so the library needs
 * only the one entry point and the vocabulary can grow without the language changing.</p>
 */
final class ExpectBuiltins {

    private ExpectBuiltins() {
    }

    static List<Builtin> all() {
        return List.of(new Builtin("expect", 0, 0, (a, l, c) -> Expect.anything()));
    }
}
