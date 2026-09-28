package com.eonmux.cadetcoder.harness.model;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.model.lang.ExecutionLimits;
import com.eonmux.cadetcoder.harness.spec.Expect;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A model that cannot explain an observation has to say so, not return something plausible.
 *
 * <p>The failure is a silent fallback, and it costs the most: {@code parse} cannot make sense of what it was given, returns an empty state rather than
 * failing, and every prediction after that is made about a world the agent is not in. The ledger
 * fills with steps that were never really checked, certification passes, and the plan built on top
 * of it walks off a cliff. So every contract function either answers or raises, and a model that
 * answers with nothing is a model that failed.</p>
 */
public class AModelHasToAccountForWhatItIsGivenTest {

    private static final String COUNTER = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.d}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 3; }
            """;

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            value.put((String) pairs[i], pairs[i + 1]);
        }
        return value;
    }

    @Test
    public void aModelAnswersTheFourQuestionsEveryModelIsFor() {
        WorldModel model = WorldModel.load(COUNTER);

        Object state = model.parse(map("pos", 1));

        assertThat(Json.canonical(state)).isEqualTo("{\"pos\":1}");
        assertThat(Json.canonical(model.step(state, map("d", 1)))).isEqualTo("{\"pos\":2}");
        assertThat(Json.canonical(model.predict(state))).isEqualTo("{\"pos\":1}");
        assertThat(model.isGoal(state)).isFalse();
        assertThat(model.isGoal(model.parse(map("pos", 4)))).isTrue();
    }

    @Test
    public void aMissingContractFunctionIsRefusedBeforeAnythingRuns() {
        assertThatThrownBy(() -> WorldModel.load("fn parse(obs) { return obs; }"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("step")
                .hasMessageContaining("predict")
                .hasMessageContaining("is_goal");
    }

    @Test
    public void aContractFunctionOfTheWrongShapeIsRefusedBeforeAnythingRuns() {
        assertThatThrownBy(() -> WorldModel.load(COUNTER.replace(
                "fn step(state, action) { return {\"pos\": state.pos + action.d}; }",
                "fn step(state) { return state; }")))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("step")
                .hasMessageContaining("2");
    }

    /** A model that will not parse is a model that failed, not a language error to handle apart. */
    @Test
    public void aSourceThatIsNotAModelAtAllIsRefusedAsAModelFailure() {
        assertThatThrownBy(() -> WorldModel.load("fn parse(obs) { return obs. }"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("line 1");
    }

    /** The silent fallback, refused where it starts. */
    @Test
    public void groundingThatAnswersWithNothingIsAFailureRatherThanAnEmptyState() {
        WorldModel model = WorldModel.load(COUNTER.replace("return {\"pos\": obs.pos};",
                                                           "return null;"));

        assertThatThrownBy(() -> model.parse(map("pos", 1)))
                .isInstanceOf(GroundingException.class)
                .hasMessageContaining("parse");
    }

    @Test
    public void groundingThatBlowsUpNamesWhereItBlewUp() {
        WorldModel model = WorldModel.load(COUNTER);

        assertThatThrownBy(() -> model.parse(map("elsewhere", 1)))
                .isInstanceOf(GroundingException.class)
                .hasMessageContaining("parse")
                .hasMessageContaining("line 1");
    }

    @Test
    public void aMechanismThatAnswersWithNothingIsAModelError() {
        WorldModel model = WorldModel.load(COUNTER.replace(
                "return {\"pos\": state.pos + action.d};", "return null;"));

        assertThatThrownBy(() -> model.step(map("pos", 1), map("d", 1)))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("step");
    }

    @Test
    public void aModelThatWillNotStopIsStoppedRatherThanWaitedFor() {
        WorldModel model = WorldModel.load(COUNTER.replace(
                "return {\"pos\": state.pos + action.d};",
                "while (true) { let n = 1; } return state;"),
                                           new ExecutionLimits(2_000, 1_000));

        assertThatThrownBy(() -> model.step(map("pos", 1), map("d", 1)))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("steps");
    }

    @Test
    public void aPredictionMayBeTheBeliefsTheModelHoldsRatherThanTheWholeObservation() {
        WorldModel model = WorldModel.load(COUNTER.replace(
                "fn predict(state) { return {\"pos\": state.pos}; }",
                "fn predict(state) { return expect().eq(\"pos\", state.pos); }"));

        Object prediction = model.predict(map("pos", 2));

        assertThat(prediction).isInstanceOf(Expect.class);
        assertThat(((Expect) prediction).check(map("pos", 2))).isEmpty();
    }

    @Test
    public void aModelIsNamedByTheHashOfItsSource() {
        WorldModel model = WorldModel.load(COUNTER);

        assertThat(model.digest()).isEqualTo(WorldModel.load(COUNTER).digest());
        assertThat(model.digest()).isNotEqualTo(WorldModel.load(COUNTER + "\nfn extra() { return 1; }")
                                                        .digest());
        assertThat(model.source()).isEqualTo(COUNTER);
    }

    /**
     * A state carries what one observation cannot show, and search must not treat that as identity.
     *
     * <p>Two states that look the same from outside are the same place to a planner; if a field the
     * model invented distinguished them, search would expand the same position over and over.</p>
     */
    @Test
    public void whatOneObservationCannotShowIsLeftOutOfAStatesIdentity() {
        WorldModel model = WorldModel.load("""
                hidden has_key;
                fn parse(obs) { return {"pos": obs.pos, "has_key": false}; }
                fn step(state, action) { return state; }
                fn predict(state) { return {"pos": state.pos}; }
                fn is_goal(state) { return false; }
                """);

        assertThat(model.hidden()).containsExactly("has_key");
        assertThat(model.observableKey(map("pos", 1, "has_key", true)))
                .isEqualTo(model.observableKey(map("pos", 1, "has_key", false)));
        assertThat(model.key(map("pos", 1, "has_key", true)))
                .isEqualTo(model.observableKey(map("pos", 1, "has_key", true)));
    }

    @Test
    public void aModelMaySayForItselfWhatMakesTwoStatesTheSame() {
        WorldModel model = WorldModel.load(COUNTER + "fn key(state) { return state.pos; }\n");

        assertThat(model.key(map("pos", 1, "noise", 99))).isEqualTo("1");
    }

    @Test
    public void aModelWithoutItsOwnActionsUsesTheOnesTheEnvironmentOffers() {
        WorldModel model = WorldModel.load(COUNTER);
        List<Object> offered = List.of(map("d", 1), map("d", -1));

        assertThat(model.actions(map("pos", 0), offered)).isEqualTo(offered);
    }

    @Test
    public void aModelMayNarrowTheActionsWorthTrying() {
        WorldModel model = WorldModel.load(
                COUNTER + "fn actions(state) { return [{\"d\": 1}]; }\n");

        assertThat(model.actions(map("pos", 0), List.of(map("d", 1), map("d", -1))))
                .hasSize(1);
    }

    @Test
    public void aModelWithNothingToOfferAnOpenActionSpaceSaysSo() {
        WorldModel model = WorldModel.load(COUNTER);

        assertThatThrownBy(() -> model.actions(map("pos", 0), null))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("actions");
    }

    @Test
    public void anOptionalFunctionTheModelDoesNotDefineIsAbsentRatherThanWrong() {
        assertThat(WorldModel.load(COUNTER).heuristic(map("pos", 0))).isNull();
        assertThat(WorldModel.load(COUNTER + "fn heuristic(state) { return 3 - state.pos; }\n")
                           .heuristic(map("pos", 1)))
                .isEqualTo(2.0);
    }

    @Test
    public void aModelSaysForItselfWhetherItHandlesAReset() {
        assertThat(WorldModel.load(COUNTER).handlesReset()).isFalse();
        assertThat(WorldModel.load(COUNTER + "fn settings() { return {\"handles_reset\": true}; }\n")
                           .handlesReset())
                .isTrue();
    }

    /**
     * What the model asks of the harness and what it predicts about a state are two questions.
     *
     * <p>{@code settings} is the first and {@code flags} is the second. Answering both with one
     * function would mean a model could not predict that a state raises the goal flag without also
     * being read as asking the harness for something.</p>
     */
    @Test
    public void aModelPredictsOnlyTheFlagsItNames() {
        assertThat(WorldModel.load(COUNTER).flags(map("pos", 4)))
                .as("a model that names no flags is committing to none, not denying all of them")
                .isEmpty();

        WorldModel loud = WorldModel.load(
                COUNTER + "fn flags(state) { return {\"goal\": state.pos >= 3}; }\n");

        assertThat(loud.flags(map("pos", 4))).containsExactly(java.util.Map.entry("goal", true));
        assertThat(loud.settings()).isEmpty();
    }

    @Test
    public void aFlagsFunctionThatDoesNotAnswerWithFlagsIsAModelFailure() {
        WorldModel model = WorldModel.load(COUNTER + "fn flags(state) { return state.pos; }\n");

        assertThatThrownBy(() -> model.flags(map("pos", 4)))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("flags");
    }

    /** Which rules reality has tested accumulates over the model's whole life, not one call. */
    @Test
    public void aModelRemembersWhichOfItsRulesHaveBeenExercised() {
        WorldModel model = WorldModel.load(COUNTER);

        assertThat(model.coverage().hit()).isEmpty();
        model.isGoal(model.parse(map("pos", 1)));

        assertThat(model.coverage().hit()).contains("fn:parse@1:1", "fn:is_goal@4:1");
        assertThat(model.coverage().uncovered()).isNotEmpty();
    }

    /**
     * Complexity is tracked so epicycles show up as growth without new coverage.
     *
     * <p>A model that keeps gaining special cases and never gains a tested rule is being patched
     * rather than corrected, and the numbers are what make that visible.</p>
     */
    @Test
    public void aModelIsMeasuredSoThatPatchingItLooksDifferentFromCorrectingIt() {
        ModelComplexity small = WorldModel.load(COUNTER).complexity();
        ModelComplexity large = WorldModel.load(COUNTER.replace(
                "fn is_goal(state) { return state.pos >= 3; }",
                "fn is_goal(state) { if (state.pos > 9) { return true; } return state.pos >= 3; }"))
                .complexity();

        assertThat(large.nodes()).isGreaterThan(small.nodes());
        assertThat(large.arms()).isGreaterThan(small.arms());
        assertThat(small.functions()).isEqualTo(4);
        assertThat(small.render()).contains("nodes");
    }

    /**
     * A goal or a feature set is asked for by naming a function of the model, so the vocabulary a
     * plan is written in is versioned with the theory rather than invented at the call site.
     */
    @Test
    public void aQuestionAboutAStateIsAskedOfTheModelsOwnFunctions() {
        WorldModel model = WorldModel.load(COUNTER + "fn near(state) { return state.pos >= 2; }\n");
        Object     state = model.parse(map("pos", 2));

        assertThat(Json.truthy(model.ask("near", state))).isTrue();
        assertThat(Json.truthy(model.ask("is_goal", state))).isFalse();
        assertThat(Json.truthy(model.ask("near", model.parse(map("pos", 1))))).isFalse();
    }

    @Test
    public void aQuestionTheModelNeverDefinedIsRefusedWithWhatItDoesDefine() {
        WorldModel model = WorldModel.load(COUNTER);

        assertThatThrownBy(() -> model.ask("near", model.parse(map("pos", 1))))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("near")
                .hasMessageContaining("is_goal");
    }

    @Test
    public void aQuestionThatDoesNotTakeExactlyOneStateIsRefusedBeforeItRuns() {
        WorldModel model = WorldModel.load(COUNTER);

        assertThatThrownBy(() -> model.ask("step", model.parse(map("pos", 1))))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("step")
                .hasMessageContaining("one");
    }

    @Test
    public void aQuestionWithNoNameIsRefusedRatherThanGuessedAt() {
        WorldModel model = WorldModel.load(COUNTER);

        assertThatThrownBy(() -> model.ask("  ", model.parse(map("pos", 1))))
                .isInstanceOf(ModelException.class);
    }

    /**
     * A search asks its goal thousands of times and a fit asks its features once per recorded
     * state, so a name nobody defined has to be caught before either starts; otherwise an empty
     * ledger fits nothing and reports no examples, and a bad goal reads as a search that failed.
     */
    @Test
    public void aQuestionCanBeCheckedBeforeAnythingIsAskedOfIt() {
        WorldModel model = WorldModel.load(COUNTER);

        assertThat(model.question("  is_goal  ")).isEqualTo("is_goal");
        assertThatThrownBy(() -> model.question("near"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("near")
                .hasMessageContaining("is_goal");
        assertThatThrownBy(() -> model.question("step"))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("one");
    }
}
