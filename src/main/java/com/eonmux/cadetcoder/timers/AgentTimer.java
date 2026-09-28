package com.eonmux.cadetcoder.timers;

import java.time.Duration;
import java.time.Instant;

/**
 * A standing instruction the model left itself, and when it is next owed.
 *
 * <h2>Why a timer carries an instruction rather than only a period</h2>
 *
 * <p>What fires is not an alarm, it is a note: the model wrote it several steps ago and will read it
 * in the middle of doing something else, with no memory of why it set it. A firing that said only
 * "timer t1 fired" would cost a turn spent working out what t1 was for. The instruction is the whole
 * value of the mechanism, so it is required, not optional.</p>
 *
 * <h2>Why the next firing is measured from now and not from the last one</h2>
 *
 * <p>A run can spend twenty minutes inside one command. Advancing by the interval from the previous
 * due time would then owe ten firings at once, and they would all arrive in the same prompt saying
 * the same thing -- ten copies of one check-in, nine of them noise, and the count in each of them
 * wrong about what actually happened. Missed firings are dropped on purpose: the point is to be
 * reminded now, not to be told how long the reminder was overdue.</p>
 *
 * @param id          how the timer is addressed, short enough to type
 * @param instruction what the model wants to be reminded to do
 * @param interval    how long between firings
 * @param limit       how many times it may fire, or {@code 0} for as long as the session lasts
 * @param fireCount   how many times it has fired -- the nonce a firing reports
 * @param createdAt   when it was set
 * @param dueAt       when it next fires
 */
public record AgentTimer(String id, String instruction, Duration interval, int limit,
                         int fireCount, Instant createdAt, Instant dueAt) {

    /** A timer that fires for as long as the session lasts. */
    public static final int UNLIMITED = 0;

    public AgentTimer {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a timer needs an id to be cancelled by");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("a timer needs to say what it is reminding you to do");
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("a timer needs a positive interval");
        }
        if (limit < UNLIMITED) {
            throw new IllegalArgumentException("a timer cannot be limited to fewer than no firings");
        }
        if (createdAt == null || dueAt == null) {
            throw new IllegalArgumentException("a timer needs to know when it was set and when it is"
                                               + " next due");
        }
        instruction = instruction.strip();
    }

    /**
     * A timer that has not yet fired.
     *
     * @param id          how it will be addressed
     * @param instruction what it reminds the model to do
     * @param interval    how long between firings, and how long until the first one
     * @param limit       how many firings it gets, or {@link #UNLIMITED}
     * @param now         when it was set
     * @return the timer
     */
    public static AgentTimer starting(String id, String instruction, Duration interval, int limit,
                                      Instant now) {
        return new AgentTimer(id, instruction, interval, limit, 0, now, now.plus(interval));
    }

    /**
     * @param now the moment being asked about
     * @return whether this timer is owed a firing
     */
    public boolean isDue(Instant now) {
        return !now.isBefore(dueAt);
    }

    /**
     * This timer, one firing later.
     *
     * @param now when it fired
     * @return the timer as it stands afterwards
     */
    public AgentTimer fired(Instant now) {
        return new AgentTimer(id, instruction, interval, limit, fireCount + 1, createdAt,
                              now.plus(interval));
    }

    /** @return whether it has fired every time it was allowed to and should now be forgotten */
    public boolean isSpent() {
        return limit != UNLIMITED && fireCount >= limit;
    }

    /** @return how many firings it has left, spelled for a reader rather than counted */
    public String remaining() {
        return limit == UNLIMITED ? "unlimited" : String.valueOf(Math.max(0, limit - fireCount));
    }
}
