package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.spec.Expect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a model, counts the rules it exercises, and stops it if it will not stop itself.
 *
 * <h2>Why coverage is collected here rather than measured afterwards</h2>
 *
 * <p>Which rules reality has put to the test is the answer certification cannot produce any other
 * way: a replay that reproduces every transition in the ledger says nothing at all about a branch it
 * never reached, and that untested branch is where a plan built on the model tends to go wrong.
 * Marking an arm as it is taken makes the enumeration and the marks the same identifiers, so the
 * two cannot drift apart.</p>
 *
 * <h2>Why the limits are counted rather than timed out</h2>
 *
 * <p>The reference harness bounds a model by forking a process with an alarm. Counting steps in the
 * evaluation loop instead means a model refused for running too long is refused for the same reason
 * on every machine, which is what a reproducible replay needs. The clock is a backstop only.</p>
 *
 * <h2>One instance, one measurement</h2>
 *
 * <p>Coverage accumulates over the life of an interpreter and never resets, because the question it
 * answers is about a whole replay. A fresh interpreter is how a new measurement is started.</p>
 */
public final class Interpreter {

    /** How often the wall clock is consulted, in steps; often enough to catch a slow model. */
    private static final int CLOCK_INTERVAL = 4_096;

    private final Program         program;
    private final ExecutionLimits limits;
    private final Set<String>     hit = new LinkedHashSet<>();

    private long steps;
    private long deadline;
    private int  depth;

    public Interpreter(Program program, ExecutionLimits limits) {
        this.program = program;
        this.limits  = limits;
    }

    /** The model being run. */
    public Program program() {
        return program;
    }

    /** Which of the model's rules have been exercised so far. */
    public Coverage coverage() {
        return new Coverage(program.arms(), hit);
    }

    /**
     * Calls one of the model's functions.
     *
     * <p>Each call gets the whole step and time budget: the limits bound one deliberation, not the
     * replay of a long ledger.</p>
     */
    public Object call(String function, List<Object> arguments) {
        FunctionDef definition = program.function(function);
        if (definition == null) {
            throw new ModelRuntimeException("this model does not define " + function);
        }
        steps    = 0;
        depth    = 0;
        deadline = System.currentTimeMillis() + limits.maxMilliseconds();
        return invoke(new Closure(definition, null), arguments,
                      definition.line(), definition.column());
    }

    /**
     * Runs a predicate a model left behind, under a budget of its own.
     *
     * <p>A model writes a predicate while predicting and the observation it judges only arrives
     * after the action has been taken, so the steps the model spent building its prediction are long
     * spent by then and the same predicate may be checked more than once. Charging it against
     * whatever was left would make a belief fail for having been stated late.</p>
     *
     * @param predicate   the function the model supplied
     * @param observation what it is being asked about
     * @param line        where the model supplied it
     * @param column      where the model supplied it
     * @return whether the predicate says the observation satisfies the belief
     */
    boolean runPredicate(Closure predicate, Object observation, int line, int column) {
        if (depth == 0) {
            steps    = 0;
            deadline = System.currentTimeMillis() + limits.maxMilliseconds();
        }
        return Values.truthy(invoke(predicate, Collections.singletonList(observation),
                                    line, column));
    }

    Object invoke(Closure closure, List<Object> arguments, int line, int column) {
        FunctionDef definition = closure.definition();
        if (arguments.size() != definition.parameters().size()) {
            throw new ModelRuntimeException(
                    (definition.name() == null ? "this function" : definition.name())
                    + " expects " + definition.parameters().size() + " arguments, was given "
                    + arguments.size(), line, column);
        }
        if (depth >= ExecutionLimits.MAX_CALL_DEPTH) {
            throw new ModelRuntimeException("calls are nested too deep past "
                                            + ExecutionLimits.MAX_CALL_DEPTH + " levels",
                                            line, column);
        }
        depth++;
        try {
            hit.add(definition.armId());
            Scope scope = new Scope(closure.captured());
            for (int i = 0; i < arguments.size(); i++) {
                scope.declare(definition.parameters().get(i), arguments.get(i));
            }
            executeBlock(definition.body(), scope);
            return null;
        } catch (Return returned) {
            return returned.value;
        } finally {
            depth--;
        }
    }

    private void executeBlock(List<Stmt> statements, Scope enclosing) {
        Scope scope = new Scope(enclosing);
        for (Stmt statement : statements) {
            execute(statement, scope);
        }
    }

    private void execute(Stmt statement, Scope scope) {
        charge(statement.line(), statement.column());
        if (statement instanceof Stmt.Let let) {
            scope.declare(let.name(), evaluate(let.value(), scope));
        } else if (statement instanceof Stmt.Assign assignment) {
            assign(assignment, scope);
        } else if (statement instanceof Stmt.Return returned) {
            throw new Return(returned.value() == null ? null : evaluate(returned.value(), scope));
        } else if (statement instanceof Stmt.ExprStmt expression) {
            evaluate(expression.value(), scope);
        } else {
            branchOrLoop(statement, scope);
        }
    }

    private void branchOrLoop(Stmt statement, Scope scope) {
        if (statement instanceof Stmt.If branch) {
            ifStatement(branch, scope);
        } else if (statement instanceof Stmt.While loop) {
            whileStatement(loop, scope);
        } else if (statement instanceof Stmt.ForIn loop) {
            forStatement(loop, scope);
        } else if (statement instanceof Stmt.Break) {
            throw Break.INSTANCE;
        } else if (statement instanceof Stmt.Continue) {
            throw Continue.INSTANCE;
        } else {
            throw new IllegalStateException("unhandled statement " + statement.getClass());
        }
    }

    private void assign(Stmt.Assign assignment, Scope scope) {
        Object value = evaluate(assignment.value(), scope);
        if (assignment.target() instanceof Expr.Name name) {
            if (!scope.assign(name.name(), value)) {
                throw new ModelRuntimeException("nothing called " + name.name()
                                                + " is in view to assign to",
                                                name.line(), name.column());
            }
        } else if (assignment.target() instanceof Expr.Field field) {
            Operators.put(evaluate(field.target(), scope), field.name(), value,
                          field.line(), field.column());
        } else if (assignment.target() instanceof Expr.Index index) {
            Operators.put(evaluate(index.target(), scope), evaluate(index.index(), scope), value,
                          index.line(), index.column());
        } else {
            throw new ModelRuntimeException("this cannot be assigned to",
                                            assignment.line(), assignment.column());
        }
    }

    private void ifStatement(Stmt.If branch, Scope scope) {
        boolean taken = Values.truthy(evaluate(branch.condition(), scope));
        hit.add(branch.armId() + (taken ? "/T" : "/F"));
        executeBlock(taken ? branch.whenTrue() : branch.whenFalse(), scope);
    }

    private void whileStatement(Stmt.While loop, Scope scope) {
        while (true) {
            boolean again = Values.truthy(evaluate(loop.condition(), scope));
            hit.add(loop.armId() + (again ? "/T" : "/F"));
            if (!again) {
                return;
            }
            try {
                executeBlock(loop.body(), scope);
            } catch (Break stop) {
                return;
            } catch (Continue next) {
                continue;
            }
        }
    }

    private void forStatement(Stmt.ForIn loop, Scope scope) {
        List<Object> sequence = Values.list(evaluate(loop.iterable(), scope), "a for loop",
                                            loop.line(), loop.column());
        for (Object element : sequence) {
            hit.add(loop.armId() + "/T");
            charge(loop.line(), loop.column());
            Scope round = new Scope(scope);
            round.declare(loop.variable(), element);
            try {
                executeBlock(loop.body(), round);
            } catch (Break stop) {
                return;
            } catch (Continue next) {
                continue;
            }
        }
        hit.add(loop.armId() + "/F");
    }

    private List<Object> evaluateAll(List<Expr> expressions, Scope scope) {
        List<Object> values = new ArrayList<>(expressions.size());
        for (Expr expression : expressions) {
            values.add(evaluate(expression, scope));
        }
        return values;
    }

    private Object evaluate(Expr expression, Scope scope) {
        charge(expression.line(), expression.column());
        if (expression instanceof Expr.Literal literal) {
            return literal.value();
        }
        if (expression instanceof Expr.Name name) {
            return read(name, scope);
        }
        if (expression instanceof Expr.ListLit list) {
            return new ArrayList<>(evaluateAll(list.elements(), scope));
        }
        if (expression instanceof Expr.ObjectLit literal) {
            return object(literal, scope);
        }
        if (expression instanceof Expr.Lambda lambda) {
            return new Closure(lambda.function(), scope);
        }
        return evaluateOperation(expression, scope);
    }

    private Object evaluateOperation(Expr expression, Scope scope) {
        if (expression instanceof Expr.Unary unary) {
            return Operators.unary(unary.operator(), evaluate(unary.operand(), scope),
                                   unary.line(), unary.column());
        }
        if (expression instanceof Expr.Binary binary) {
            return Operators.binary(binary.operator(), evaluate(binary.left(), scope),
                                    evaluate(binary.right(), scope),
                                    binary.line(), binary.column());
        }
        if (expression instanceof Expr.Logical logical) {
            return logical(logical, scope);
        }
        if (expression instanceof Expr.Ternary ternary) {
            return ternary(ternary, scope);
        }
        return evaluateAccess(expression, scope);
    }

    private Object evaluateAccess(Expr expression, Scope scope) {
        if (expression instanceof Expr.Field field) {
            return field(field, scope);
        }
        if (expression instanceof Expr.Index index) {
            return Operators.element(evaluate(index.target(), scope),
                                     evaluate(index.index(), scope),
                                     index.line(), index.column());
        }
        if (expression instanceof Expr.Call invocation) {
            return call(invocation, scope);
        }
        throw new IllegalStateException("unhandled expression " + expression.getClass());
    }

    /**
     * A field of a value, or a belief a model is stating about the next observation.
     *
     * <p>An expectation is the one value in the language whose members are methods rather than
     * data, because a prediction is built by chaining beliefs onto it. Reading its fields through
     * the ordinary rule would report that it has none, which is true and useless.</p>
     */
    private Object field(Expr.Field field, Scope scope) {
        Object target = evaluate(field.target(), scope);
        if (target instanceof Expect expectation) {
            return ExpectMethods.bind(expectation, field.name(), this,
                                      field.line(), field.column());
        }
        return Operators.field(target, field.name(), field.line(), field.column());
    }

    private Object object(Expr.ObjectLit literal, Scope scope) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < literal.keys().size(); i++) {
            value.put(literal.keys().get(i), evaluate(literal.values().get(i), scope));
        }
        return value;
    }

    private Object read(Expr.Name name, Scope scope) {
        if (scope.holds(name.name())) {
            return scope.read(name.name());
        }
        FunctionDef definition = program.function(name.name());
        if (definition != null) {
            return new Closure(definition, null);
        }
        Builtin builtin = Builtins.lookup(name.name());
        if (builtin != null) {
            return builtin;
        }
        throw new ModelRuntimeException("nothing called " + name.name() + " is in view",
                                        name.line(), name.column());
    }

    private Object logical(Expr.Logical logical, Scope scope) {
        Object  left  = evaluate(logical.left(), scope);
        boolean truth = Values.truthy(left);
        hit.add(logical.armId() + (truth ? "/T" : "/F"));
        boolean shortCircuits = logical.operator().equals("and") != truth;
        return shortCircuits ? left : evaluate(logical.right(), scope);
    }

    private Object ternary(Expr.Ternary ternary, Scope scope) {
        boolean taken = Values.truthy(evaluate(ternary.condition(), scope));
        hit.add(ternary.armId() + (taken ? "/T" : "/F"));
        return evaluate(taken ? ternary.whenTrue() : ternary.whenFalse(), scope);
    }

    private Object call(Expr.Call call, Scope scope) {
        if (call.callee() instanceof Expr.Name name && !scope.holds(name.name())) {
            FunctionDef definition = program.function(name.name());
            if (definition != null) {
                return invoke(new Closure(definition, null), evaluateAll(call.arguments(), scope),
                              call.line(), call.column());
            }
        }
        return apply(evaluate(call.callee(), scope), evaluateAll(call.arguments(), scope), call);
    }

    private Object apply(Object callee, List<Object> arguments, Expr.Call call) {
        if (callee instanceof Closure closure) {
            return invoke(closure, arguments, call.line(), call.column());
        }
        if (callee instanceof Builtin builtin) {
            return builtin.invoke(arguments, call.line(), call.column());
        }
        throw new ModelRuntimeException(Values.describe(callee) + " cannot be called",
                                        call.line(), call.column());
    }

    private void charge(int line, int column) {
        steps++;
        if (steps > limits.maxSteps()) {
            throw new ModelRuntimeException("a model may not run for more than "
                                            + limits.maxSteps() + " steps", line, column);
        }
        if (steps % CLOCK_INTERVAL == 0 && System.currentTimeMillis() > deadline) {
            throw new ModelRuntimeException("a model may not run for more than "
                                            + limits.maxMilliseconds() + " ms", line, column);
        }
    }

    /** Carries a returned value out of however many blocks it is nested in. */
    private static final class Return extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Object value;

        private Return(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /** Ends the nearest loop. One instance: it carries nothing and is thrown constantly. */
    private static final class Break extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private static final Break INSTANCE = new Break();

        private Break() {
            super(null, null, false, false);
        }
    }

    /** Skips to the next round of the nearest loop. */
    private static final class Continue extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private static final Continue INSTANCE = new Continue();

        private Continue() {
            super(null, null, false, false);
        }
    }
}
