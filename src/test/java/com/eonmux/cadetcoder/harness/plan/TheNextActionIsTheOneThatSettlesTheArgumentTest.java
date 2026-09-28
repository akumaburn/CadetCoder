package com.eonmux.cadetcoder.harness.plan;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The experiment that tells two theories apart, found rather than reasoned about.
 *
 * <p>An agent holding two models that both survive the whole ledger has learned nothing by
 * certifying either of them. What separates them is an action on which they predict different
 * things, and the useful one is the shortest such action sequence, because every real step costs
 * budget and may not be undoable. That is a breadth-first search over joint states, and it is here
 * so the agent is handed the experiment instead of being asked to imagine one.</p>
 *
 * <p>The delicate half is what counts as disagreeing. A model that names the next observation
 * exactly and one that states beliefs about it are not automatically at odds: they are at odds only
 * when the beliefs refuse the named observation. Two models that both state beliefs are treated as
 * rivals unless they state the same ones, because an expectation is a claim about what its author
 * is willing to be wrong about, and two authors willing to be wrong about different things have not
 * agreed on anything.</p>
 */
public class TheNextActionIsTheOneThatSettlesTheArgumentTest {

    private static final String PLAIN = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String PLAIN_TWIN = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                let here = state.pos;
                return {"pos": here + action.move};
            }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String STICKY = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                let p = state.pos + action.move;
                if (p > 2) { p = 2; }
                return {"pos": p};
            }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String CLAMPED = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                let p = state.pos + action.move;
                if (p < 0) { p = 0; }
                if (p > 2) { p = 2; }
                return {"pos": p};
            }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String CLAMPED_TWIN = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                return {"pos": min(max(state.pos + action.move, 0), 2)};
            }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String CLAMPED_EXPECTING = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                return {"pos": min(max(state.pos + action.move, 0), 2)};
            }
            fn predict(state) { return expect().eq("pos", state.pos); }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String CLAMPED_EXPECTING_TWIN = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                let p = state.pos + action.move;
                if (p < 0) { p = 0; }
                if (p > 2) { p = 2; }
                return {"pos": p};
            }
            fn predict(state) { return expect().eq("pos", state.pos); }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String CLAMPED_EXPECTING_ELSEWHERE = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                return {"pos": min(max(state.pos + action.move, 0), 2)};
            }
            fn predict(state) { return expect().eq("pos", state.pos + 1); }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String CLAMPED_EXPECTING_SOMETHING_ELSE = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                return {"pos": min(max(state.pos + action.move, 0), 2)};
            }
            fn predict(state) { return expect().present("blocked"); }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}, {"move": -1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String UNREADABLE = """
            fn parse(obs) { return error("this model cannot read that observation"); }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}]; }
            fn key(state) { return state.pos; }
            """;

    private static final String BROKEN = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return error("this model cannot step"); }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return false; }
            fn actions(state) { return [{"move": 1}]; }
            fn key(state) { return state.pos; }
            """;

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            value.put((String) pairs[i], pairs[i + 1]);
        }
        return value;
    }

    private static List<WorldModel> candidates(String... sources) {
        List<WorldModel> models = new ArrayList<>();
        for (String source : sources) {
            models.add(WorldModel.load(source));
        }
        return models;
    }

    @Test
    public void theExperimentIsTheShortestSequenceOnWhichTheCandidatesPredictDifferentThings() {
        DiscriminationResult result = Discrimination.between(candidates(PLAIN, STICKY),
                                                             map("pos", 0), null);

        assertThat(result.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(result.found()).isTrue();
        assertThat(Json.canonical(result.actions()))
                .isEqualTo("[{\"move\":1},{\"move\":1},{\"move\":1}]");
        assertThat(result.disagreement().step()).isEqualTo(2);
        assertThat(Json.canonical(result.disagreement().action())).isEqualTo("{\"move\":1}");
    }

    @Test
    public void whatEachCandidatePredictedIsNamedAgainstTheModelThatPredictedIt() {
        List<WorldModel> models = candidates(PLAIN, STICKY);

        Disagreement found = Discrimination.between(models, map("pos", 0), null).disagreement();

        assertThat(found.predictions()).containsOnlyKeys(models.get(0).digest(),
                                                         models.get(1).digest());
        assertThat(found.predictions().get(models.get(0).digest())).isEqualTo("{\"pos\":3}");
        assertThat(found.predictions().get(models.get(1).digest())).isEqualTo("{\"pos\":2}");
        assertThat(found.render()).contains("step 2").contains(models.get(0).digest());
    }

    @Test
    public void candidatesThatPredictTheSameThingStayInTheSameCamp() {
        List<WorldModel> models = candidates(PLAIN, PLAIN_TWIN, STICKY);

        Disagreement found = Discrimination.between(models, map("pos", 0), null).disagreement();

        assertThat(found.groups()).hasSize(2);
        assertThat(found.groups().get(0)).containsExactly(models.get(0).digest(),
                                                          models.get(1).digest());
        assertThat(found.groups().get(1)).containsExactly(models.get(2).digest());
    }

    @Test
    public void candidatesNothingCanTellApartAreReportedAsSuchRatherThanAsAFailedSearch() {
        DiscriminationResult result = Discrimination.between(candidates(CLAMPED, CLAMPED_TWIN),
                                                             map("pos", 0), null);

        assertThat(result.status()).isEqualTo(SearchStatus.EXHAUSTED);
        assertThat(result.found()).isFalse();
        assertThat(result.actions()).isEmpty();
        assertThat(result.disagreement()).isNull();
        assertThat(result.hint()).contains("observationally equivalent");
    }

    @Test
    public void anExpectationAgreesWithAnExactPredictionItAccepts() {
        DiscriminationResult result = Discrimination.between(
                candidates(CLAMPED, CLAMPED_EXPECTING), map("pos", 0), null);

        assertThat(result.status())
                .as("a model willing to be wrong about less has not thereby disagreed")
                .isEqualTo(SearchStatus.EXHAUSTED);
    }

    @Test
    public void anExpectationDisagreesWithAnExactPredictionItRefuses() {
        List<WorldModel> models = candidates(CLAMPED, CLAMPED_EXPECTING_ELSEWHERE);

        DiscriminationResult result = Discrimination.between(models, map("pos", 0), null);

        assertThat(result.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(result.disagreement().step()).isEqualTo(0);
        assertThat(result.disagreement().predictions().get(models.get(1).digest()))
                .contains("expect[");
    }

    @Test
    public void twoExpectationsAboutDifferentBeliefsAreTreatedAsRivals() {
        DiscriminationResult result = Discrimination.between(
                candidates(CLAMPED_EXPECTING, CLAMPED_EXPECTING_SOMETHING_ELSE),
                map("pos", 0), null);

        assertThat(result.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(result.disagreement().step()).isEqualTo(0);
        assertThat(result.disagreement().groups()).hasSize(2);
    }

    @Test
    public void twoExpectationsAboutTheSameBeliefsAreNotAnExperiment() {
        DiscriminationResult result = Discrimination.between(
                candidates(CLAMPED_EXPECTING, CLAMPED_EXPECTING_TWIN), map("pos", 0), null);

        assertThat(result.status())
                .as("two authors willing to be wrong about the same things have agreed")
                .isEqualTo(SearchStatus.EXHAUSTED);
    }

    @Test
    public void theSameModelTwiceIsNotTwoTheories() {
        DiscriminationResult result = Discrimination.between(
                candidates(CLAMPED_EXPECTING, CLAMPED_EXPECTING), map("pos", 0), null);

        assertThat(result.status())
                .as("a search would report them equivalent, which says nothing")
                .isEqualTo(SearchStatus.ERROR);
        assertThat(result.error()).contains("same model");
    }

    @Test
    public void oneCandidateIsNotAnArgument() {
        DiscriminationResult result = Discrimination.between(candidates(PLAIN), map("pos", 0), null);

        assertThat(result.status()).isEqualTo(SearchStatus.ERROR);
        assertThat(result.error()).contains("at least two");
        assertThat(result.effort().expanded()).isZero();
    }

    @Test
    public void anObservationACandidateCannotReadStopsTheExperimentBeforeItStarts() {
        DiscriminationResult result = Discrimination.between(candidates(PLAIN, UNREADABLE),
                                                             map("pos", 0), null);

        assertThat(result.status()).isEqualTo(SearchStatus.ERROR);
        assertThat(result.error()).contains("cannot read");
    }

    @Test
    public void aCandidateThatBreaksInTheMiddleOfTheSearchIsReportedAsTheModelFailingIt() {
        DiscriminationResult result = Discrimination.between(candidates(PLAIN, BROKEN),
                                                             map("pos", 0), null);

        assertThat(result.status()).isEqualTo(SearchStatus.ERROR);
        assertThat(result.error()).contains("this model cannot step");
    }

    @Test
    public void aSearchStoppedByItsNodeBudgetSaysSoRatherThanReportingNoExperiment() {
        DiscriminationResult result = Discrimination.between(candidates(PLAIN, STICKY),
                                                             map("pos", 0), null,
                                                             new SearchLimits(2, 30_000, 6));

        assertThat(result.status()).isEqualTo(SearchStatus.NODE_BUDGET);
        assertThat(result.effort().expanded()).isEqualTo(2);
        assertThat(result.disagreement()).isNull();
    }

    @Test
    public void aSearchStoppedByItsTimeBudgetSaysSoAsWell() {
        DiscriminationResult result = Discrimination.between(candidates(PLAIN, STICKY),
                                                             map("pos", 0), null,
                                                             new SearchLimits(200_000, 0, 6));

        assertThat(result.status()).isEqualTo(SearchStatus.TIME_BUDGET);
    }

    @Test
    public void theHorizonIsTheOnlyReasonAReachableExperimentIsNotFound() {
        DiscriminationResult tooShort = Discrimination.between(candidates(PLAIN, STICKY),
                                                               map("pos", 0), null,
                                                               SearchLimits.standard().toDepth(2));
        DiscriminationResult farEnough = Discrimination.between(candidates(PLAIN, STICKY),
                                                                map("pos", 0), null,
                                                                SearchLimits.standard().toDepth(3));

        assertThat(tooShort.status()).isEqualTo(SearchStatus.EXHAUSTED);
        assertThat(farEnough.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(farEnough.actions()).hasSize(3);
    }

    @Test
    public void theActionsSearchedAreTheOnesTheEnvironmentOffersWhenItOffersAny() {
        String openEnded = """
                fn parse(obs) { return {"pos": obs.pos}; }
                fn step(state, action) { return {"pos": state.pos + action.move}; }
                fn predict(state) { return {"pos": state.pos}; }
                fn is_goal(state) { return false; }
                fn key(state) { return state.pos; }
                """;
        String openEndedSticky = """
                fn parse(obs) { return {"pos": obs.pos}; }
                fn step(state, action) {
                    let p = state.pos + action.move;
                    if (p > 2) { p = 2; }
                    return {"pos": p};
                }
                fn predict(state) { return {"pos": state.pos}; }
                fn is_goal(state) { return false; }
                fn key(state) { return state.pos; }
                """;

        DiscriminationResult result = Discrimination.between(
                candidates(openEnded, openEndedSticky), map("pos", 0),
                List.of(map("move", 1)));

        assertThat(result.status()).isEqualTo(SearchStatus.FOUND);
        assertThat(Json.canonical(result.actions()))
                .isEqualTo("[{\"move\":1},{\"move\":1},{\"move\":1}]");
    }

    @Test
    public void anExperimentIsSearchedForWithModelsThatHaveNotBeenSearchedWith() {
        List<WorldModel> models = candidates(PLAIN, STICKY);

        Discrimination.between(models, map("pos", 0), null);

        assertThat(models.get(0).coverage().hit())
                .as("searching for an experiment must not spend the coverage a certificate measures")
                .isEmpty();
        assertThat(models.get(1).coverage().hit()).isEmpty();
    }

    @Test
    public void anExperimentReadsBackAsAValueAndAsALine() {
        DiscriminationResult result = Discrimination.between(candidates(PLAIN, STICKY),
                                                             map("pos", 0), null);

        Object value = result.toValue();

        assertThat(Json.at(value, "status")).isEqualTo("found");
        assertThat(Json.canonical(Json.at(value, "actions")))
                .isEqualTo("[{\"move\":1},{\"move\":1},{\"move\":1}]");
        assertThat(Json.at(value, "disagreement.step")).isEqualTo(2);
        assertThat(Json.at(value, "expanded")).isNotNull();
        assertThat(result.summary()).contains("discriminate found").contains("expanded=");
        assertThat(result.toString()).isEqualTo(result.summary());
    }
}
