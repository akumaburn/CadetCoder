package com.eonmux.cadetcoder.timers;

import java.time.Duration;
import java.time.Instant;

/**
 * One moment at which a timer came due, as the model is told about it.
 *
 * <h2>Why this is not simply the timer</h2>
 *
 * <p>A timer is a standing thing whose count keeps moving; a firing is one event that happened at
 * one time with one number on it. Handing the live timer to whatever renders the notice would mean
 * the text said whatever the count had reached by the moment it was rendered, which is not
 * necessarily the firing being reported -- and in a run where two firings are owed at once, the two
 * notices would both claim the later number.</p>
 *
 * @param timerId     which timer came due
 * @param instruction what it is reminding the model to do
 * @param nonce       how many times this timer has now fired, this firing included
 * @param remaining   how many firings it has left, or {@code unlimited}
 * @param interval    how long between its firings
 * @param firedAt     when it came due
 */
public record TimerFiring(String timerId, String instruction, int nonce, String remaining,
                          Duration interval, Instant firedAt) {

    public TimerFiring {
        if (timerId == null || timerId.isBlank()) {
            throw new IllegalArgumentException("a firing has to say which timer fired");
        }
        if (nonce < 1) {
            throw new IllegalArgumentException("a firing is at least the first one");
        }
        if (firedAt == null) {
            throw new IllegalArgumentException("a firing has to say when it happened");
        }
    }

    /**
     * The firing of a timer that has just been advanced.
     *
     * @param timer the timer as it stands AFTER {@link AgentTimer#fired}, so its count is this
     *              firing's number
     * @param at    when it came due
     * @return the firing to report
     */
    public static TimerFiring of(AgentTimer timer, Instant at) {
        return new TimerFiring(timer.id(), timer.instruction(), timer.fireCount(),
                               timer.remaining(), timer.interval(), at);
    }
}
