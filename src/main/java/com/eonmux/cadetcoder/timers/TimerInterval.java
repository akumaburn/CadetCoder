package com.eonmux.cadetcoder.timers;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How often a timer fires, read from what a model wrote and written back the same way.
 *
 * <h2>Why an interval is parsed rather than counted in seconds</h2>
 *
 * <p>The timers here are created by a model, in the middle of a step, out of a sentence a person
 * asked for. "Every two minutes" reaches the command as {@code 2m} far more often than as
 * {@code 120}, and a parser that took only the number would read that as two seconds -- a timer
 * sixty times faster than the one anybody asked for, firing into every turn of the run. A bare
 * number is still accepted, and means seconds, because that is what a bare number means everywhere
 * else in this tool.</p>
 *
 * <h2>Why there are bounds</h2>
 *
 * <p>A timer exists to interrupt a long job occasionally. One set to a second fires on every turn,
 * which is not a check-in but a second conversation running alongside the first, and it costs a
 * share of every prompt for as long as it lives. One set to a week will not fire inside any session
 * that will ever exist, so accepting it silently is agreeing to do nothing. Both are refused by
 * name, with the bound in the message, so the model can correct the value rather than guess at
 * which end it was wrong.</p>
 */
public final class TimerInterval {

    /** Fast enough to be a check-in, slow enough not to be a second conversation. */
    public static final Duration SHORTEST = Duration.ofSeconds(5);

    /** Longer than any session this tool is part of, so a timer beyond it would never fire. */
    public static final Duration LONGEST = Duration.ofHours(24);

    /** One {@code <number><unit>} group; the unit is optional only on a lone number. */
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([smhd]?)");

    private static final Pattern WELL_FORMED = Pattern.compile("(?:\\d+\\s*[smhd]?\\s*)+");

    private TimerInterval() {
    }

    /**
     * The interval {@code text} names.
     *
     * @param text a duration such as {@code 30s}, {@code 5m}, {@code 1h30m}, or a bare number of
     *             seconds
     * @return the interval
     * @throws IllegalArgumentException when it names none, or one outside {@link #SHORTEST} to
     *                                  {@link #LONGEST}
     */
    public static Duration parse(String text) {
        String cleaned = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty() || !WELL_FORMED.matcher(cleaned).matches()) {
            throw new IllegalArgumentException(
                    "'" + text + "' is not an interval: write it as 30s, 5m, 2h, 1h30m, or a plain "
                    + "number of seconds");
        }

        Duration total  = Duration.ZERO;
        Matcher  parts  = PART.matcher(cleaned);
        while (parts.find()) {
            total = total.plus(unit(parts.group(2)).multipliedBy(Long.parseLong(parts.group(1))));
        }
        return checked(total, text);
    }

    /** The interval one {@code <number><unit>} group counts in; a missing unit means seconds. */
    private static Duration unit(String suffix) {
        switch (suffix) {
            case "m":  return Duration.ofMinutes(1);
            case "h":  return Duration.ofHours(1);
            case "d":  return Duration.ofDays(1);
            case "s":
            default:   return Duration.ofSeconds(1);
        }
    }

    /**
     * @param interval what was read
     * @param original what it was read from, so the complaint quotes what was written
     * @return the interval, when it is one a timer can be given
     */
    private static Duration checked(Duration interval, String original) {
        if (interval.compareTo(SHORTEST) < 0) {
            throw new IllegalArgumentException(
                    "'" + original + "' is too short for a timer: the shortest is "
                    + render(SHORTEST) + ", below which it would fire on every turn");
        }
        if (interval.compareTo(LONGEST) > 0) {
            throw new IllegalArgumentException(
                    "'" + original + "' is longer than the longest timer (" + render(LONGEST)
                    + "), and would never fire");
        }
        return interval;
    }

    /**
     * An interval as a person would write it.
     *
     * @param interval what to describe
     * @return the shortest exact spelling, such as {@code 90s}, {@code 5m} or {@code 1h30m}
     */
    public static String render(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return "0s";
        }
        long          seconds = interval.getSeconds();
        StringBuilder text    = new StringBuilder();
        appendPart(text, seconds / 3600, "h");
        appendPart(text, (seconds % 3600) / 60, "m");
        appendPart(text, seconds % 60, "s");
        return text.toString();
    }

    private static void appendPart(StringBuilder text, long amount, String unit) {
        if (amount > 0) {
            text.append(amount).append(unit);
        }
    }
}
