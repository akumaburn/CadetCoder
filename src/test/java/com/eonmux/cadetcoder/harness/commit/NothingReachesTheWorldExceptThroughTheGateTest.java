package com.eonmux.cadetcoder.harness.commit;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.budget.Budget;
import com.eonmux.cadetcoder.harness.budget.BudgetLimits;
import com.eonmux.cadetcoder.harness.certify.Certificate;
import com.eonmux.cadetcoder.harness.certify.Certification;
import com.eonmux.cadetcoder.harness.env.Corridor;
import com.eonmux.cadetcoder.harness.env.StepOutcome;
import com.eonmux.cadetcoder.harness.ledger.Ledger;
import com.eonmux.cadetcoder.harness.ledger.Transition;
import com.eonmux.cadetcoder.harness.model.WorldModel;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The commit gate is the only channel from thinking to acting, and it is the last place anything
 * can be stopped.
 *
 * <p>Everything checked here is a way an agent gets from a plausible plan to a broken world. It
 * acts on a certificate that was about a ledger two steps ago. It walks confidently through a rule
 * of its own model that nothing has ever exercised. It takes an irreversible action on a theory. It
 * keeps going after the world has already told it the theory is wrong. The gate's job is to make
 * each of those a refusal with a reason rather than a surprise afterwards, and to make sure that
 * whatever does reach the world is in the ledger before the next thing happens.</p>
 */
public class NothingReachesTheWorldExceptThroughTheGateTest {

    @Rule
    public TemporaryFolder workspace = new TemporaryFolder();

    /** Predicts the corridor exactly, so any disagreement with the world is the world's doing. */
    private static final String COUNTER = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 99; }
            """;

    /** The same corridor, with a rule that only turns on past position two. */
    private static final String GUARDED = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) {
                if (state.pos >= 2) {
                    return {"pos": state.pos + action.move};
                }
                return {"pos": state.pos + action.move};
            }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 99; }
            """;

    /** The same corridor, with the goal the environment actually reports. */
    private static final String SEEKER = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 3; }
            """;

    /** Believes only where it is, and stops being able to read the corridor at all. */
    private static final String LOOSE = """
            fn parse(obs) {
                if (has(obs, "blocked")) { return error("this corridor cannot be read"); }
                return {"pos": obs.pos};
            }
            fn step(state, action) { return {"pos": state.pos + action.move}; }
            fn predict(state) { return expect().eq("pos", state.pos); }
            fn is_goal(state) { return state.pos >= 99; }
            """;

    /** Reads the corridor, but cannot take a step at all. */
    private static final String LAME = """
            fn parse(obs) { return {"pos": obs.pos}; }
            fn step(state, action) { return error("this model cannot step"); }
            fn predict(state) { return {"pos": state.pos}; }
            fn is_goal(state) { return state.pos >= 99; }
            """;

    private Corridor   world;
    private Ledger     ledger;
    private Budget     budget;
    private CommitGate gate;

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            value.put((String) pairs[i], pairs[i + 1]);
        }
        return value;
    }

    private static List<Object> moves(int count) {
        List<Object> actions = new ArrayList<>();
        for (int at = 0; at < count; at++) {
            actions.add(map("move", 1));
        }
        return actions;
    }

    @Before
    public void setUp() throws IOException {
        world  = new Corridor();
        ledger = new Ledger(workspace.newFolder().toPath().resolve("ledger.jsonl"));
        budget = new Budget(BudgetLimits.unlimited());
        gate   = new CommitGate(world, ledger, budget, CommitPolicy.standard());
    }

    private CommitGate gateWith(CommitPolicy policy) {
        return new CommitGate(world, ledger, budget, policy);
    }

    /** Real actions, so that the ledger and the world agree about where things are. */
    private void walk(int steps) {
        for (int at = 0; at < steps; at++) {
            assertThat(gate.probe(moves(1)).status()).isEqualTo(CommitStatus.COMPLETED);
        }
    }

    private Certificate certificateFor(WorldModel model) {
        return Certification.certify(model, ledger);
    }

    // ----------------------------------------------------------------- refusals before anything

    @Test
    public void anEmptyPlanIsRefusedRatherThanQuietlyDoingNothing() {
        CommitResult result = gate.probe(List.of());

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("no actions");
        assertThat(ledger.size()).isZero();
    }

    @Test
    public void aPlanLongerThanThePolicyAllowsIsRefused() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);

        CommitResult result = gateWith(CommitPolicy.standard().withMaxActionsPerCommit(2))
                .commit(model, certificateFor(model), moves(3));

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("3").contains("2");
        assertThat(world.taken()).hasSize(1);
    }

    /** Acting with no model at all is allowed, but only far enough to see what happens. */
    @Test
    public void aBlindProbeIsLimitedToAFewActions() {
        CommitResult result = gate.probe(moves(CommitPolicy.standard().maxBlindActions() + 1));

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("blind").contains("certify");
        assertThat(world.taken()).isEmpty();
    }

    @Test
    public void aBlindProbeThatIsShortEnoughReachesTheWorld() {
        CommitResult result = gate.probe(moves(2));

        assertThat(result.status()).isEqualTo(CommitStatus.COMPLETED);
        assertThat(result.executed()).isEqualTo(2);
        assertThat(ledger.size()).isEqualTo(2);
        assertThat(ledger.get(0).modelHash()).as("a blind probe claims nothing").isNull();
        assertThat(result.certificate()).isNull();
    }

    @Test
    public void aCertificateThatTheLedgerHasMovedPastIsRefused() {
        walk(1);
        WorldModel  model       = WorldModel.load(COUNTER);
        Certificate certificate = certificateFor(model);
        walk(1);

        CommitResult result = gate.commit(model, certificate, moves(1));

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("stale").contains("certify");
    }

    @Test
    public void aCertificateThatIsRedIsRefusedUnlessThePolicySaysOtherwise() {
        walk(1);
        world.driftFrom(0);
        walk(1);
        world.driftFrom(Corridor.NEVER);
        WorldModel  model = WorldModel.load(COUNTER);
        Certificate red   = certificateFor(model);
        assertThat(red.green()).isFalse();

        assertThat(gate.commit(model, red, moves(1)).reason()).contains("RED");
        assertThat(gateWith(CommitPolicy.standard().allowingRed()).commit(model, red, moves(1))
                                                                  .status())
                .isEqualTo(CommitStatus.COMPLETED);
    }

    @Test
    public void aCertificateAboutADifferentModelIsRefused() {
        walk(1);
        WorldModel counter = WorldModel.load(COUNTER);
        WorldModel guarded = WorldModel.load(GUARDED);

        CommitResult result = gate.commit(guarded, certificateFor(counter), moves(1));

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("not about this model");
    }

    @Test
    public void aPlanThatDoesNotSimulateIsRefusedBeforeAnythingHappens() {
        walk(1);
        WorldModel model = WorldModel.load(LAME);

        CommitResult result = gateWith(CommitPolicy.standard().allowingRed())
                .commit(model, certificateFor(model), moves(2));

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("does not simulate").contains("cannot step");
        assertThat(world.taken()).hasSize(1);
    }

    // ----------------------------------------------------------------------------- what runs

    @Test
    public void everyActionThatReachesTheWorldIsInTheLedgerWithWhatWasClaimedAboutIt() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);

        CommitResult result = gate.commit(model, certificateFor(model), moves(2));

        assertThat(result.status()).isEqualTo(CommitStatus.COMPLETED);
        assertThat(result.executed()).isEqualTo(2);
        assertThat(result.ledgerIndices()).containsExactly(1, 2);
        assertThat(ledger.size()).isEqualTo(3);
        Transition last = ledger.get(2);
        assertThat(last.modelHash()).isEqualTo(model.digest());
        assertThat(last.predictionHeld()).isTrue();
        assertThat(Json.at(last.predicted(), "pos")).isEqualTo(3.0);
        assertThat(world.taken()).hasSize(3);
    }

    @Test
    public void aStepTheWorldContradictsStopsThePlanThere() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);
        world.driftFrom(2);

        CommitResult result = gate.commit(model, certificateFor(model), moves(4));

        assertThat(result.status()).isEqualTo(CommitStatus.SURPRISE);
        assertThat(result.executed()).isEqualTo(2);
        assertThat(world.taken()).as("the rest of the plan is discarded").hasSize(3);
        assertThat(budget.spend().surprises()).isEqualTo(1);
    }

    @Test
    public void theStepThatSurprisedTheGateIsNamedWithWhatWasExpected() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);
        world.driftFrom(0);

        CommitResult result = gate.commit(model, certificateFor(model), moves(2));

        Surprise surprise = result.surprise();
        assertThat(surprise).isNotNull();
        assertThat(surprise.step()).isZero();
        assertThat(surprise.ledgerIndex()).isEqualTo(1);
        assertThat(surprise.expected()).contains("pos").contains("2");
        assertThat(surprise.violations().toString()).contains("pos");
        assertThat(surprise.toString()).isEqualTo(surprise.render());
        assertThat(Json.at(result.toValue(), "surprise.step")).isEqualTo(0);
        assertThat(result.summary()).contains("SURPRISE").contains("pos");
    }

    /**
     * A prediction can hold on a step whose observation the model can no longer read at all, and
     * carrying on would mean acting from a state nothing has confirmed.
     */
    @Test
    public void aModelThatCanNoLongerReadTheWorldStopsRatherThanCarryingOnFromMemory() {
        walk(1);
        WorldModel  model       = WorldModel.load(LOOSE);
        Certificate certificate = certificateFor(model);
        assertThat(certificate.green()).isTrue();
        world.blockFrom(1);

        CommitResult result = gate.commit(model, certificate, moves(3));

        assertThat(result.status()).isEqualTo(CommitStatus.SURPRISE);
        assertThat(result.executed()).isEqualTo(1);
        assertThat(result.surprise().violations().toString()).contains("ground");
        assertThat(result.certificate().covers(ledger.head())).isFalse();
    }

    @Test
    public void reachingTheGoalEndsTheCommit() {
        world.goalAt(3);
        walk(1);
        WorldModel model = WorldModel.load(SEEKER);

        CommitResult result = gate.commit(model, certificateFor(model), moves(4));

        assertThat(result.status()).isEqualTo(CommitStatus.GOAL);
        assertThat(result.executed()).isEqualTo(2);
        assertThat(Json.truthy(result.flags().get(StepOutcome.GOAL_FLAG))).isTrue();
    }

    @Test
    public void aTerminalStepEndsTheCommit() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);
        world.terminalAt(3);

        CommitResult result = gate.commit(model, certificateFor(model), moves(4));

        assertThat(result.status()).isEqualTo(CommitStatus.TERMINAL);
        assertThat(result.executed()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------ untested rules

    @Test
    public void aPlanIsCutOffAfterTheFirstStepThatExercisesAnUntestedRule() {
        walk(1);
        WorldModel model = WorldModel.load(GUARDED);

        CommitResult result = gate.commit(model, certificateFor(model), moves(3));

        assertThat(result.status()).isEqualTo(CommitStatus.TRUNCATED_UNTESTED);
        assertThat(result.truncatedAt()).isEqualTo(1);
        assertThat(result.executed()).as("the untested rule is tested once, then stopped")
                                     .isEqualTo(2);
        assertThat(result.audit().untestedSteps()).containsExactly(1);
        assertThat(result.reason()).contains("untested");
    }

    @Test
    public void aPolicyCanCutThePlanOffBeforeTheUntestedStepInstead() {
        walk(1);
        WorldModel model = WorldModel.load(GUARDED);

        CommitResult result = gateWith(CommitPolicy.standard().withUntested(UntestedRule.BEFORE))
                .commit(model, certificateFor(model), moves(3));

        assertThat(result.status()).isEqualTo(CommitStatus.TRUNCATED_UNTESTED);
        assertThat(result.truncatedAt()).isEqualTo(1);
        assertThat(result.executed()).isEqualTo(1);
    }

    @Test
    public void anUntestedRuleIsRunAnywayWhenTheCommitSaysItIsAnExperiment() {
        walk(1);
        WorldModel model = WorldModel.load(GUARDED);

        CommitResult result = gate.commit(model, certificateFor(model), moves(3), true, null);

        assertThat(result.status()).isEqualTo(CommitStatus.COMPLETED);
        assertThat(result.executed()).isEqualTo(3);
        assertThat(result.audit().untestedSteps()).as("running it does not make it tested before")
                                                  .containsExactly(1);
    }

    /** The whole plan is untested when the very first step turns on a rule nothing has exercised. */
    @Test
    public void aPlanWhoseFirstStepIsUntestedRunsNothingUnderTheStricterRule() {
        walk(2);
        WorldModel model = WorldModel.load(GUARDED);
        int        acted = world.taken().size();

        CommitResult result = gateWith(CommitPolicy.standard().withUntested(UntestedRule.BEFORE))
                .commit(model, certificateFor(model), moves(2));

        assertThat(result.status()).isEqualTo(CommitStatus.TRUNCATED_UNTESTED);
        assertThat(result.truncatedAt()).isZero();
        assertThat(result.executed()).isZero();
        assertThat(world.taken()).hasSize(acted);
    }

    // ------------------------------------------------------------------------- reversibility

    @Test
    public void anIrreversibleActionNeedsAGreenCertifiedModel() {
        CommitResult result = gate.probe(List.of(map("move", 1, "kind", Corridor.DELETE)));

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("irreversible").contains("certified");
        assertThat(world.taken()).isEmpty();
    }

    @Test
    public void anIrreversibleActionOnATestedRuleIsAllowedWithAGreenModel() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);

        CommitResult result = gate.commit(model, certificateFor(model),
                                          List.of(map("move", 1, "kind", Corridor.DELETE)));

        assertThat(result.status()).isEqualTo(CommitStatus.COMPLETED);
    }

    @Test
    public void anIrreversibleActionOnAnUntestedRuleIsRefusedEvenWithAGreenModel() {
        walk(2);
        WorldModel model = WorldModel.load(GUARDED);

        CommitResult result = gate.commit(model, certificateFor(model),
                                          List.of(map("move", 1, "kind", Corridor.DELETE)));

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("untested");
        assertThat(world.taken()).hasSize(2);
    }

    @Test
    public void anIrreversibleActionCanBeDeniedOutright() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);

        CommitResult result = gateWith(CommitPolicy.standard()
                                                   .withIrreversible(IrreversibleRule.DENY))
                .commit(model, certificateFor(model), List.of(map("move", 1, "kind", Corridor.DELETE)));

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("denies");
    }

    @Test
    public void anIrreversibleActionCanBeSentToSomeoneToApprove() {
        walk(1);
        WorldModel   model = WorldModel.load(COUNTER);
        List<String> asked = new ArrayList<>();

        CommitResult refused = gateWith(CommitPolicy.standard()
                                                    .withIrreversible(IrreversibleRule.APPROVAL)
                                                    .withApproval((action, context) -> {
                                                        asked.add(context);
                                                        return false;
                                                    }))
                .commit(model, certificateFor(model), List.of(map("move", 1, "kind", Corridor.DELETE)));

        assertThat(refused.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(refused.reason()).contains("approval");
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0)).contains("step 0").contains("green");

        CommitResult allowed = gateWith(CommitPolicy.standard()
                                                    .withIrreversible(IrreversibleRule.APPROVAL)
                                                    .withApproval((action, context) -> true))
                .commit(model, certificateFor(model), List.of(map("move", 1, "kind", Corridor.DELETE)));

        assertThat(allowed.status()).isEqualTo(CommitStatus.COMPLETED);
    }

    @Test
    public void anApprovalPolicyWithNobodyToAskRefusesRatherThanAssumingYes() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);

        CommitResult result = gateWith(CommitPolicy.standard()
                                                   .withIrreversible(IrreversibleRule.APPROVAL))
                .commit(model, certificateFor(model), List.of(map("move", 1, "kind", Corridor.DELETE)));

        assertThat(result.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(result.reason()).contains("nobody").contains("approve");
    }

    @Test
    public void anIrreversibleActionCanBeAllowedWithoutAnyModelAtAll() {
        CommitResult result = gateWith(CommitPolicy.standard()
                                                   .withIrreversible(IrreversibleRule.ALLOW))
                .probe(List.of(map("move", 1, "kind", Corridor.DELETE)));

        assertThat(result.status()).isEqualTo(CommitStatus.COMPLETED);
    }

    @Test
    public void aCostlyActionCanBeMadeToRequireAModel() {
        CommitResult allowed = gate.probe(List.of(map("move", 1, "kind", Corridor.PAY)));
        assertThat(allowed.status()).isEqualTo(CommitStatus.COMPLETED);

        CommitResult refused = gateWith(CommitPolicy.standard().requiringModelForCostly())
                .probe(List.of(map("move", 1, "kind", Corridor.PAY)));

        assertThat(refused.status()).isEqualTo(CommitStatus.REFUSED);
        assertThat(refused.reason()).contains("costly");
    }

    /** The reversibility rules are read from the steps that will run, not the ones that will not. */
    @Test
    public void anIrreversibleStepBeyondTheTruncationDoesNotStopTheStepsBeforeIt() {
        walk(1);
        WorldModel   model   = WorldModel.load(GUARDED);
        List<Object> actions = new ArrayList<>(moves(2));
        actions.add(map("move", 1, "kind", Corridor.DELETE));

        CommitResult result = gate.commit(model, certificateFor(model), actions);

        assertThat(result.status()).isEqualTo(CommitStatus.TRUNCATED_UNTESTED);
        assertThat(result.executed()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------- budget

    @Test
    public void theBudgetStopsTheCommitAndWhatWasSpentIsStillRecorded() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);
        Budget     tight = new Budget(new BudgetLimits(1, BudgetLimits.UNLIMITED,
                                                       BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED));

        CommitResult result = new CommitGate(world, ledger, tight, CommitPolicy.standard())
                .commit(model, certificateFor(model), moves(3));

        assertThat(result.status()).isEqualTo(CommitStatus.BUDGET);
        assertThat(result.executed()).isEqualTo(1);
        assertThat(result.reason()).contains("allowance");
        assertThat(ledger.size()).isEqualTo(2);
    }

    /**
     * What the run is billed for is what the world did, not what it was about to be asked to do.
     *
     * <p>The allowance is asked about before an action and charged after it. Charged first and
     * stopped on the refusal, the action that broke the allowance is paid for and never taken: the
     * tally says two where the ledger and the corridor both say one. Everything that reads the two
     * together -- a plateau, a certificate's coverage, the line the agent is shown before it
     * decides what it can afford -- would then be reading about a run nobody made.</p>
     */
    @Test
    public void theActionThatBrokeTheAllowanceIsNotBilledForBecauseItNeverHappened() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);
        Budget     tight = new Budget(new BudgetLimits(1, BudgetLimits.UNLIMITED,
                                                       BudgetLimits.UNLIMITED,
                                                       BudgetLimits.UNLIMITED));

        new CommitGate(world, ledger, tight, CommitPolicy.standard())
                .commit(model, certificateFor(model), moves(3));

        assertThat(tight.spend().actions())
                .as("one action was taken, so one action is what the run spent")
                .isEqualTo(1);
        assertThat(world.taken()).hasSize(2);
        assertThat(ledger.size()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------- certificates

    @Test
    public void aCommitThatWentAsPredictedCarriesTheCertificateForwardToTheNewHead() {
        walk(1);
        WorldModel  model  = WorldModel.load(COUNTER);
        Certificate before = certificateFor(model);

        CommitResult result = gate.commit(model, before, moves(2));

        Certificate after = result.certificate();
        assertThat(after).isNotNull();
        assertThat(after.covers(ledger.head())).isTrue();
        assertThat(after.checked()).isEqualTo(before.checked() + 2);
        assertThat(after.ok()).isEqualTo(before.ok() + 2);
        assertThat(after.green()).isTrue();
        assertThat(Certification.certify(model, ledger).checked())
                .as("carrying forward must agree with replaying the whole ledger")
                .isEqualTo(after.checked());
    }

    @Test
    public void aSurpriseLeavesTheCertificateBehindTheLedgerRatherThanExtendingIt() {
        walk(1);
        WorldModel  model  = WorldModel.load(COUNTER);
        Certificate before = certificateFor(model);
        world.driftFrom(0);

        CommitResult result = gate.commit(model, before, moves(2));

        assertThat(result.status()).isEqualTo(CommitStatus.SURPRISE);
        assertThat(result.certificate().covers(ledger.head()))
                .as("a contradicted model has to be certified again, not carried forward")
                .isFalse();
    }

    @Test
    public void aCommitThatTestsAnUntestedRuleCarriesThatRuleIntoTheCertificate() {
        walk(1);
        WorldModel  model  = WorldModel.load(GUARDED);
        Certificate before = certificateFor(model);

        CommitResult result = gate.commit(model, before, moves(3));

        assertThat(result.certificate().coverage().covered())
                .isGreaterThan(before.coverage().covered());
    }

    // ------------------------------------------------------------------------------ resets

    @Test
    public void aResetStartsANewEpisodeAndCostsAnEpisodeRatherThanAnAction() {
        walk(2);

        Object observation = gate.reset("starting over");

        assertThat(Json.at(observation, "pos")).isEqualTo(0);
        Transition reset = ledger.get(ledger.size() - 1);
        assertThat(reset.isReset()).isTrue();
        assertThat(reset.episode()).isEqualTo(1);
        assertThat(reset.note()).isEqualTo("starting over");
        assertThat(budget.spend().resets()).isEqualTo(1);
        assertThat(budget.spend().actions()).isEqualTo(2);
    }

    @Test
    public void theGateReadsWhereTheWorldIsFromTheLedgerRatherThanRememberingItItself() {
        walk(1);

        assertThat(Json.at(gate.observation(), "pos")).isEqualTo(1);

        CommitGate other = new CommitGate(world, ledger, budget, CommitPolicy.standard());

        assertThat(Json.canonical(other.observation()))
                .as("a second gate on the same ledger sees the same world")
                .isEqualTo(Json.canonical(gate.observation()));
    }

    @Test
    public void aGateOnAnEmptyLedgerAsksTheWorldWhereItIs() {
        assertThat(Json.at(gate.observation(), "pos")).isEqualTo(0);
        assertThat(ledger.size()).isZero();
    }

    // ----------------------------------------------------------------------------- reporting

    @Test
    public void aCommitReadsBackAsAValueAndAsALine() {
        walk(1);
        WorldModel model = WorldModel.load(COUNTER);

        CommitResult result = gate.commit(model, certificateFor(model), moves(2), false, "a note");

        assertThat(Json.at(result.toValue(), "status")).isEqualTo("completed");
        assertThat(Json.at(result.toValue(), "executed")).isEqualTo(2);
        assertThat(result.summary()).contains("commit completed").contains("executed=2");
        assertThat(result.toString()).isEqualTo(result.summary());
        assertThat(ledger.get(1).note()).isEqualTo("a note");
    }

    @Test
    public void aRefusalNamesTheReasonInEveryFormItIsRead() {
        CommitResult result = gate.probe(List.of());

        assertThat(Json.at(result.toValue(), "status")).isEqualTo("refused");
        assertThat(Json.at(result.toValue(), "reason")).isEqualTo(result.reason());
        assertThat(result.summary()).contains("refused").contains(result.reason());
    }

    @Test
    public void whatTheAgentIsToldAboutTheGateNamesWhatItHasToProduceToAct() {
        String told = gate.policy().render();

        assertThat(told).contains("certificate")
                        .contains(String.valueOf(CommitPolicy.standard().maxActionsPerCommit()))
                        .contains(String.valueOf(CommitPolicy.standard().maxBlindActions()))
                        .contains("irreversible");
    }

    @Test
    public void aPolicyCannotBeSetToANonsenseLength() {
        assertThatThrownBy(() -> CommitPolicy.standard().withMaxActionsPerCommit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one action");
    }
}
