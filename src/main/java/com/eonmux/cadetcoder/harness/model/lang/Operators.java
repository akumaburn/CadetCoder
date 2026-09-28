package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.Json;

import java.util.List;
import java.util.Map;

/**
 * What the operators mean, and what reading or writing a value is allowed to do.
 *
 * <h2>Why a missing field is an error</h2>
 *
 * <p>This is the rule the language exists to enforce. A model that reads a field an observation does
 * not have is a model whose theory has come apart, and answering {@code null} lets it keep
 * predicting from a state it never really derived -- the silent fallback that turns one wrong
 * grounding into a long run of confident nonsense. There is a way to say "this may be absent",
 * {@code has} and the three-argument {@code get}, and it has to be written down.</p>
 */
final class Operators {

    private Operators() {
    }

    static Object unary(String operator, Object value, int line, int column) {
        return switch (operator) {
            case "-" -> -Values.number(value, "'-'", line, column);
            case "!" -> !Values.truthy(value);
            default  -> throw new ModelRuntimeException("unknown operator " + operator, line, column);
        };
    }

    static Object binary(String operator, Object left, Object right, int line, int column) {
        if (operator.equals("==")) {
            return Json.equal(left, right);
        }
        if (operator.equals("!=")) {
            return !Json.equal(left, right);
        }
        if (operator.equals("+") && (left instanceof String || right instanceof String)) {
            return Json.readable(left) + Json.readable(right);
        }
        if (left instanceof String x && right instanceof String y) {
            return compare(operator, x.compareTo(y), line, column);
        }
        return arithmetic(operator, Values.number(left, "'" + operator + "'", line, column),
                          Values.number(right, "'" + operator + "'", line, column), line, column);
    }

    private static Object arithmetic(String operator, double left, double right,
                                     int line, int column) {
        return switch (operator) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> divide(left, right, line, column);
            case "%" -> remainder(left, right, line, column);
            default  -> compare(operator, Double.compare(left, right), line, column);
        };
    }

    private static Object divide(double left, double right, int line, int column) {
        if (right == 0) {
            throw new ModelRuntimeException("divide by zero", line, column);
        }
        return left / right;
    }

    private static Object remainder(double left, double right, int line, int column) {
        if (right == 0) {
            throw new ModelRuntimeException("divide by zero", line, column);
        }
        return left % right;
    }

    private static Object compare(String operator, int ordering, int line, int column) {
        return switch (operator) {
            case "<"  -> ordering < 0;
            case "<=" -> ordering <= 0;
            case ">"  -> ordering > 0;
            case ">=" -> ordering >= 0;
            default   -> throw new ModelRuntimeException("unknown operator " + operator, line, column);
        };
    }

    static Object field(Object target, String name, int line, int column) {
        if (!(target instanceof Map<?, ?> object)) {
            throw new ModelRuntimeException("no field '" + name + "': " + Values.describe(target)
                                            + " has no fields", line, column);
        }
        if (!object.containsKey(name)) {
            throw new ModelRuntimeException("no field '" + name + "' on " + Values.brief(target),
                                            line, column);
        }
        return object.get(name);
    }

    static Object element(Object target, Object index, int line, int column) {
        if (target instanceof List<?> list) {
            int at = Values.index(index, "an index", line, column);
            if (at < 0 || at >= list.size()) {
                throw new ModelRuntimeException("index " + at + " is outside a list of "
                                                + list.size(), line, column);
            }
            return list.get(at);
        }
        if (target instanceof String text) {
            int at = Values.index(index, "an index", line, column);
            if (at < 0 || at >= text.length()) {
                throw new ModelRuntimeException("index " + at + " is outside text of "
                                                + text.length(), line, column);
            }
            return String.valueOf(text.charAt(at));
        }
        return field(target, Values.text(index, "a key", line, column), line, column);
    }

    /**
     * Writes into a value the model is entitled to change.
     *
     * <p>Observations arrive frozen, so an attempt to edit one arrives here as an
     * {@link UnsupportedOperationException} and is reported as what it is. Hiding it behind a copy
     * would let a model quietly rewrite the world's own record of what happened.</p>
     */
    @SuppressWarnings ("unchecked")
    static void put(Object target, Object key, Object value, int line, int column) {
        try {
            if (target instanceof List<?> list) {
                int at = Values.index(key, "an index", line, column);
                if (at < 0 || at >= list.size()) {
                    throw new ModelRuntimeException("index " + at + " is outside a list of "
                                                    + list.size(), line, column);
                }
                ((List<Object>) list).set(at, value);
                return;
            }
            if (target instanceof Map<?, ?> object) {
                ((Map<String, Object>) object).put(Values.text(key, "a key", line, column), value);
                return;
            }
        } catch (UnsupportedOperationException e) {
            throw new ModelRuntimeException("this value cannot be changed; copy() it first",
                                            line, column);
        }
        throw new ModelRuntimeException(Values.describe(target) + " has nothing to assign to",
                                        line, column);
    }
}
