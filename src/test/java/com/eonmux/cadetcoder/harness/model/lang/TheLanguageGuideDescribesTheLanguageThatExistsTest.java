package com.eonmux.cadetcoder.harness.model.lang;

import com.eonmux.cadetcoder.harness.model.WorldModel;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guide is the whole of what a model author is told, so nothing in it may be false.
 *
 * <p>A model is written from the prompt by something that has never seen this language. A sentence
 * in the guide that the parser does not honour is therefore not a documentation defect: it is a run
 * that spends its budget writing models that are refused, for a rule the guide invented. These tests
 * put each claim to the language itself, and check that every name the language has reached the
 * guide -- so a builtin, a belief or a keyword that is added cannot arrive undescribed.</p>
 */
public class TheLanguageGuideDescribesTheLanguageThatExistsTest {

    private static final String GUIDE = LanguageGuide.render();

    private static Object run(String source, String function, Object... arguments) {
        return new Interpreter(Program.parse(source), ExecutionLimits.standard())
                .call(function, List.of(arguments));
    }

    @Test
    public void theExampleModelInTheGuideIsOneTheHarnessAccepts() {
        WorldModel model = WorldModel.load(LanguageGuide.EXAMPLE);

        Object state = model.parse(Map.of("pos", 0.0));

        assertThat(model.hidden()).containsExactly("carried");
        assertThat(model.isGoal(state)).isFalse();
        assertThat(model.actions(state, List.of())).hasSize(2);
        assertThat(model.predict(model.step(state, Map.of("move", 1.0))))
                .isEqualTo(Map.of("pos", 1.0));
    }

    @Test
    public void theExampleModelDoesNotChangeTheStateItIsGiven() {
        WorldModel model = WorldModel.load(LanguageGuide.EXAMPLE);
        Object     state = model.parse(Map.of("pos", 0.0));

        model.step(state, Map.of("move", 1.0));

        assertThat(state).isEqualTo(Map.of("pos", 0.0, "carried", 0.0));
    }

    @Test
    public void everyBuiltinTheLanguageHasIsListedInTheGuide() {
        for (String name : Builtins.names()) {
            assertThat(GUIDE).as("the library listing is missing %s", name).contains(name + "/");
        }
    }

    @Test
    public void theLibraryListingSaysHowManyArgumentsEachBuiltinTakes() {
        assertThat(Builtins.render()).contains("copy/1", "slice/2..3", "max/1+");
    }

    @Test
    public void everyBeliefAModelCanStateIsNamedInTheGuide() {
        for (String belief : ExpectMethods.names()) {
            assertThat(GUIDE).as("the beliefs are missing %s", belief).contains(belief);
        }
    }

    @Test
    public void everyWordTheLanguageReservesIsShownInTheGuide() {
        for (String keyword : Lexer.keywords()) {
            assertThat(GUIDE).as("the guide never mentions %s", keyword).contains(keyword);
        }
    }

    /**
     * The guide claimed a {@code for} loop would walk an object's keys. It will not: the loop asks
     * for a list, so a model written from that sentence fails on its first observation.
     */
    @Test
    public void aForLoopWalksAListAndAnObjectHasToBeMadeIntoOneFirst() {
        assertThatThrownBy(() -> run("fn f(o) { for (k in o) { return k; } return null; }", "f",
                                     Map.of("a", 1.0)))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("a for loop");

        assertThat(run("fn f(o) { for (k in keys(o)) { return k; } return null; }", "f",
                       Map.of("a", 1.0))).isEqualTo("a");
        assertThat(GUIDE).contains("for (k in keys(o))");
    }

    /**
     * The guide claimed a missing field read as {@code null}. It does not: reading one stops the
     * model, and a model author who believes otherwise writes no {@code has} or {@code get} at all.
     */
    @Test
    public void readingAFieldThatIsNotThereStopsTheModelRatherThanAnsweringNothing() {
        assertThatThrownBy(() -> run("fn f(o) { return o.missing; }", "f", Map.of("a", 1.0)))
                .isInstanceOf(ModelRuntimeException.class)
                .hasMessageContaining("no field 'missing'");

        assertThat(run("fn f(o) { return get(o, \"missing\", 7); }", "f", Map.of("a", 1.0)))
                .isEqualTo(7.0);
        assertThat(run("fn f(o) { return has(o, \"missing\"); }", "f", Map.of("a", 1.0)))
                .isEqualTo(false);
        assertThat(GUIDE).contains("reading what is not there is an error");
    }

    /**
     * The guide claimed {@code +} joined two lists. It does not: it joins only when a side is text,
     * and a model that concatenates lists with it is refused where it can least afford to be.
     */
    @Test
    public void plusJoinsOnlyWhenOneSideIsText() {
        assertThat(run("fn f() { return \"a\" + 1; }", "f")).isEqualTo("a1");

        assertThatThrownBy(() -> run("fn f() { return [1] + [2]; }", "f"))
                .isInstanceOf(ModelRuntimeException.class);
        assertThat(GUIDE).contains("+ joins when either side is text");
    }

    /**
     * The guide claimed an anonymous function was for {@code sort}. {@code sort} takes one argument
     * and orders by value; the one place a model supplies a function of its own is a belief.
     */
    @Test
    public void anAnonymousFunctionIsForABeliefRatherThanForSorting() {
        assertThat(GUIDE).contains("for expect().where");
        assertThat(Builtins.render()).contains("sort/1");
    }
}
