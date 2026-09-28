package com.eonmux.cadetcoder.harness.plan;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search inside a model, and the two things a bare plan never says.
 *
 * <p>The first is why a search came back empty. "No plan" from an exhaustive search means the goal
 * is unreachable; "no plan" from a search that hit its node cap means nothing at all, and an agent
 * that cannot tell them apart will either give up on a solvable problem or grind forever on an
 * unsolvable one. Every search here therefore answers with a status.</p>
 *
 * <p>The second is which of the plan's steps turn on rules the ledger has never put to the test. A
 * plan whose fourth step walks into a branch no transition ever exercised is an experiment, and the
 * commit gate is entitled to stop there -- but only if the plan says so.</p>
 */
public class APlanIsOnlyAsGoodAsWhatTheLedgerHasTestedTest {

    private static final String CORRIDOR = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos == 3; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String ROOM = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                let p = state.pos + action.move;
                if (p < 0) { p = 0; }
                if (p > 2) { p = 2; }
                return {"pos": p};
            }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos == 9; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            """;

    private static final String WINNABLE = """
            fn parse(obs) { return {"pos": obs.pos, "won": false}; }
            fn step(state, action) {
                if (action.type == "win") { return {"pos": state.pos, "won": true}; }
                return {"pos": state.pos + 1, "won": state.won};
            }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.won; }
            fn actions(state) { return [{"type": "win"}, {"type": "walk"}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String BROKEN = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return error("this model cannot step"); }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos == 3; }
            fn actions(state) { return [{"move": 1}]; }
            """;

    private static final String GUIDED = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos == 12; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            fn heuristic(state) { return abs(12 - state.pos); }
            """;

    private static final String FAR = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos == 1000000; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String FAR_WITH_A_TALLY_IN_THE_KEY = """
            fn parse(obs) { return {"pos": obs.pos, "score": 0}; }
            fn step(state, action) {
                return {"pos": state.pos + action.move,
                        "score": state.score * 2 + action.move + 1};
            }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos == 1000000; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return [state.pos, state.score]; }
            """;

    private static final String GUARDED = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                if (state.pos >= 2) { return {"pos": state.pos + 10}; }
                return {"pos": state.pos + 1};
            }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 12; }
            fn actions(state) { return [{"move": 1}]; }
            """;

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            value.put((String) pairs[i], pairs[i + 1]);
        }
        return value;
    }

    private static List<Object> moves(int count) {
        return java.util.Collections.nCopies(count, map("move", 1));
    }

    @Test
    public void aSearchFindsTheShortestSequenceThatReachesTheGoal() {
        SearchResult result = Planner.bfs(WorldModel.load(CORRIDOR), map("pos", 0), null);

        assertThat(result.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(result.found()).isTrue();
        assertThat(Json.canonical(result.plan()))
                .isEqualTo("[{\"move\":1},{\"move\":1},{\"move\":1}]");
    }

    /** A goal that is already true is a plan of no steps, not a search that failed. */
    @Test
    public void aStartThatIsAlreadyTheGoalIsAPlanOfNoSteps() {
        SearchResult result = Planner.bfs(WorldModel.load(CORRIDOR), map("pos", 3), null);

        assertThat(result.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(result.plan()).isEmpty();
    }

    /** The distinction the whole status field exists for. */
    @Test
    public void anExhaustedSearchIsNotTheSameAnswerAsAnExhaustedBudget() {
        SearchResult exhausted = Planner.bfs(WorldModel.load(ROOM), map("pos", 0), null);
        SearchResult capped    = Planner.bfs(WorldModel.load(FAR), map("pos", 0), null,
                                             new SearchLimits(5, SearchLimits.standard().maxMillis(),
                                                              SearchLimits.ANY_DEPTH));

        assertThat(exhausted.status()).isEqualTo(SearchStatus.EXHAUSTED);
        assertThat(exhausted.plan()).isEmpty();
        assertThat(capped.status()).isEqualTo(SearchStatus.NODE_BUDGET);
        assertThat(capped.effort().expanded()).isEqualTo(5);
        assertThat(exhausted.summary()).isNotEqualTo(capped.summary());
    }

    @Test
    public void aSearchThatRunsOutOfTimeSaysWhichBudgetItHit() {
        SearchResult result = Planner.bfs(WorldModel.load(FAR), map("pos", 0), null,
                                          new SearchLimits(SearchLimits.standard().maxNodes(), 0,
                                                           SearchLimits.ANY_DEPTH));

        assertThat(result.status()).isEqualTo(SearchStatus.TIME_BUDGET);
        assertThat(result.summary()).contains("time_budget");
    }

    @Test
    public void aDepthLimitStopsTheSearchWhereItIsTold() {
        SearchResult result = Planner.bfs(WorldModel.load(CORRIDOR), map("pos", 0), null,
                                          new SearchLimits(SearchLimits.standard().maxNodes(),
                                                           SearchLimits.standard().maxMillis(), 2));

        assertThat(result.status()).isEqualTo(SearchStatus.EXHAUSTED);
        assertThat(result.effort().depthReached()).isEqualTo(2);
    }

    /** A model that breaks mid-search is a fact about the model, never a plan of nothing. */
    @Test
    public void aModelThatBreaksMakesTheSearchAnErrorRatherThanAnEmptyResult() {
        SearchResult result = Planner.bfs(WorldModel.load(BROKEN), map("pos", 0), null);

        assertThat(result.status()).isEqualTo(SearchStatus.ERROR);
        assertThat(result.error()).contains("cannot step");
        assertThat(result.found()).isFalse();
    }

    /**
     * The goal is checked before the visited set, because a {@code key} that drops a goal-relevant
     * field would otherwise throw away the answer as a place it had already been.
     */
    @Test
    public void aGoalIsFoundEvenWhenTheKeyWouldHaveHiddenIt() {
        SearchResult result = Planner.bfs(WorldModel.load(WINNABLE), map("pos", 0, "won", false),
                                          null, new SearchLimits(50, 5_000, SearchLimits.ANY_DEPTH));

        assertThat(result.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(Json.canonical(result.plan())).isEqualTo("[{\"type\":\"win\"}]");
    }

    @Test
    public void anEnvironmentsActionsAreSearchedWhenTheModelNamesNone() {
        String noActions = CORRIDOR.replace("fn actions(state) { return [{\"move\": 1}, "
                                            + "{\"move\": -1}]; }", "");

        SearchResult result = Planner.bfs(WorldModel.load(noActions), map("pos", 0),
                                          List.of(map("move", 1)));

        assertThat(result.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(result.plan()).hasSize(3);
    }

    @Test
    public void aModelsHeuristicIsUsedWhenItOffersOneAndIgnoredWhenItDoesNot() {
        SearchResult guided = Planner.search(WorldModel.load(GUIDED), map("pos", 0), null);
        SearchResult blind  = Planner.bfs(WorldModel.load(GUIDED), map("pos", 0), null);

        assertThat(guided.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(guided.plan()).hasSameSizeAs(blind.plan());
        assertThat(guided.effort().expanded()).isLessThan(blind.effort().expanded());
        assertThat(Planner.search(WorldModel.load(CORRIDOR), map("pos", 0), null).plan())
                .as("a model with no heuristic is searched breadth first")
                .hasSize(3);
    }

    /**
     * A counter in {@code key} makes every state new, so a search that can never terminate looks
     * exactly like a search that needs a bigger budget.
     */
    @Test
    public void aSearchThatCanNeverTerminateIsDiagnosedRatherThanJustReported() {
        SearchLimits limits = new SearchLimits(1_000, 30_000, SearchLimits.ANY_DEPTH);

        SearchResult leaking = Planner.bfs(WorldModel.load(FAR_WITH_A_TALLY_IN_THE_KEY),
                                           map("pos", 0, "score", 0), null, limits);
        SearchResult honest  = Planner.bfs(WorldModel.load(FAR), map("pos", 0), null, limits);

        assertThat(leaking.status()).isEqualTo(SearchStatus.NODE_BUDGET);
        assertThat(leaking.hint()).contains("counter");
        assertThat(leaking.summary()).contains("hint");
        assertThat(honest.status()).isEqualTo(SearchStatus.NODE_BUDGET);
        assertThat(honest.hint()).as("a search that merely needs a bigger budget is not diagnosed")
                .isNull();
    }

    @Test
    public void aFoundPlanIsSearchedFromAModelThatHasNotBeenSearchedWith() {
        WorldModel model = WorldModel.load(CORRIDOR);

        Planner.bfs(model, map("pos", 0), null);

        assertThat(model.coverage().hit())
                .as("searching must not spend the coverage a certificate measures")
                .isEmpty();
    }

    @Test
    public void aPlanIsSimulatedStepByStepWithWhatEachStepPredicts() {
        Simulation simulation = Planner.simulate(WorldModel.load(CORRIDOR), map("pos", 0), moves(3));

        assertThat(simulation.ok()).isTrue();
        assertThat(simulation.steps()).hasSize(3);
        assertThat(Json.canonical(simulation.steps().get(0).state())).isEqualTo("{\"pos\":1}");
        assertThat(Json.canonical(simulation.steps().get(2).prediction())).isEqualTo("{\"pos\":3}");
        assertThat(Json.canonical(simulation.finalState())).isEqualTo("{\"pos\":3}");
    }

    @Test
    public void aSimulationSaysWhereTheGoalFirstBecomesTrue() {
        Simulation simulation = Planner.simulate(WorldModel.load(CORRIDOR), map("pos", 0), moves(5));

        assertThat(simulation.goalAt()).isEqualTo(2);
        assertThat(Planner.simulate(WorldModel.load(CORRIDOR), map("pos", 0), moves(1)).goalAt())
                .isNull();
    }

    @Test
    public void aSimulationThatBreaksNamesTheStepThatBrokeIt() {
        Simulation simulation = Planner.simulate(WorldModel.load(BROKEN), map("pos", 0), moves(3));

        assertThat(simulation.ok()).isFalse();
        assertThat(simulation.errorAt()).isEqualTo(0);
        assertThat(simulation.error()).contains("cannot step");
        assertThat(simulation.steps()).isEmpty();
    }

    @Test
    public void aStepThatTurnsOnARuleTheLedgerNeverExercisedIsNamed() {
        WorldModel model  = WorldModel.load(GUARDED);
        Set<String> known = Planner.simulate(model, map("pos", 0), moves(2)).arms();

        PlanAudit audit = Planner.audit(Planner.simulate(model, map("pos", 0), moves(3)), known);

        assertThat(audit.clean()).isFalse();
        assertThat(audit.untestedSteps()).containsExactly(2);
        assertThat(audit.untestedArms().get(2)).isNotEmpty();
        assertThat(audit.firstUntested()).isEqualTo(2);
        assertThat(audit.render()).contains("2");
    }

    @Test
    public void aPlanThatOnlyUsesTestedRulesHasNothingToAudit() {
        WorldModel model  = WorldModel.load(GUARDED);
        Set<String> known = Planner.simulate(model, map("pos", 0), moves(3)).arms();

        PlanAudit audit = Planner.audit(Planner.simulate(model, map("pos", 0), moves(2)), known);

        assertThat(audit.clean()).isTrue();
        assertThat(audit.untestedSteps()).isEmpty();
        assertThat(audit.firstUntested()).isEqualTo(PlanAudit.NOTHING_UNTESTED);
        assertThat(audit.render()).contains("every rule");
    }

    @Test
    public void anAuditIsCarriedOnTheResultWithoutChangingTheOneItWasGiven() {
        WorldModel   model    = WorldModel.load(GUARDED);
        SearchResult searched = Planner.bfs(model, map("pos", 0), null);
        PlanAudit    audit    = Planner.audit(Planner.simulate(model, map("pos", 0), moves(3)),
                                              Set.of());

        SearchResult audited = searched.audited(audit);

        assertThat(searched.audit().clean()).as("the original result is left as it was").isTrue();
        assertThat(audited.audit().clean()).isFalse();
        assertThat(audited.status()).isEqualTo(searched.status());
        assertThat(audited.summary()).contains("untested");
    }

    @Test
    public void aSearchResultReadsBackAsAValueTheHarnessCanKeep() {
        SearchResult result = Planner.bfs(WorldModel.load(CORRIDOR), map("pos", 0), null);

        Object value = result.toValue();

        assertThat(Json.at(value, "status")).isEqualTo("found");
        assertThat(Json.at(value, "expanded")).isNotNull();
        assertThat(Json.canonical(Json.at(value, "plan")))
                .isEqualTo("[{\"move\":1},{\"move\":1},{\"move\":1}]");
        assertThat(result.summary()).contains("found").contains("expanded=");
    }
}
