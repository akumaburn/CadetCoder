package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.Json;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A world model has to be a program the harness can run, count, and bound.
 *
 * <p>In the reference harness a model is Python source, which buys expressiveness at three prices
 * this project cannot pay: running it means executing arbitrary code, bounding it means forking a
 * process, and counting which of its rules reality has exercised means walking a foreign syntax
 * tree. This language exists so all three are ordinary: it has no I/O, no imports and no way to
 * reach the host, a step limit that is checked in the evaluation loop, and a branch set that is
 * enumerated when it is parsed.</p>
 *
 * <p>Its second job is to make the failure it is most likely to hide impossible. A model that reads
 * a field an observation does not have must say so; a language that answers {@code null} lets a
 * wrong theory keep predicting on a state it silently failed to read.</p>
 */
public class TheModelLanguageRunsWhatItIsGivenTest {

    private static Object run(String source, String function, Object... arguments) {
        return new Interpreter(Program.parse(source), ExecutionLimits.standard())
                .call(function, List.of(arguments));
    }

    private static Object evaluate(String expression) {
        return run("fn main() { return " + expression + "; }", "main");
    }

    @Test
    public void arithmeticFollowsTheUsualPrecedence() {
        assertThat(evaluate("1 + 2 * 3")).isEqualTo(7.0);
        assertThat(evaluate("(1 + 2) * 3")).isEqualTo(9.0);
        assertThat(evaluate("7 % 4")).isEqualTo(3.0);
        assertThat(evaluate("-3 + 1")).isEqualTo(-2.0);
        assertThat(evaluate("10 / 4")).isEqualTo(2.5);
    }

    @Test
    public void comparisonsAndLogicWork() {
        assertThat(evaluate("1 < 2 && 2 <= 2")).isEqualTo(true);
        assertThat(evaluate("1 > 2 || 3 != 4")).isEqualTo(true);
        assertThat(evaluate("!(1 == 1)")).isEqualTo(false);
        assertThat(evaluate("2 == 2 ? \"yes\" : \"no\"")).isEqualTo("yes");
    }

    /** Equality is the ledger's equality, so a model never disagrees with the record by spelling. */
    @Test
    public void equalityIsTheSameNotionTheLedgerUses() {
        assertThat(evaluate("{\"a\": 1, \"b\": 2} == {\"b\": 2, \"a\": 1}")).isEqualTo(true);
        assertThat(evaluate("[1, 2] == [1, 2]")).isEqualTo(true);
        assertThat(evaluate("1 == 1.0")).isEqualTo(true);
    }

    @Test
    public void stringsAndListsAndObjectsAreValues() {
        assertThat(evaluate("\"a\" + \"b\"")).isEqualTo("ab");
        assertThat(evaluate("len([1, 2, 3])")).isEqualTo(3.0);
        assertThat(evaluate("[1, 2, 3][1]")).isEqualTo(2.0);
        assertThat(evaluate("{\"pos\": 4}.pos")).isEqualTo(4.0);
        assertThat(evaluate("{\"pos\": 4}[\"pos\"]")).isEqualTo(4.0);
        assertThat(Json.canonical(evaluate("keys({\"b\": 1, \"a\": 2})"))).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    public void controlFlowWorks() {
        String source = """
                fn classify(n) {
                    if (n < 0) { return "negative"; }
                    else if (n == 0) { return "zero"; }
                    return "positive";
                }
                """;

        assertThat(run(source, "classify", -1.0)).isEqualTo("negative");
        assertThat(run(source, "classify", 0.0)).isEqualTo("zero");
        assertThat(run(source, "classify", 5.0)).isEqualTo("positive");
    }

    @Test
    public void loopsAccumulate() {
        String source = """
                fn total(items) {
                    let sum = 0;
                    for (item in items) {
                        if (item == 3) { continue; }
                        if (item == 9) { break; }
                        sum = sum + item;
                    }
                    let i = 0;
                    while (i < 2) { sum = sum + 100; i = i + 1; }
                    return sum;
                }
                """;

        assertThat(run(source, "total", List.of(1, 2, 3, 4, 9, 100))).isEqualTo(207.0);
    }

    @Test
    public void functionsCallEachOtherAndRecurse() {
        String source = """
                fn double(n) { return n * 2; }
                fn factorial(n) { if (n <= 1) { return 1; } return n * factorial(n - 1); }
                fn main(n) { return double(factorial(n)); }
                """;

        assertThat(run(source, "main", 4.0)).isEqualTo(48.0);
    }

    /** {@code Expect.where} needs a predicate the model wrote; that means anonymous functions. */
    @Test
    public void anonymousFunctionsAreValues() {
        String source = """
                fn apply(f, x) { return f(x); }
                fn main() { let bump = fn(n) { return n + 1; }; return apply(bump, 41); }
                """;

        assertThat(run(source, "main")).isEqualTo(42.0);
    }

    @Test
    public void emptinessIsFalseJustAsItIsEverywhereElse() {
        assertThat(evaluate("[] ? 1 : 0")).isEqualTo(0.0);
        assertThat(evaluate("\"\" ? 1 : 0")).isEqualTo(0.0);
        assertThat(evaluate("{} ? 1 : 0")).isEqualTo(0.0);
        assertThat(evaluate("0 ? 1 : 0")).isEqualTo(0.0);
        assertThat(evaluate("[0] ? 1 : 0")).isEqualTo(1.0);
    }

    /**
     * The one rule this language exists to enforce.
     *
     * <p>Reading a field that is not there is how a model quietly keeps predicting after it has
     * stopped matching reality. There is a way to say "it may be absent" -- {@code has} and the
     * three-argument {@code get} -- and it has to be written down.</p>
     */
    @Test
    public void readingSomethingThatIsNotThereIsAnError() {
        assertThatThrownBy(() -> evaluate("{\"a\": 1}.b"))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("no field 'b'");

        assertThatThrownBy(() -> evaluate("[1, 2][5]"))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("index 5");

        assertThat(evaluate("has({\"a\": 1}, \"b\")")).isEqualTo(false);
        assertThat(evaluate("get({\"a\": 1}, \"b\", 7)")).isEqualTo(7.0);
    }

    @Test
    public void anErrorNamesWhereItHappened() {
        String source = """
                fn main() {
                    let s = {"a": 1};
                    return s.missing;
                }
                """;

        assertThatThrownBy(() -> run(source, "main"))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("line 3");
    }

    @Test
    public void aModelCanRaiseItsOwnError() {
        assertThatThrownBy(() -> evaluate("error(\"cannot ground this observation\")"))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("cannot ground this observation");
    }

    @Test
    public void dividingByZeroIsAnErrorRatherThanInfinity() {
        assertThatThrownBy(() -> evaluate("1 / 0"))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("divide by zero");
    }

    /** A model is given the world's own value; changing it is not a prediction, it is cheating. */
    @Test
    public void anObservationCannotBeEditedInPlace() {
        Object frozen = Json.frozen(Map.of("pos", 1));

        assertThatThrownBy(() -> run("fn main(obs) { obs.pos = 2; return obs; }", "main", frozen))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    public void copyGivesSomethingTheModelMayChange() {
        Object frozen = Json.frozen(Map.of("pos", 1));
        Object stepped = run("fn main(obs) { let s = copy(obs); s.pos = s.pos + 1; return s; }",
                             "main", frozen);

        assertThat(Json.canonical(stepped)).isEqualTo("{\"pos\":2}");
        assertThat(Json.canonical(frozen)).isEqualTo("{\"pos\":1}");
    }

    @Test
    public void aRunawayModelIsStoppedRatherThanHangingTheHarness() {
        assertThatThrownBy(() -> new Interpreter(Program.parse("fn main() { while (1) { } return 1; }"),
                                                 new ExecutionLimits(500, 5_000))
                .call("main", List.of()))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("500 steps");
    }

    @Test
    public void aRunawayRecursionIsStoppedToo() {
        assertThatThrownBy(() -> run("fn main() { return main(); }", "main"))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("too deep");
    }

    @Test
    public void nothingInTheLanguageCanReachTheHost() {
        assertThatThrownBy(() -> Program.parse("fn main() { return System.exit(1); }"))
                .isInstanceOf(ModelSyntaxException.class)
                .hasMessageContaining("System");
    }

    @Test
    public void anUnknownFunctionIsRefusedBeforeAnythingRuns() {
        assertThatThrownBy(() -> Program.parse("fn main() { return helper(1); }"))
                .isInstanceOf(ModelSyntaxException.class)
                .hasMessageContaining("helper");
    }

    @Test
    public void syntaxErrorsNameTheLineAndWhatWasExpected() {
        assertThatThrownBy(() -> Program.parse("fn main() {\n  let x = ;\n}"))
                .isInstanceOf(ModelSyntaxException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    public void hiddenFieldsAreDeclaredAtTheTop() {
        Program program = Program.parse("hidden has_key, under;\nfn main() { return 1; }");

        assertThat(program.hidden()).containsExactlyInAnyOrder("has_key", "under");
    }

    @Test
    public void aProgramKnowsWhichFunctionsItDefines() {
        Program program = Program.parse("fn parse(obs) { return obs; }\nfn step(s, a) { return s; }");

        assertThat(program.functionNames()).containsExactlyInAnyOrder("parse", "step");
        assertThat(program.defines("predict")).isFalse();
    }

    @Test
    public void callingSomethingTheProgramDoesNotDefineIsRefused() {
        assertThatThrownBy(() -> run("fn main() { return 1; }", "predict"))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("predict");
    }

    @Test
    public void theWrongNumberOfArgumentsIsRefused() {
        assertThatThrownBy(() -> run("fn main(a, b) { return a; }", "main", 1.0))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("2 arguments");
    }

    @Test
    public void theStandardLibraryCoversWhatAModelActuallyNeeds() {
        assertThat(evaluate("str(3)")).isEqualTo("3");
        assertThat(evaluate("num(\"3.5\")")).isEqualTo(3.5);
        assertThat(evaluate("int(3.7)")).isEqualTo(3.0);
        assertThat(evaluate("abs(-2)")).isEqualTo(2.0);
        assertThat(evaluate("min(3, 1)")).isEqualTo(1.0);
        assertThat(evaluate("max(3, 1)")).isEqualTo(3.0);
        assertThat(Json.canonical(evaluate("range(3)"))).isEqualTo("[0,1,2]");
        assertThat(evaluate("contains(\"abc\", \"b\")")).isEqualTo(true);
        assertThat(evaluate("contains([1, 2], 2)")).isEqualTo(true);
        assertThat(evaluate("startswith(\"abc\", \"ab\")")).isEqualTo(true);
        assertThat(evaluate("endswith(\"abc\", \"bc\")")).isEqualTo(true);
        assertThat(Json.canonical(evaluate("split(\"a,b\", \",\")"))).isEqualTo("[\"a\",\"b\"]");
        assertThat(evaluate("join([\"a\", \"b\"], \"-\")")).isEqualTo("a-b");
        assertThat(evaluate("lower(\"AB\")")).isEqualTo("ab");
        assertThat(evaluate("upper(\"ab\")")).isEqualTo("AB");
        assertThat(evaluate("trim(\"  a  \")")).isEqualTo("a");
        assertThat(evaluate("replace(\"aba\", \"a\", \"c\")")).isEqualTo("cbc");
        assertThat(evaluate("find(\"abc\", \"c\")")).isEqualTo(2.0);
        assertThat(Json.canonical(evaluate("slice([1, 2, 3, 4], 1, 3)"))).isEqualTo("[2,3]");
        assertThat(Json.canonical(evaluate("sort([3, 1, 2])"))).isEqualTo("[1,2,3]");
        assertThat(Json.canonical(evaluate("push([1], 2)"))).isEqualTo("[1,2]");
        assertThat(Json.canonical(evaluate("set({\"a\": 1}, \"b\", 2)")))
                .isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(Json.canonical(evaluate("del({\"a\": 1, \"b\": 2}, \"a\")"))).isEqualTo("{\"b\":2}");
        assertThat(Json.canonical(evaluate("values({\"b\": 1, \"a\": 2})"))).isEqualTo("[2,1]");
    }

    /** The library never edits what it is handed; a model that needs a change gets a new value. */
    @Test
    public void theLibraryReturnsNewValuesRatherThanChangingOldOnes() {
        String source = """
                fn main() {
                    let original = [1];
                    let extended = push(original, 2);
                    return [len(original), len(extended)];
                }
                """;

        assertThat(Json.canonical(run(source, "main"))).isEqualTo("[1,2]");
    }
}
