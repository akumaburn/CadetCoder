package com.eonmux.cadetcoder.jobs;

import com.eonmux.cadetcoder.security.SecretRedactor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What a background job has printed, kept in bounded memory and readable in slices.
 *
 * <h2>Why the oldest lines go first</h2>
 *
 * <p>A job runs for as long as it likes and prints as much as it likes. A build that loops, a server
 * logging a request a second, a test suite in a watch loop: each of those will fill any buffer given
 * long enough, so the only question is what happens when it is full. Dropping the newest would mean
 * the buffer stops describing the job the moment it matters most -- what it printed just before it
 * failed is the part anybody reads. So the oldest go, and how many went is kept, because output that
 * silently lost its first half would have the reader drawing conclusions from a transcript that does
 * not say it is partial.</p>
 *
 * <h2>Why lines are numbered from the start of the job</h2>
 *
 * <p>Reading is incremental: "what has this printed since I last looked" is the question an agent
 * asks on every check-in, and asking it must not cost the whole transcript again. A cursor is an
 * absolute line number rather than a position in the buffer, so it stays meaningful after the front
 * of the buffer has been dropped -- a reader that has fallen behind is told how much it missed
 * instead of being handed the wrong lines.</p>
 *
 * <h2>Why a credential is taken out as the line arrives</h2>
 *
 * <p>Everything {@code bash} prints is redacted in one place, {@code ui/CapturedRun}, because the
 * step that ran it collects its output there. A job has no such step: its output is read by
 * {@code job output}, quoted into the next prompt by {@code JobNotice}, and written into the
 * harness transcript, and each of those would need the rule repeated. A deploy script that echoes
 * an {@code Authorization: Bearer} header on the way to failing would otherwise have it quoted into
 * every prompt for the rest of the run. So it goes here, where every reader passes through, and the
 * buffer never holds what must not leave it.</p>
 */
public final class JobOutput {

    /**
     * How much of one job's output is kept, in characters.
     *
     * <p>Far more than {@code bash} keeps for a single command, because that output is read once,
     * in full, by the step that asked for it. This is read in slices over the life of a job that
     * nobody is watching, so the useful window is the last few thousand lines rather than the
     * first.</p>
     */
    public static final int MAX_CHARS = 200_000;

    private final Deque<String> lines = new ArrayDeque<>();

    /** How many characters the kept lines occupy, separators included. */
    private int chars;

    /** How many lines have been dropped off the front to stay inside {@link #MAX_CHARS}. */
    private long dropped;

    /** How many lines the job has printed in total, dropped ones included. */
    private long produced;

    /**
     * Records one line, with anything credential-shaped in it masked.
     *
     * @param line what the job printed, without its terminator
     */
    public synchronized void append(String line) {
        String text = line == null ? "" : SecretRedactor.redact(line);
        lines.addLast(text);
        chars += text.length() + 1;
        produced++;
        while (chars > MAX_CHARS && lines.size() > 1) {
            String oldest = lines.removeFirst();
            chars -= oldest.length() + 1;
            dropped++;
        }
    }

    /** @return how many lines the job has printed, including any since dropped */
    public synchronized long produced() {
        return produced;
    }

    /** @return the line number of the oldest line still kept */
    public synchronized long oldestKept() {
        return dropped;
    }

    /**
     * The lines from {@code cursor} onwards.
     *
     * @param cursor the absolute line number to read from; below {@link #oldestKept()} it is raised
     *               to it, and what was missed is reported by {@link #missedBefore(long)}
     * @param limit  the most lines to return, counted from the END so the newest are never the ones
     *               left out; zero or less means all of them
     * @return the lines, oldest first
     */
    public synchronized List<String> since(long cursor, int limit) {
        long from = Math.max(cursor, dropped);
        if (from >= produced) {
            return List.of();
        }
        int skip  = (int) (from - dropped);
        int count = (int) (produced - from);
        if (limit > 0 && count > limit) {
            skip += count - limit;
            count = limit;
        }
        List<String> slice = new ArrayList<>(count);
        int          index = 0;
        for (String line : lines) {
            if (index++ < skip) {
                continue;
            }
            slice.add(line);
            if (slice.size() == count) {
                break;
            }
        }
        return slice;
    }

    /**
     * How many lines a reader at {@code cursor} will never see, because they were dropped.
     *
     * @param cursor the absolute line number the reader had reached
     * @return the number of lines lost between there and the oldest line still kept
     */
    public synchronized long missedBefore(long cursor) {
        return Math.max(0, dropped - Math.max(0, cursor));
    }

    /** @return how many lines are waiting for a reader at {@code cursor} */
    public synchronized long pendingFrom(long cursor) {
        return Math.max(0, produced - Math.max(cursor, dropped));
    }
}
