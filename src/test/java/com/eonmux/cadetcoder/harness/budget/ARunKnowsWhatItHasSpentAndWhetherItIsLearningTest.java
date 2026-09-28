package com.eonmux.cadetcoder.harness.budget;

import com.eonmux.cadetcoder.harness.Json;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a run is allowed to spend, and how it finds out it has stopped learning.
 *
 * <p>Two different things are being counted here and they must not be confused. Actions, tokens,
 * time and deliberations are allowances: when one runs out the run stops, and it stops whether or
 * not it was going well. The plateau is the opposite question -- the run is still inside every
 * allowance and is spending them without getting anywhere, which is the failure that is invisible
 * from inside a loop and obvious from one snapshot to the next.</p>
 *
 * <p>The rule that matters most here is that a charge which breaks the budget still counts. The
 * action has already happened to the world; a tally that rolled it back to stay under the limit
 * would be a record of a world that does not exist.</p>
 */
public class ARunKnowsWhatItHasSpentAndWhetherItIsLearningTest {

    private static Progress progress(int deliberation, int certifiedOk, int mismatches,
                                     int ledgerLength) {
        return new Progress(deliberation, certifiedOk, mismatches, ledgerLength, "abc", 0);
    }

    private static Budget stuck(int entries, boolean observing) {
        Budget budget = new Budget(BudgetLimits.unlimited());
        for (int at = 0; at <= entries; at++) {
            budget.record(progress(at, 3, 2, observing ? 10 + at : 10));
        }
        return budget;
    }

    @Test
    public void aFreshBudgetHasSpentNothing() {
        Spend spend = new Budget(BudgetLimits.unlimited()).spend();

        assertThat(spend.actions()).isZero();
        assertThat(spend.tokensIn() + spend.tokensOut()).isZero();
        assertThat(spend.deliberations()).isZero();
    }

    @Test
    public void actionsAreChargedAndTheRunStopsWhenTheAllowanceIsGone() {
        Budget budget = new Budget(new BudgetLimits(2, BudgetLimits.UNLIMITED,
                                                    BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED));

        budget.chargeActions(1);
        budget.chargeActions(1);

        assertThatThrownBy(() -> budget.chargeActions(1))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("3").hasMessageContaining("2");
    }

    /** The world has already changed; a tally that hides the last action is a lie about it. */
    @Test
    public void theChargeThatBreaksTheAllowanceStillCounts() {
        Budget budget = new Budget(new BudgetLimits(1, BudgetLimits.UNLIMITED,
                                                    BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED));
        budget.chargeActions(1);

        assertThatThrownBy(() -> budget.chargeActions(1))
                .isInstanceOf(BudgetExceededException.class);

        assertThat(budget.spend().actions()).isEqualTo(2);
    }

    @Test
    public void anAllowanceNobodySetIsNeverExhausted() {
        Budget budget = new Budget(BudgetLimits.unlimited());

        budget.chargeActions(1_000_000);
        budget.chargeTokens(1_000_000, 1_000_000);
        budget.checkTime();

        assertThat(budget.spend().actions()).isEqualTo(1_000_000);
    }

    @Test
    public void tokensAreChargedInBothDirectionsAgainstOneAllowance() {
        Budget budget = new Budget(new BudgetLimits(BudgetLimits.UNLIMITED, 10,
                                                    BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED));

        budget.chargeTokens(4, 4);

        assertThat(budget.spend().tokensIn()).isEqualTo(4);
        assertThat(budget.spend().tokensOut()).isEqualTo(4);
        assertThatThrownBy(() -> budget.chargeTokens(2, 2))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("token");
    }

    @Test
    public void timeIsAskedAboutRatherThanCharged() {
        Budget budget = new Budget(new BudgetLimits(BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED,
                                                    0, BudgetLimits.UNLIMITED));

        assertThatThrownBy(budget::checkTime)
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("time");
    }

    @Test
    public void deliberationsAreCountedByTheOneCallThatChecksThem() {
        Budget budget = new Budget(new BudgetLimits(BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED,
                                                    BudgetLimits.UNLIMITED, 2));

        budget.deliberate();
        budget.deliberate();

        assertThat(budget.spend().deliberations()).isEqualTo(2);
        assertThatThrownBy(budget::deliberate)
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("deliberation");
    }

    /** A reset costs an episode, not an action: charging it would make restarting look expensive. */
    @Test
    public void resetsAndSurprisesAreCountedWithoutBeingChargedAsActions() {
        Budget budget = new Budget(new BudgetLimits(1, BudgetLimits.UNLIMITED,
                                                    BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED));

        budget.countReset();
        budget.countReset();
        budget.countSurprise();
        budget.countToolCall();

        assertThat(budget.spend().resets()).isEqualTo(2);
        assertThat(budget.spend().surprises()).isEqualTo(1);
        assertThat(budget.spend().toolCalls()).isEqualTo(1);
        assertThat(budget.spend().actions()).isZero();
    }

    @Test
    public void whatHasBeenSpentReadsBackAsAValueAndAsALine() {
        Budget budget = new Budget(BudgetLimits.unlimited());
        budget.chargeActions(3);
        budget.chargeTokens(10, 20);

        Object value = budget.spend().toValue();

        assertThat(Json.at(value, "actions")).isEqualTo(3);
        assertThat(Json.at(value, "tokens_in")).isEqualTo(10L);
        assertThat(budget.spend().render()).contains("3 actions").contains("30 tokens");
    }

    @Test
    public void thereIsNoPlateauUntilThereIsEnoughToCompare() {
        Budget budget = new Budget(BudgetLimits.unlimited());
        budget.record(progress(0, 3, 2, 10));
        budget.record(progress(1, 3, 2, 11));

        assertThat(budget.plateau()).isNull();
    }

    @Test
    public void aRunThatKeepsObservingWithoutLearningIsAPlateau() {
        String warning = stuck(Budget.PLATEAU_WINDOW, true).plateau();

        assertThat(warning).contains("new observations").contains("no model improvement")
                            .doesNotContain("no new observations");
    }

    @Test
    public void aRunThatIsNotEvenObservingIsToldSoDifferently() {
        String warning = stuck(Budget.PLATEAU_WINDOW, false).plateau();

        assertThat(warning).contains("no new observations");
    }

    @Test
    public void aRunThatCertifiesMoreOfTheLedgerIsNotOnAPlateau() {
        Budget budget = new Budget(BudgetLimits.unlimited());
        for (int at = 0; at <= Budget.PLATEAU_WINDOW; at++) {
            budget.record(progress(at, 3 + at, 2, 10 + at));
        }

        assertThat(budget.plateau()).isNull();
    }

    @Test
    public void aRunThatIsGettingFewerThingsWrongIsNotOnAPlateauEither() {
        Budget budget = new Budget(BudgetLimits.unlimited());
        for (int at = 0; at <= Budget.PLATEAU_WINDOW; at++) {
            budget.record(progress(at, 3, 9 - at, 10 + at));
        }

        assertThat(budget.plateau()).isNull();
    }

    @Test
    public void aShorterWindowNoticesAPlateauSooner() {
        Budget budget = new Budget(BudgetLimits.unlimited());
        budget.record(progress(0, 3, 2, 10));
        budget.record(progress(1, 3, 2, 11));

        assertThat(budget.plateau(1)).isNotNull();
        assertThat(budget.plateau(Budget.PLATEAU_WINDOW)).isNull();
    }

    @Test
    public void everyDeliberationIsKeptInTheOrderItHappened() {
        Budget budget = new Budget(BudgetLimits.unlimited());
        budget.record(progress(0, 3, 2, 10));
        budget.record(progress(1, 4, 1, 11));

        assertThat(budget.history()).extracting(Progress::certifiedOk).containsExactly(3, 4);
    }

    @Test
    public void aDeliberationReadsBackAsAValueAndAsALine() {
        Progress progress = new Progress(2, 7, 1, 30, "0123456789abcdef", 5);

        assertThat(Json.at(progress.toValue(), "certified_ok")).isEqualTo(7);
        assertThat(progress.render()).contains("#2").contains("certified=7").contains("wrong=1")
                                     .contains("model=01234567")
                                     .doesNotContain("0123456789abcdef");
    }

    @Test
    public void aDeliberationWithNoModelYetSaysSoRatherThanShowingNothing() {
        assertThat(new Progress(0, 0, 0, 0, null, 0).render()).contains("model=none");
    }

    @Test
    public void anAllowanceCannotBeSetToANonsenseNumber() {
        assertThatThrownBy(() -> new BudgetLimits(-2, BudgetLimits.UNLIMITED,
                                                  BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    public void whatTheAgentIsToldAboutItsBudgetNamesTheAllowancesItHas() {
        Budget budget = new Budget(new BudgetLimits(10, BudgetLimits.UNLIMITED,
                                                    BudgetLimits.UNLIMITED, BudgetLimits.UNLIMITED));
        budget.chargeActions(4);

        assertThat(budget.render()).contains("4/10");
    }
}
