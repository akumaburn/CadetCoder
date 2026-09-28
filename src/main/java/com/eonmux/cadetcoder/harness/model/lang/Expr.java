package com.eonmux.cadetcoder.harness.model.lang;

import java.util.List;

/**
 * An expression in a model, and where it was written.
 *
 * <p>Every node carries its position because every failure a model can have is reported back to the
 * person who wrote it: a missing field, a division by zero, and a branch reality never exercised all
 * have to name a line.</p>
 */
public sealed interface Expr {

    /** The 1-based line the expression starts on. */
    int line();

    /** The 1-based column the expression starts at. */
    int column();

    /** A number, string, boolean or null written literally. */
    record Literal(Object value, int line, int column) implements Expr {
    }

    /** A parameter, local, function or builtin, by name. */
    record Name(String name, int line, int column) implements Expr {
    }

    /** A list written out: {@code [a, b]}. */
    record ListLit(List<Expr> elements, int line, int column) implements Expr {
    }

    /** An object written out: {@code {"a": 1}}. */
    record ObjectLit(List<String> keys, List<Expr> values, int line, int column) implements Expr {
    }

    /** {@code !x} or {@code -x}. */
    record Unary(String operator, Expr operand, int line, int column) implements Expr {
    }

    /** Arithmetic, comparison and equality; both sides are always evaluated. */
    record Binary(String operator, Expr left, Expr right, int line, int column) implements Expr {
    }

    /**
     * {@code &&} or {@code ||}, which short-circuit and are therefore branches.
     *
     * @param armId what coverage calls this branch; the two arms are {@code /T} and {@code /F}
     */
    record Logical(String operator, Expr left, Expr right, String armId,
                   int line, int column) implements Expr {
    }

    /** {@code c ? a : b}, a branch like any other. */
    record Ternary(Expr condition, Expr whenTrue, Expr whenFalse, String armId,
                   int line, int column) implements Expr {
    }

    /** {@code target.name}. */
    record Field(Expr target, String name, int line, int column) implements Expr {
    }

    /** {@code target[index]}. */
    record Index(Expr target, Expr index, int line, int column) implements Expr {
    }

    /** {@code callee(arguments)}. */
    record Call(Expr callee, List<Expr> arguments, int line, int column) implements Expr {
    }

    /** {@code fn(a) { ... }} written where a value is expected. */
    record Lambda(FunctionDef function, int line, int column) implements Expr {
    }
}
