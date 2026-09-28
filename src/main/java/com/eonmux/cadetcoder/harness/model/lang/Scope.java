package com.eonmux.cadetcoder.harness.model.lang;

import java.util.HashMap;
import java.util.Map;

/**
 * The names in view while a model runs, and the one enclosing them.
 *
 * <p>A block gets its own scope so a {@code let} inside a loop body does not leak out of it, and an
 * assignment walks outwards so a loop can still accumulate into a local declared before it.</p>
 */
final class Scope {

    private final Scope              parent;
    private final Map<String, Object> values = new HashMap<>();

    Scope(Scope parent) {
        this.parent = parent;
    }

    void declare(String name, Object value) {
        values.put(name, value);
    }

    boolean holds(String name) {
        return values.containsKey(name) || (parent != null && parent.holds(name));
    }

    Object read(String name) {
        if (values.containsKey(name)) {
            return values.get(name);
        }
        return parent == null ? null : parent.read(name);
    }

    /** Writes to the nearest scope that already holds the name; reports when none does. */
    boolean assign(String name, Object value) {
        if (values.containsKey(name)) {
            values.put(name, value);
            return true;
        }
        return parent != null && parent.assign(name, value);
    }
}
