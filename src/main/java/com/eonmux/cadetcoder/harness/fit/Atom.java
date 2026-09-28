package com.eonmux.cadetcoder.harness.fit;

import com.eonmux.cadetcoder.harness.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * One comparison about one feature: the smallest thing a fitted predicate is built from.
 *
 * <h2>Why a feature an example does not have satisfies nothing</h2>
 *
 * <p>An atom is a claim about a value, so where there is no value there is no claim to be true.
 * Reading a missing feature as satisfying {@code !=} would be the more literal reading, and it
 * would let a conjunction made entirely of absences look like a perfect separator -- the fit would
 * report a rule about states that were never described rather than about the world.</p>
 *
 * <h2>Why an order comparison is about numbers only</h2>
 *
 * <p>{@code >=} between a string and a number has an answer in some languages and not in others,
 * and neither answer is about the environment. It is false here, in the same way and for the same
 * reason as in the constraints a model states, so that a feature whose values turn out to be mixed
 * degrades to categorical rather than to nonsense.</p>
 *
 * @param feature which feature the atom is about
 * @param op      how it is compared
 * @param value   what it is compared against
 */
public record Atom(String feature, AtomOp op, Object value) {

    /**
     * Whether the atom holds of one example's features.
     *
     * @param features the example's features; a missing feature satisfies nothing
     * @return whether the comparison holds
     */
    public boolean holds(Map<String, Object> features) {
        if (features == null || !features.containsKey(feature)) {
            return false;
        }
        Object seen = features.get(feature);
        return switch (op) {
            case EQ -> Json.equal(seen, value);
            case NE -> !Json.equal(seen, value);
            case GE -> ordered(seen, sign -> sign >= 0);
            case LT -> ordered(seen, sign -> sign < 0);
        };
    }

    /** The atom as it is written in a predicate. */
    public String render() {
        return feature + " " + op.symbol() + " " + Json.canonical(value);
    }

    /** The atom as a value the harness can keep. */
    public Map<String, Object> toValue() {
        Map<String, Object> written = new LinkedHashMap<>();
        written.put("feature", feature);
        written.put("op", op.symbol());
        written.put("value", value);
        return written;
    }

    private boolean ordered(Object seen, IntPredicate accept) {
        return seen instanceof Number left && value instanceof Number right
               && accept.test(Double.compare(left.doubleValue(), right.doubleValue()));
    }

    @Override
    public String toString() {
        return render();
    }
}
