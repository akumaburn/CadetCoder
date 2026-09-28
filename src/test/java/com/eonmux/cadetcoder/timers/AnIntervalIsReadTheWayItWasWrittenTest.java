package com.eonmux.cadetcoder.timers;

import org.junit.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How often a timer fires, read from what was written.
 *
 * <p><b>The defect</b>: these intervals are written by a model, in the middle of a step, out of a
 * sentence somebody said. "Every two minutes" reaches the command as {@code 2m}, and a parser that
 * read only the number would take that for two seconds -- a timer sixty times faster than the one
 * anybody asked for, firing into every turn of the run and costing a share of every prompt. Nothing
 * downstream could notice: a two-second timer is a perfectly valid timer.</p>
 *
 * <p><b>What is locked here</b>: that every unit means what it says; that a bare number still means
 * seconds, as it does everywhere else in this tool; that compound intervals add up; that an interval
 * fast enough to fire on every turn and one too slow ever to fire are both refused by name rather
 * than accepted and quietly ignored; and that an interval is written back in the form it was read
 * in, since that is what the model is shown when it asks what is set.</p>
 */
public class AnIntervalIsReadTheWayItWasWrittenTest {

    @Test
    public void eachUnitMeansWhatItSays() {
        assertThat(TimerInterval.parse("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(TimerInterval.parse("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(TimerInterval.parse("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(TimerInterval.parse("1d")).isEqualTo(Duration.ofDays(1));
    }

    /** A minute written as a minute is the defect this exists for; a bare number is the old way. */
    @Test
    public void abareNumberIsStillSeconds() {
        assertThat(TimerInterval.parse("90")).isEqualTo(Duration.ofSeconds(90));
        assertThat(TimerInterval.parse("10")).isEqualTo(Duration.ofSeconds(10));
        assertThat(TimerInterval.parse("10m")).isNotEqualTo(TimerInterval.parse("10"));
    }

    @Test
    public void compoundIntervalsAddUp() {
        assertThat(TimerInterval.parse("1h30m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(TimerInterval.parse("2m 30s")).isEqualTo(Duration.ofSeconds(150));
    }

    @Test
    public void caseAndSurroundingSpaceDoNotMatter() {
        assertThat(TimerInterval.parse("  5M  ")).isEqualTo(Duration.ofMinutes(5));
    }

    /**
     * An interval below the floor would fire on every turn, which is not a check-in but a second
     * conversation running alongside the first.
     */
    @Test
    public void somethingFastEnoughToFireEveryTurnIsRefused() {
        assertThatThrownBy(() -> TimerInterval.parse("1s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
        assertThatThrownBy(() -> TimerInterval.parse("0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Accepting an interval that can never fire is agreeing to do nothing, silently. */
    @Test
    public void somethingTooSlowEverToFireIsRefused() {
        assertThatThrownBy(() -> TimerInterval.parse("48h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never fire");
    }

    @Test
    public void whatIsNotAnIntervalSaysSoAndSaysWhatOneLooksLike() {
        assertThatThrownBy(() -> TimerInterval.parse("soon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30s");
        assertThatThrownBy(() -> TimerInterval.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimerInterval.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** What is rendered is what the model is shown, so it has to read back as what was written. */
    @Test
    public void anIntervalIsWrittenBackInTheFormItWasReadIn() {
        assertThat(TimerInterval.render(Duration.ofSeconds(30))).isEqualTo("30s");
        assertThat(TimerInterval.render(Duration.ofMinutes(5))).isEqualTo("5m");
        assertThat(TimerInterval.render(Duration.ofMinutes(90))).isEqualTo("1h30m");
        assertThat(TimerInterval.render(Duration.ZERO)).isEqualTo("0s");
        assertThat(TimerInterval.parse(TimerInterval.render(Duration.ofSeconds(150))))
                .isEqualTo(Duration.ofSeconds(150));
    }
}
