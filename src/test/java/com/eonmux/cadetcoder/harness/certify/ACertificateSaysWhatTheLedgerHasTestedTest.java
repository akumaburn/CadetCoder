package com.eonmux.cadetcoder.harness.certify;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.env.Environment;
import com.eonmux.cadetcoder.harness.env.StepOutcome;
import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.ModelRegistry;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A certificate is the only thing that turns a model from a guess into a claim that can fail.
 *
 * <p>It says one sentence: this model, replayed over everything that really happened, reproduced
 * every transition -- and, unlike a backtest, it also says which of the model's rules that replay
 * actually put to the test. Both halves matter. A green replay of a model whose interesting half was
 * never reached is exactly the certificate that gets an agent into trouble, because the untested
 * half is where the plan built on it goes wrong.</p>
 *
 * <p>Each test here locks out one failure: a replay that skipped the
 * first transition and hid an off-by-one; a model whose {@code step} quietly edited the state it was
 * given; a green mechanism sitting on top of a {@code parse} that had stopped agreeing with it; and
 * a goal predicate nothing ever checked.</p>
 */
public class ACertificateSaysWhatTheLedgerHasTestedTest {

    @Rule
    public TemporaryFolder workspace = new TemporaryFolder();

    private static final String COUNTER = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 3; }
            """;

    /** A model whose eyes fail it partway along: it reads the start of the walk and not the end. */
    private static final String SHORT_SIGHTED = COUNTER.replace(
            "fn parse(obs) { return {\"pos\": obs.pos}; }",
            "fn parse(obs) { if (obs.pos >= 2) { return error(\"cannot read this far\"); } "
            + "return {\"pos\": obs.pos}; }");

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            value.put((String) pairs[i], pairs[i + 1]);
        }
        return value;
    }

    private Ledger freshLedger() throws IOException {
        return new Ledger(workspace.newFolder().toPath().resolve("ledger.jsonl"));
    }

    /** A walk from the origin, with the environment reporting the goal exactly when it is reached. */
    private Ledger walk(int steps) throws IOException {
        Ledger ledger = freshLedger();
        for (int at = 0; at < steps; at++) {
            ledger.append(Transition.proposal(0, map("pos", at), map("move", 1), map("pos", at + 1),
                                              map(StepOutcome.GOAL_FLAG, at + 1 >= 3), map()));
        }
        return ledger;
    }

    @Test
    public void aModelThatReproducesEveryTransitionIsGreen() throws IOException {
        Certificate certificate = Certification.certify(WorldModel.load(COUNTER), walk(4));

        assertThat(certificate.green()).isTrue();
        assertThat(certificate.checked()).isEqualTo(4);
        assertThat(certificate.ok()).isEqualTo(4);
        assertThat(certificate.mismatched()).isZero();
        assertThat(certificate.reports()).isEmpty();
        assertThat(certificate.summary()).contains("GREEN").contains(certificate.modelDigest());
    }

    /** An off-by-one: a replay that starts at the second transition proves nothing. */
    @Test
    public void everyTransitionIsCheckedIncludingTheFirst() throws IOException {
        Ledger ledger = freshLedger();
        ledger.append(Transition.proposal(0, map("pos", 0), map("move", 1), map("pos", 7), map(),
                                          map()));
        ledger.append(Transition.proposal(0, map("pos", 7), map("move", 1), map("pos", 8), map(),
                                          map()));

        Certificate certificate = Certification.certify(WorldModel.load(COUNTER), ledger);

        assertThat(certificate.checked()).isEqualTo(2);
        assertThat(certificate.reports()).first()
                .extracting(TransitionReport::index).isEqualTo(0);
    }

    @Test
    public void aModelThatMispredictsIsRedAndSaysWhere() throws IOException {
        String wrong = COUNTER.replace("state.pos + action.move", "state.pos + 2");

        Certificate certificate = Certification.certify(WorldModel.load(wrong), walk(2));

        assertThat(certificate.green()).isFalse();
        assertThat(certificate.mismatched()).isEqualTo(2);
        assertThat(certificate.reports()).hasSize(2);
        assertThat(certificate.reports().get(0).kind()).isEqualTo(ReportKind.EXACT);
        assertThat(certificate.reports().get(0).violations().toString()).contains("pos");
        assertThat(certificate.summary()).contains("RED");
    }

    /** A named belief that fails says which belief, and what the whole prediction was. */
    @Test
    public void aConstraintPredictionThatFailsNamesTheBeliefAndThePrediction() throws IOException {
        String believer = COUNTER.replace("fn predict(state) { return {\"pos\": state.pos}; }",
                                          "fn predict(state) { return expect().le(\"pos\", 0); }");

        Certificate certificate = Certification.certify(WorldModel.load(believer), walk(1));

        assertThat(certificate.green()).isFalse();
        assertThat(certificate.reports().get(0).kind()).isEqualTo(ReportKind.CONSTRAINT);
        assertThat(certificate.reports().get(0).violations().toString())
                .contains("pos <= 0")
                .contains("the prediction was");
    }

    /** A step that edits the state it was handed makes every search built on it meaningless. */
    @Test
    public void aStepThatChangesTheStateItWasGivenIsRefused() throws IOException {
        String impure = COUNTER.replace(
                "fn step(state, action) { return {\"pos\": state.pos + action.move}; }",
                "fn step(state, action) { state.pos = state.pos + action.move; return state; }");

        Certificate certificate = Certification.certify(WorldModel.load(impure), walk(2));

        assertThat(certificate.green()).isFalse();
        assertThat(certificate.reports().get(0).kind()).isEqualTo(ReportKind.IMPURE_STEP);
        assertThat(certificate.reports().get(0).violations().toString()).contains("step");
    }

    @Test
    public void groundingThatFailsIsAModelFailureRatherThanEvidenceAboutTheWorld() throws IOException {
        String blind = COUNTER.replace("fn parse(obs) { return {\"pos\": obs.pos}; }",
                                       "fn parse(obs) { return error(\"I cannot read this\"); }");

        Certificate certificate = Certification.certify(WorldModel.load(blind), walk(2));

        assertThat(certificate.green()).isFalse();
        assertThat(certificate.reports().get(0).kind()).isEqualTo(ReportKind.GROUNDING_FAILURE);
        assertThat(certificate.reports().get(0).violations().toString()).contains("I cannot read this");
    }

    /** The goal predicate is the one belief every model holds, so it is always falsifiable. */
    @Test
    public void theGoalPredicateIsCheckedAgainstWhatTheEnvironmentReported() throws IOException {
        Ledger ledger = freshLedger();
        for (int at = 0; at < 3; at++) {
            ledger.append(Transition.proposal(0, map("pos", at), map("move", 1), map("pos", at + 1),
                                              map(), map()));
        }

        Certificate certificate = Certification.certify(WorldModel.load(COUNTER), ledger);

        assertThat(certificate.green()).isFalse();
        assertThat(certificate.reports()).hasSize(1);
        assertThat(certificate.reports().get(0).index()).isEqualTo(2);
        assertThat(certificate.reports().get(0).violations().toString()).contains("goal_predicate");
    }

    @Test
    public void aModelAnswersForTheFlagsItNamesAndNoOthers() throws IOException {
        Ledger ledger = freshLedger();
        ledger.append(Transition.proposal(0, map("pos", 0), map("move", 1), map("pos", 1),
                                          map("weather", true), map()));

        assertThat(Certification.certify(WorldModel.load(COUNTER), ledger).green())
                .as("a flag the model never mentions is not a contradiction of it")
                .isTrue();

        String claims = COUNTER + "fn flags(state) { return {\"weather\": false}; }\n";
        Certificate certificate = Certification.certify(WorldModel.load(claims), ledger);

        assertThat(certificate.green()).isFalse();
        assertThat(certificate.reports().get(0).violations().toString()).contains("flag:weather");
    }

    @Test
    public void aCertificateSaysWhichRulesTheLedgerNeverExercised() throws IOException {
        String guarded = COUNTER.replace(
                "fn step(state, action) { return {\"pos\": state.pos + action.move}; }",
                """
                fn step(state, action) {
                  if (action.move > 100) { return {"pos": 0}; }
                  return {"pos": state.pos + action.move};
                }
                """);

        Certificate certificate = Certification.certify(WorldModel.load(guarded), walk(2));

        assertThat(certificate.green()).isTrue();
        assertThat(certificate.coverage().uncovered())
                .as("nothing in this ledger ever moved more than one square")
                .isNotEmpty();
        assertThat(certificate.coverage().uncovered().toString()).contains("action.move > 100");
        assertThat(certificate.summary()).contains("coverage");
    }

    /** Rolling replay is what puts a model's memory to the test at all. */
    @Test
    public void aReplayRollsWithinAnEpisodeSoHiddenStateIsTestedToo() throws IOException {
        String remembers = """
                hidden steps;
                fn parse(obs) { return {"pos": obs.pos, "steps": 0}; }
                fn step(state, action) {
                  return {"pos": state.pos + action.move, "steps": state.steps + 1};
                }
                fn predict(state) { return {"pos": state.pos}; }
                fn is_goal(state) { return state.pos >= 3; }
                """;

        Certificate certificate = Certification.certify(WorldModel.load(remembers), walk(4));

        assertThat(certificate.green()).isTrue();
        assertThat(Json.at(certificate.finalState(), "steps"))
                .as("a hidden field one observation cannot show is carried across re-grounding")
                .isEqualTo(4.0);
    }

    @Test
    public void aResetRegroundsRatherThanBeingSteppedThrough() throws IOException {
        Ledger ledger = freshLedger();
        ledger.append(Transition.proposal(0, map("pos", 2), Environment.RESET_ACTION, map("pos", 0),
                                          map(), map()));
        ledger.append(Transition.proposal(1, map("pos", 0), map("move", 1), map("pos", 1), map(),
                                          map()));

        Certificate certificate = Certification.certify(WorldModel.load(COUNTER), ledger);

        assertThat(certificate.green()).isTrue();
        assertThat(certificate.resets()).isEqualTo(1);
        assertThat(certificate.checked()).as("a reset the model does not model is not a prediction")
                .isEqualTo(1);
    }

    @Test
    public void aModelThatSaysItHandlesResetsIsHeldToThem() throws IOException {
        String restarts = """
                fn parse(obs) { return {"pos": obs.pos}; }
                fn step(state, action) {
                  if (has(action, "type")) { return {"pos": 0}; }
                  return {"pos": state.pos + action.move};
                }
                fn predict(state) { return {"pos": state.pos}; }
                fn is_goal(state) { return state.pos >= 3; }
                fn settings() { return {"handles_reset": true}; }
                """;
        Ledger ledger = freshLedger();
        ledger.append(Transition.proposal(0, map("pos", 0), map("move", 1), map("pos", 1), map(),
                                          map()));
        ledger.append(Transition.proposal(1, map("pos", 1), Environment.RESET_ACTION, map("pos", 0),
                                          map(), map()));

        Certificate certificate = Certification.certify(WorldModel.load(restarts), ledger);

        assertThat(certificate.green()).isTrue();
        assertThat(certificate.resets()).isZero();
        assertThat(certificate.checked())
                .as("a model that claims to model resets has that claim checked")
                .isEqualTo(2);
    }

    /**
     * The representation and the rule have to agree with each other, not merely with the world.
     *
     * <p>A {@code step} that reaches a state {@code parse} would never produce from the same
     * observation is a model whose two halves have drifted apart. Every prediction still holds, so
     * nothing else would notice, and a plan several steps long walks straight off the end of it.</p>
     */
    @Test
    public void driftBetweenTheRuleAndTheRepresentationIsReported() throws IOException {
        String drifting = COUNTER.replace(
                "fn step(state, action) { return {\"pos\": state.pos + action.move}; }",
                "fn step(state, action) { return {\"pos\": state.pos + action.move, \"ghost\": 1}; }");

        Certificate lenient = Certification.certify(WorldModel.load(drifting), walk(2));

        assertThat(lenient.drifted()).isEqualTo(2);
        assertThat(lenient.mismatched()).isZero();
        assertThat(lenient.green()).as("drift is reported before it is fatal").isTrue();
        assertThat(lenient.reports().get(0).groundingDrift()).isNotNull();

        assertThat(Certification.certify(WorldModel.load(drifting), walk(2), true).green())
                .as("a run that cannot afford drift asks for it to be fatal")
                .isFalse();
    }

    @Test
    public void aCertificateStopsCoveringTheLedgerAsSoonAsTheLedgerMovesOn() throws IOException {
        Ledger      ledger      = walk(2);
        Certificate certificate = Certification.certify(WorldModel.load(COUNTER), ledger);

        assertThat(certificate.covers(ledger.head())).isTrue();

        ledger.append(Transition.proposal(0, map("pos", 2), map("move", 1), map("pos", 3),
                                          map(StepOutcome.GOAL_FLAG, true), map()));

        assertThat(certificate.covers(ledger.head()))
                .as("a certificate is about the ledger it replayed, not the one there is now")
                .isFalse();
    }

    @Test
    public void reportsAreCappedSoACertificateStaysReadable() throws IOException {
        String wrong = COUNTER.replace("state.pos + action.move", "state.pos + 2");

        Certificate certificate = Certification.certify(WorldModel.load(wrong), walk(40));

        assertThat(certificate.mismatched()).isEqualTo(40);
        assertThat(certificate.reports()).hasSize(Certification.MAX_REPORTS);
    }

    /** The store reads one number out of a certificate; this is the one it reads. */
    @Test
    public void aCertificateReadsBackAsAValueTheStoreCanKeep() throws IOException {
        Certificate certificate = Certification.certify(WorldModel.load(COUNTER), walk(2));
        Object      value       = certificate.toValue();

        assertThat(Json.at(value, ModelRegistry.COVERED_PATH))
                .isEqualTo(certificate.coverage().covered());
        assertThat(Json.at(value, "green")).isEqualTo(true);
        assertThat(Json.at(value, "model")).isEqualTo(certificate.modelDigest());
    }

    /** The gate checks one step live, and it has to be the same check the replay makes. */
    @Test
    public void theLiveCheckIsTheSameCheckTheReplayMakes() {
        WorldModel model = WorldModel.load(COUNTER);
        Object     state = model.parse(map("pos", 0));

        LiveCheck held = Certification.checkLive(model, state, map("move", 1), map("pos", 1), map());

        assertThat(held.held()).isTrue();
        assertThat(held.violations()).isEmpty();
        assertThat(Json.at(held.state(), "pos")).isEqualTo(1.0);

        LiveCheck broken = Certification.checkLive(model, state, map("move", 1), map("pos", 9),
                                                   map());

        assertThat(broken.held()).isFalse();
        assertThat(broken.violations().toString()).contains("pos");
    }

    /** The claim goes into the ledger, so the check has to hand it back rather than be asked twice. */
    @Test
    public void aLiveCheckCarriesBackTheClaimItChecked() {
        WorldModel model = WorldModel.load(COUNTER);

        LiveCheck check = Certification.checkLive(model, model.parse(map("pos", 0)),
                                                  map("move", 1), map("pos", 1), map());

        assertThat(Json.at(check.prediction(), "pos")).isEqualTo(1.0);
        assertThat(check.describe()).contains("pos");
    }

    /** Steps already checked as they happened are carried forward, not replayed a second time. */
    @Test
    public void aCertificateIsCarriedForwardOverStepsThatWereCheckedLive() throws IOException {
        Ledger      ledger  = walk(3);
        Certificate first   = Certification.certify(WorldModel.load(COUNTER), ledger);
        Certificate carried = first.extended("newhead0000", first.ledgerLength() + 2, 2,
                                             Set.of("nowhere:1"), map("pos", 5));

        assertThat(carried.checked()).isEqualTo(first.checked() + 2);
        assertThat(carried.ok()).isEqualTo(first.ok() + 2);
        assertThat(carried.mismatched()).isEqualTo(first.mismatched());
        assertThat(carried.covers("newhead0000")).isTrue();
        assertThat(carried.covers(ledger.head())).isFalse();
        assertThat(carried.coverage().hit()).containsAll(first.coverage().hit())
                                            .contains("nowhere:1");
        assertThat(Json.at(carried.finalState(), "pos")).isEqualTo(5);
        assertThat(first.covers(ledger.head())).as("extending must not change the one it was given")
                                               .isTrue();
    }

    /**
     * A rule fitted to states the certifier never saw would be a rule about a different theory.
     *
     * <p>Anything that wants to reason over the ledger in a model's own terms -- fitting a predicate
     * to what the environment flagged, say -- needs the state the model was in at each transition.
     * Deriving those a second time somewhere else is how two answers about the same ledger start
     * disagreeing, so the replay that certifies is the replay that hands them out.</p>
     */
    @Test
    public void theStatesTheReplayRolledCanBeAskedForRatherThanDerivedAgain() throws IOException {
        Ledger ledger = walk(3);

        Certificate certificate = Certification.certify(WorldModel.load(COUNTER), ledger);
        var states = Certification.states(WorldModel.load(COUNTER), ledger);

        assertThat(states).hasSize(ledger.size());
        assertThat(Json.canonical(states.get(0))).isEqualTo("{\"pos\":1}");
        assertThat(Json.canonical(states.get(2))).isEqualTo("{\"pos\":3}");
        assertThat(Json.canonical(states.get(2))).isEqualTo(Json.canonical(certificate.finalState()));
    }

    /** A state the model could not reach is left out rather than filled in with something plausible. */
    @Test
    public void aStateTheModelCouldNotReachComesBackAsNothingAtAll() throws IOException {
        Ledger ledger = walk(1);
        String blind  = COUNTER.replace("fn parse(obs) { return {\"pos\": obs.pos}; }",
                                        "fn parse(obs) { return error(\"cannot read that\"); }");

        var states = Certification.states(WorldModel.load(blind), ledger);

        assertThat(states).hasSize(1);
        assertThat(states.get(0)).isNull();
    }

    /**
     * A transition is one transition, however many ways the model failed at it.
     *
     * <p>A prediction that held and a re-grounding that did not are two facts about the same ledger
     * entry. Counted separately, a certificate says more predictions held than were ever checked,
     * and the numbers a run reads to decide whether a model is worth acting on stop adding up.</p>
     */
    @Test
    public void aTransitionIsCountedOnceHoweverManyWaysTheModelFailedAtIt() throws IOException {
        Certificate certificate = Certification.certify(WorldModel.load(SHORT_SIGHTED), walk(2));

        assertThat(certificate.green()).isFalse();
        assertThat(certificate.checked()).isEqualTo(2);
        assertThat(certificate.mismatched()).isEqualTo(1);
        assertThat(certificate.ok() + certificate.mismatched())
                .as("every checked transition holds or does not; there is no third answer")
                .isEqualTo(certificate.checked());
        assertThat(certificate.reports()).extracting(TransitionReport::index).contains(1);
    }

    /**
     * A reset the model cannot read is a failure like any other, and is counted like one.
     *
     * <p>Reading where the world restarted is the only thing a model is asked to do at an episode
     * start, so a {@code parse} that cannot is a model the ledger has contradicted. Counting it as a
     * re-grounding that happened would let a certificate come back green with the failure printed
     * underneath it.</p>
     */
    @Test
    public void aResetTheModelCannotReadIsCountedAsTheFailureItIs() throws IOException {
        Ledger ledger = freshLedger();
        ledger.append(Transition.proposal(0, map("pos", 0), map("move", 1), map("pos", 1), map(),
                                          map()));
        ledger.append(Transition.proposal(1, map("pos", 1), Environment.RESET_ACTION, map("pos", 9),
                                          map(), map()));

        Certificate certificate = Certification.certify(WorldModel.load(SHORT_SIGHTED), ledger);

        assertThat(certificate.green()).isFalse();
        assertThat(certificate.resets()).as("a reset that could not be grounded is not one")
                .isZero();
        assertThat(certificate.checked() + certificate.resets())
                .as("every transition of the ledger is accounted for exactly once")
                .isEqualTo(ledger.size());
        assertThat(certificate.ok() + certificate.mismatched()).isEqualTo(certificate.checked());
    }
}
