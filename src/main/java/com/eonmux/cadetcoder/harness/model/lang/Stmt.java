package com.eonmux.cadetcoder.harness.model.lang;

import java.util.List;

/** A statement in a model, and where it was written. */
public sealed interface Stmt {

    /** The 1-based line the statement starts on. */
    int line();

    /** The 1-based column the statement starts at. */
    int column();

    /** {@code let name = value;} -- introduces a new local. */
    record Let(String name, Expr value, int line, int column) implements Stmt {
    }

    /** {@code target = value;} where the target is a local, a field or an element. */
    record Assign(Expr target, Expr value, int line, int column) implements Stmt {
    }

    /** {@code return value;}, with {@code null} for a bare {@code return}. */
    record Return(Expr value, int line, int column) implements Stmt {
    }

    /**
     * {@code if (condition) { ... } else { ... }}.
     *
     * @param armId what coverage calls this branch; the arms are {@code /T} and {@code /F}, and the
     *              false arm exists whether or not an {@code else} was written -- a condition that
     *              was never false is an untested rule either way
     */
    record If(Expr condition, List<Stmt> whenTrue, List<Stmt> whenFalse, String armId,
              int line, int column) implements Stmt {
    }

    /** {@code while (condition) { ... }}. */
    record While(Expr condition, List<Stmt> body, String armId, int line, int column) implements Stmt {
    }

    /** {@code for (name in iterable) { ... }}. */
    record ForIn(String variable, Expr iterable, List<Stmt> body, String armId,
                 int line, int column) implements Stmt {
    }

    /** {@code break;} */
    record Break(int line, int column) implements Stmt {
    }

    /** {@code continue;} */
    record Continue(int line, int column) implements Stmt {
    }

    /** An expression evaluated for its result being discarded. */
    record ExprStmt(Expr value, int line, int column) implements Stmt {
    }
}
