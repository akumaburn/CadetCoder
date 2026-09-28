package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.timers.AgentTimer;
import com.eonmux.cadetcoder.timers.TimerRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command a model uses to arrange its own check-in.
 *
 * <p><b>The defect</b>: the arguments here are written by a model rather than typed, and the two
 * ways it will get them wrong both used to be silent. An instruction of several unquoted words --
 * {@code timer create --every 2m check the build} -- is what a model actually writes, and a parser
 * that took only the first operand would set a timer reminding it to "check". An interval given in
 * the documented {@code --every=2m} form is what the catalog tells it to write, and a loop that only
 * matched {@code --every 2m} would set no interval at all.</p>
 *
 * <p><b>What is locked here</b>: that an unquoted instruction survives whole; that both option forms
 * work; that a call missing the interval or the instruction is refused rather than guessed at, and
 * leaves nothing behind; that a limit is carried; and that cancelling names what it could not
 * find.</p>
 */
public class SettingACheckInFromInsideARunTest {

    private TimerCommand timer;

    @Before
    public void clearTimers() {
        TimerRegistry.clearAll();
        timer = new TimerCommand();
    }

    @After
    public void leaveNothingRunning() {
        TimerRegistry.clearAll();
    }

    /** What a model actually writes: the instruction is not quoted and is not one word. */
    @Test
    public void anUnquotedInstructionOfSeveralWordsSurvivesWhole() {
        assertThat(timer.execute(new String[] {"create", "--every", "2m",
                                               "check", "whether", "the", "build", "finished"}))
                .isZero();

        assertThat(TimerRegistry.active()).hasSize(1);
        assertThat(TimerRegistry.active().get(0).instruction())
                .isEqualTo("check whether the build finished");
        assertThat(TimerRegistry.active().get(0).interval()).isEqualTo(Duration.ofMinutes(2));
    }

    /** The catalog promises {@code --flag=value} works everywhere, so it has to work here. */
    @Test
    public void theInlineOptionFormWorksToo() {
        assertThat(timer.execute(new String[] {"create", "--every=90s", "--limit=3", "look again"}))
                .isZero();

        AgentTimer set = TimerRegistry.active().get(0);
        assertThat(set.interval()).isEqualTo(Duration.ofSeconds(90));
        assertThat(set.limit()).isEqualTo(3);
        assertThat(set.remaining()).isEqualTo("3");
    }

    @Test
    public void acallWithNoIntervalIsRefusedAndLeavesNothingBehind() {
        assertThat(timer.execute(new String[] {"create", "check the build"})).isEqualTo(1);
        assertThat(TimerRegistry.active()).isEmpty();
    }

    @Test
    public void acallWithNothingToBeRemindedOfIsRefused() {
        assertThat(timer.execute(new String[] {"create", "--every", "2m"})).isEqualTo(1);
        assertThat(TimerRegistry.active()).isEmpty();
    }

    @Test
    public void anIntervalThatIsNotOneIsRefusedRatherThanGuessedAt() {
        assertThat(timer.execute(new String[] {"create", "--every", "soon", "check the build"}))
                .isEqualTo(1);
        assertThat(timer.execute(new String[] {"create", "--every", "1s", "check the build"}))
                .isEqualTo(1);
        assertThat(TimerRegistry.active()).isEmpty();
    }

    @Test
    public void listingWorksWithTimersAndWithoutThem() {
        assertThat(timer.execute(new String[] {"list"})).isZero();
        timer.execute(new String[] {"create", "--every", "2m", "check the build"});
        assertThat(timer.execute(new String[] {"list"})).isZero();
        assertThat(timer.execute(new String[0])).isZero();
    }

    @Test
    public void cancellingNamesWhatItCouldNotFind() {
        timer.execute(new String[] {"create", "--every", "2m", "check the build"});
        String id = TimerRegistry.active().get(0).id();

        assertThat(timer.execute(new String[] {"cancel", "t999"})).isEqualTo(1);
        assertThat(TimerRegistry.active()).hasSize(1);

        assertThat(timer.execute(new String[] {"cancel", id})).isZero();
        assertThat(TimerRegistry.active()).isEmpty();

        assertThat(timer.execute(new String[] {"cancel"})).isEqualTo(1);
    }

    @Test
    public void cancellingThemAllWorksWhetherOrNotThereAreAny() {
        assertThat(timer.execute(new String[] {"cancel", "all"})).isZero();
        timer.execute(new String[] {"create", "--every", "2m", "one"});
        timer.execute(new String[] {"create", "--every", "2m", "two"});
        assertThat(timer.execute(new String[] {"cancel", "all"})).isZero();
        assertThat(TimerRegistry.active()).isEmpty();
    }

    /** Waiting for nothing must return at once rather than block the run for ten minutes. */
    @Test
    public void waitingWithNothingSetReturnsImmediately() {
        long started = System.currentTimeMillis();
        assertThat(timer.execute(new String[] {"wait"})).isZero();
        assertThat(System.currentTimeMillis() - started).isLessThan(2_000L);
    }

    /** Something already owed is not something to wait for; the step that follows reports it. */
    @Test
    public void waitingWhenSomethingIsAlreadyDueReturnsAtOnce() {
        TimerRegistry.create("check the build", Duration.ofMinutes(1), AgentTimer.UNLIMITED,
                             Instant.now().minusSeconds(120));

        long started = System.currentTimeMillis();
        assertThat(timer.execute(new String[] {"wait"})).isZero();
        assertThat(System.currentTimeMillis() - started).isLessThan(2_000L);
    }

    /**
     * A wait that ends before the timer does says so rather than pretending one fired, and a wait
     * asked for in something that is not seconds is refused rather than run for the default.
     */
    @Test
    public void awaitIsBoundedByWhatWasAskedForAndRefusesWhatIsNotSeconds() {
        timer.execute(new String[] {"create", "--every", "5m", "check the build"});

        long started = System.currentTimeMillis();
        assertThat(timer.execute(new String[] {"wait", "1"})).isZero();
        long elapsed = System.currentTimeMillis() - started;
        assertThat(elapsed).isBetween(900L, 5_000L);

        assertThat(timer.execute(new String[] {"wait", "soon"})).isEqualTo(1);
    }

    /** A timer already owed is listed as due now rather than as due in a negative amount of time. */
    @Test
    public void listingSaysWhenEachIsNextDueIncludingOneThatAlreadyIs() {
        TimerRegistry.create("check the build", Duration.ofMinutes(1), 3,
                             Instant.now().minusSeconds(120));
        TimerRegistry.create("and again later", Duration.ofMinutes(5), AgentTimer.UNLIMITED,
                             Instant.now());

        assertThat(timer.execute(new String[] {"list"})).isZero();
        assertThat(TimerRegistry.active()).hasSize(2);
    }

    @Test
    public void alimitThatIsNotANumberIsRefused() {
        assertThat(timer.execute(new String[] {"create", "--every", "2m", "--limit", "lots",
                                               "check the build"})).isEqualTo(1);
        assertThat(TimerRegistry.active()).isEmpty();
    }

    /** Past the ceiling the command has to say what to do about it, not just fail. */
    @Test
    public void oneTooManyIsRefusedWithSomethingToDoAboutIt() {
        for (int i = 0; i < TimerRegistry.MAX_PER_SCOPE; i++) {
            assertThat(timer.execute(new String[] {"create", "--every", "2m", "check " + i}))
                    .isZero();
        }
        assertThat(timer.execute(new String[] {"create", "--every", "2m", "one too many"}))
                .isEqualTo(1);
        assertThat(TimerRegistry.active()).hasSize(TimerRegistry.MAX_PER_SCOPE);
    }

    @Test
    public void asubcommandThatIsNotOneIsRefusedWithTheUsage() {
        assertThat(timer.execute(new String[] {"explode"})).isEqualTo(1);
        assertThat(timer.getUsage()).contains("timer create").contains("timer cancel");
    }
}
