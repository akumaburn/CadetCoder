package com.eonmux.cadetcoder.timers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * What a firing looks like when the model reads it.
 *
 * <h2>Why the notice says who is speaking</h2>
 *
 * <p>It arrives in the middle of a conversation with a person, in the same place that person's words
 * arrive. Without a marker saying otherwise the model has every reason to read it as something the
 * user just said -- and a user who appeared to interrupt with "check whether the build finished"
 * would be answered, at length, instead of being quietly worked around. The bracketed label is the
 * same device the harness uses for the things it says between turns.</p>
 *
 * <h2>Why it carries the count and the time</h2>
 *
 * <p>These are what make a repeating reminder usable rather than merely repeated. The count is how
 * the model can tell the third check on a build from the first, which is the difference between
 * "still going" and "this has been going far too long"; the time is what lets it work out how long
 * "far too long" actually was. Neither can be recovered from the text of the instruction, and a
 * model asked to keep its own tally across a compacted transcript will not have one.</p>
 */
public final class TimerNotice {

    /** Marks the notice as the tool speaking, not the person. */
    private static final String LABEL = "[timer]";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private TimerNotice() {
    }

    /**
     * Every firing the calling scope is owed right now, rendered.
     *
     * <p>Withdraws the firings, so the same one is never reported twice; see
     * {@link TimerRegistry#takeDue}. Withdrawing is not spending: whoever builds a prompt with this
     * in it must say afterwards whether the prompt was sent, with {@link #delivered()} or
     * {@link #undelivered()}. A prompt that is never sent takes the reminder with it otherwise.</p>
     *
     * @return the notice to append to the next prompt, or an empty string when nothing is due
     */
    public static String dueNow() {
        return render(TimerRegistry.takeDue(Instant.now()));
    }

    /** The prompt built with the last {@link #dueNow()} reached the model. */
    public static void delivered() {
        TimerRegistry.delivered();
    }

    /**
     * The prompt built with the last {@link #dueNow()} was never sent.
     *
     * @return how many firings were put back
     */
    public static int undelivered() {
        return TimerRegistry.returnUndelivered();
    }

    /**
     * @param firings what came due, in the order they should be read
     * @return the notice, or an empty string when there were none
     */
    public static String render(List<TimerFiring> firings) {
        if (firings == null || firings.isEmpty()) {
            return "";
        }
        StringBuilder notice = new StringBuilder();
        for (TimerFiring firing : firings) {
            notice.append(System.lineSeparator()).append(System.lineSeparator())
                  .append(one(firing));
        }
        notice.append(System.lineSeparator())
              .append("Deal with each of these as part of your next step -- they do not replace the"
                      + " work you are doing. Cancel one with `timer cancel <id>` once it has served"
                      + " its purpose.");
        return notice.toString();
    }

    /** One firing: what fired, when, how often, and what it asked for. */
    private static String one(TimerFiring firing) {
        return LABEL + " " + firing.timerId() + " fired at " + at(firing.firedAt())
               + " -- firing number " + firing.nonce() + ", " + firing.remaining()
               + " left, every " + TimerInterval.render(firing.interval()) + "."
               + System.lineSeparator()
               + "You set it to: " + firing.instruction();
    }

    /**
     * @param moment when the firing came due
     * @return the moment in this machine's own time zone, to the second
     */
    static String at(Instant moment) {
        return WHEN.format(ZonedDateTime.ofInstant(moment.truncatedTo(ChronoUnit.SECONDS),
                                                   ZoneId.systemDefault()));
    }
}
