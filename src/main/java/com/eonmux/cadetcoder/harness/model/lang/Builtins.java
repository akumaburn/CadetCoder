package com.eonmux.cadetcoder.harness.model.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a model can call that it did not write itself.
 *
 * <h2>Why this list and no way to add to it</h2>
 *
 * <p>The library is the whole of the language's contact with anything outside a model's own source.
 * There is no file, no clock, no random number and no host object in it, which is what makes running
 * a model an LLM wrote an ordinary call rather than a sandboxing problem -- and what lets the parser
 * refuse an unknown name outright instead of waiting to find out at run time.</p>
 */
final class Builtins {

    private static final Map<String, Builtin> BY_NAME = index(
            CoreBuiltins.all(), NumberBuiltins.all(), TextBuiltins.all(),
            SequenceBuiltins.all(), ObjectBuiltins.all(), ExpectBuiltins.all());

    private Builtins() {
    }

    @SafeVarargs
    private static Map<String, Builtin> index(List<Builtin>... groups) {
        Map<String, Builtin> byName = new LinkedHashMap<>();
        for (List<Builtin> group : groups) {
            for (Builtin builtin : group) {
                if (byName.put(builtin.name(), builtin) != null) {
                    throw new IllegalStateException("two builtins are called " + builtin.name());
                }
            }
        }
        return Map.copyOf(byName);
    }

    /** Every name a model may call without declaring it. */
    static Set<String> names() {
        return BY_NAME.keySet();
    }

    /** The builtin with this name, or {@code null} when the language has none. */
    static Builtin lookup(String name) {
        return BY_NAME.get(name);
    }

    /**
     * The library as a prompt reads it, so a model author is told rather than left to guess.
     *
     * <h2>Why the arity is listed with the name</h2>
     *
     * <p>A name on its own leaves the one thing a model author cannot guess -- whether
     * {@code slice} takes two arguments or three -- to be discovered by writing a model that is
     * refused for it, which costs a whole deliberation. The arity is already declared on every
     * builtin for exactly this, so listing it is what makes the promise good.</p>
     */
    static String render() {
        List<String> listed = new ArrayList<>(BY_NAME.keySet());
        listed.sort(String::compareTo);
        List<String> signatures = new ArrayList<>();
        for (String name : listed) {
            signatures.add(name + "/" + arity(BY_NAME.get(name)));
        }
        return String.join(", ", signatures);
    }

    /** How many arguments one builtin takes, in the shorthand the guide explains. */
    private static String arity(Builtin builtin) {
        if (builtin.maxArity() == Builtin.ANY) {
            return builtin.minArity() + "+";
        }
        if (builtin.minArity() == builtin.maxArity()) {
            return String.valueOf(builtin.minArity());
        }
        return builtin.minArity() + ".." + builtin.maxArity();
    }
}
