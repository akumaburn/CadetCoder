package com.eonmux.cadetcoder.harness.model.lang;

import java.util.List;

/**
 * How much program a model is.
 *
 * <p>A count of syntax nodes is a cheap stand-in for the description length of a theory, and the
 * harness tracks it across versions for one reason: a model that keeps growing while the set of
 * rules reality has tested stays the same is being patched rather than corrected. Nothing here
 * decides anything -- it only makes epicycles measurable instead of a matter of taste.</p>
 */
final class Nodes {

    private Nodes() {
    }

    static int count(Iterable<FunctionDef> functions) {
        int total = 0;
        for (FunctionDef function : functions) {
            total += function(function);
        }
        return total;
    }

    private static int function(FunctionDef function) {
        return 1 + function.parameters().size() + statements(function.body());
    }

    private static int statements(List<Stmt> body) {
        int total = 0;
        for (Stmt statement : body) {
            total += statement(statement);
        }
        return total;
    }

    private static int statement(Stmt statement) {
        if (statement instanceof Stmt.Let let) {
            return 1 + expression(let.value());
        }
        if (statement instanceof Stmt.Assign assign) {
            return 1 + expression(assign.target()) + expression(assign.value());
        }
        if (statement instanceof Stmt.Return returned) {
            return 1 + (returned.value() == null ? 0 : expression(returned.value()));
        }
        if (statement instanceof Stmt.ExprStmt expression) {
            return 1 + expression(expression.value());
        }
        return branchOrLoop(statement);
    }

    private static int branchOrLoop(Stmt statement) {
        if (statement instanceof Stmt.If branch) {
            return 1 + expression(branch.condition()) + statements(branch.whenTrue())
                   + statements(branch.whenFalse());
        }
        if (statement instanceof Stmt.While loop) {
            return 1 + expression(loop.condition()) + statements(loop.body());
        }
        if (statement instanceof Stmt.ForIn loop) {
            return 1 + expression(loop.iterable()) + statements(loop.body());
        }
        return 1;
    }

    private static int expression(Expr expression) {
        if (expression instanceof Expr.Literal || expression instanceof Expr.Name) {
            return 1;
        }
        if (expression instanceof Expr.ListLit list) {
            return 1 + expressions(list.elements());
        }
        if (expression instanceof Expr.ObjectLit object) {
            return 1 + object.keys().size() + expressions(object.values());
        }
        if (expression instanceof Expr.Lambda lambda) {
            return 1 + function(lambda.function());
        }
        return operation(expression);
    }

    private static int operation(Expr expression) {
        if (expression instanceof Expr.Unary unary) {
            return 1 + expression(unary.operand());
        }
        if (expression instanceof Expr.Binary binary) {
            return 1 + expression(binary.left()) + expression(binary.right());
        }
        if (expression instanceof Expr.Logical logical) {
            return 1 + expression(logical.left()) + expression(logical.right());
        }
        if (expression instanceof Expr.Ternary ternary) {
            return 1 + expression(ternary.condition()) + expression(ternary.whenTrue())
                   + expression(ternary.whenFalse());
        }
        return access(expression);
    }

    private static int access(Expr expression) {
        if (expression instanceof Expr.Field field) {
            return 1 + expression(field.target());
        }
        if (expression instanceof Expr.Index index) {
            return 1 + expression(index.target()) + expression(index.index());
        }
        if (expression instanceof Expr.Call call) {
            return 1 + expression(call.callee()) + expressions(call.arguments());
        }
        return 1;
    }

    private static int expressions(List<Expr> expressions) {
        int total = 0;
        for (Expr expression : expressions) {
            total += expression(expression);
        }
        return total;
    }
}
