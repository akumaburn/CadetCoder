package com.eonmux.cadetcoder.harness.model.lang;

import java.util.ArrayList;
import java.util.List;

/** The arithmetic a model needs that operators do not cover. */
final class NumberBuiltins {

    private NumberBuiltins() {
    }

    static List<Builtin> all() {
        return List.of(
                new Builtin("num", 1, 1, NumberBuiltins::num),
                new Builtin("int", 1, 1, (a, l, c) -> truncate(Values.number(a.get(0), "int", l, c))),
                new Builtin("floor", 1, 1, (a, l, c) -> Math.floor(Values.number(a.get(0), "floor", l, c))),
                new Builtin("abs", 1, 1, (a, l, c) -> Math.abs(Values.number(a.get(0), "abs", l, c))),
                new Builtin("min", 1, Builtin.ANY, (a, l, c) -> extreme("min", a, l, c, true)),
                new Builtin("max", 1, Builtin.ANY, (a, l, c) -> extreme("max", a, l, c, false)),
                new Builtin("range", 1, 2, NumberBuiltins::range));
    }

    private static Object num(List<Object> arguments, int line, int column) {
        Object given = arguments.get(0);
        if (given instanceof Number n) {
            return n.doubleValue();
        }
        String text = Values.text(given, "num", line, column).trim();
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new ModelRuntimeException("num cannot read '" + text + "' as a number", line, column);
        }
    }

    /** Truncates towards zero, so {@code int(3.7)} is 3 and {@code int(-3.7)} is -3. */
    private static Object truncate(double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    /**
     * Takes the extreme of the arguments, or of the single list it was handed.
     *
     * <p>A model reaching for the smallest of a collection it computed should not have to write a
     * loop to get it -- a loop is two more arms nothing will ever exercise.</p>
     */
    private static Object extreme(String name, List<Object> arguments, int line, int column,
                                  boolean smallest) {
        List<Object> operands = arguments.size() == 1 && arguments.get(0) instanceof List<?> only
                                ? List.copyOf(only) : arguments;
        if (operands.isEmpty()) {
            throw new ModelRuntimeException(name + " needs something to compare", line, column);
        }
        double best = Values.number(operands.get(0), name, line, column);
        for (Object operand : operands) {
            double candidate = Values.number(operand, name, line, column);
            best = smallest ? Math.min(best, candidate) : Math.max(best, candidate);
        }
        return best;
    }

    private static Object range(List<Object> arguments, int line, int column) {
        int from = arguments.size() == 1 ? 0 : Values.index(arguments.get(0), "range", line, column);
        int to   = Values.index(arguments.get(arguments.size() - 1), "range", line, column);
        List<Object> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            out.add((double) i);
        }
        return out;
    }
}
