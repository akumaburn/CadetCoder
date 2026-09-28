package com.eonmux.cadetcoder.timers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A firing survives a turn that never reached the model.
 *
 * <p><b>The defect</b>: the notice was taken while the prompt was being BUILT, and building a prompt
 * is not sending one. The agent loop then checks for an interrupt and can return before asking
 * anything, and the model call itself can fail -- and in both cases the firing had already been
 * consumed and the timer already advanced. The reminder was gone: never shown to anyone, its number
 * counted against the limit, and the next one not owed until another whole interval had passed. A
 * timer set to check on a long job three times could quietly deliver none of them.</p>
 *
 * <p>So taking a firing is now two steps. It is withdrawn when the prompt is built, and it is spent
 * only once the prompt has actually been sent; a turn that ends any other way puts it back exactly
 * as it was.</p>
 */
public class AfiringIsSpentOnlyWhenTheModelHasSeenItTest {

    private static final Duration EVERY_MINUTE = Duration.ofMinutes(1);

    private Instant start;

    @Before
    public void clearTimers() {
        TimerRegistry.clearAll();
        start = Instant.parse("2026-09-13T10:00:00Z");
    }

    @After
    public void leaveNothingRunning() {
        TimerRegistry.clearAll();
    }

    private Instant aMinuteLater() {
        return start.plus(EVERY_MINUTE);
    }

    @Test
    public void aturnThatNeverAskedTheModelGivesTheFiringBack() {
        TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        List<TimerFiring> withdrawn = TimerRegistry.takeDue(aMinuteLater());
        assertThat(withdrawn).hasSize(1);
        assertThat(TimerRegistry.returnUndelivered()).isEqualTo(1);

        List<TimerFiring> again = TimerRegistry.takeDue(aMinuteLater());
        assertThat(again).hasSize(1);
        assertThat(again.get(0).nonce())
                .as("the firing that was never seen is still the first one")
                .isEqualTo(1);
        assertThat(again.get(0).instruction()).isEqualTo("check the build");
    }

    @Test
    public void afiringTheModelHasSeenIsNotOfferedAgain() {
        TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        assertThat(TimerRegistry.takeDue(aMinuteLater())).hasSize(1);
        TimerRegistry.delivered();

        assertThat(TimerRegistry.takeDue(aMinuteLater())).isEmpty();
        assertThat(TimerRegistry.returnUndelivered())
                .as("nothing is owed back once it has been delivered")
                .isZero();
    }

    /** The last firing of a limited timer is the one most worth not losing. */
    @Test
    public void thelastFiringOfAlimitedTimerComesBackToo() {
        TimerRegistry.create("check the build once", EVERY_MINUTE, 1, start);

        assertThat(TimerRegistry.takeDue(aMinuteLater())).hasSize(1);
        assertThat(TimerRegistry.active())
                .as("a spent timer is forgotten as it fires")
                .isEmpty();

        assertThat(TimerRegistry.returnUndelivered()).isEqualTo(1);
        assertThat(TimerRegistry.active()).hasSize(1);
        assertThat(TimerRegistry.takeDue(aMinuteLater())).hasSize(1);
    }

    @Test
    public void aturnWithNothingDueHasNothingToGiveBack() {
        TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);

        assertThat(TimerRegistry.takeDue(start)).isEmpty();

        assertThat(TimerRegistry.returnUndelivered()).isZero();
        assertThat(TimerRegistry.active()).hasSize(1);
    }

    /** Confirming delivery twice, or with nothing withdrawn, changes nothing. */
    @Test
    public void confirmingNothingIsHarmless() {
        TimerRegistry.delivered();
        TimerRegistry.delivered();

        assertThat(TimerRegistry.active()).isEmpty();
    }

    /** A scope that has ended owes nothing, so nothing can be put back into it. */
    @Test
    public void afinishedScopeCannotBeRefilled() {
        TimerScope.in("Worker 1", () -> {
            TimerRegistry.create("check the build", EVERY_MINUTE, AgentTimer.UNLIMITED, start);
            assertThat(TimerRegistry.takeDue(aMinuteLater())).hasSize(1);
        });

        TimerScope.in("Worker 1", () -> {
            assertThat(TimerRegistry.returnUndelivered()).isZero();
            assertThat(TimerRegistry.active()).isEmpty();
        });
    }
}
