package com.eonmux.cadetcoder.logging;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The session log entries that have been recorded but not yet written.
 *
 * <h2>Why a full queue is not silence</h2>
 *
 * <p>The queue is bounded so that a long burst of output cannot grow it until the process runs out
 * of memory, and everything a command prints comes through it. The offer that did not fit used to
 * return {@code false} to nobody, so the entries beyond the bound were dropped without a word. What
 * was left looked complete -- nothing in the file said anything was missing -- and a session log is
 * read to work out what happened, so a reader would take the absence of a line as the absence of
 * the event.</p>
 *
 * <p>What did not fit is counted here and handed to the writer, which records it in the log at the
 * point where the hole is. The entries are still lost: making the thread that logged wait, so that
 * the tool stops in order to keep its diary, would be the worse trade. The loss is not.</p>
 */
final class PendingEntries {

    private final BlockingQueue<SessionLogEntry> queue;
    private final AtomicLong                     notTaken = new AtomicLong();
    private final int                            capacity;

    /**
     * @param capacity how many entries may wait to be written before further ones are lost
     */
    PendingEntries(int capacity) {
        this.capacity = capacity;
        this.queue    = new LinkedBlockingQueue<>(capacity);
    }

    /** Takes an entry if there is room for it, and counts it as lost if there is not. */
    void add(SessionLogEntry entry) {
        if (!queue.offer(entry)) {
            notTaken.incrementAndGet();
        }
    }

    /**
     * The next entry to write, waiting a while for one to arrive.
     *
     * @param timeout how long to wait
     * @param unit    what the timeout is measured in
     * @return the next entry, or {@code null} if none arrived in that time
     * @throws InterruptedException if the wait was interrupted
     */
    SessionLogEntry next(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /** The next entry if one is already waiting, or {@code null} if none is. */
    SessionLogEntry nextIfWaiting() {
        return queue.poll();
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    int size() {
        return queue.size();
    }

    /**
     * What has been lost since this was last asked, worded for the log.
     *
     * <p>Cleared by the asking, so that one gap is described once. Reported every time round the
     * writer's loop instead, the same sentence would be repeated until the session ended and would
     * itself become most of the log.</p>
     *
     * @return the sentence to record, or empty if nothing has been lost since the last report
     */
    Optional<String> whatWasLost() {
        long lost = notTaken.getAndSet(0);
        if (lost == 0) {
            return Optional.empty();
        }
        String what = lost == 1 ? "1 log entry was not recorded: it arrived"
                                : lost + " log entries were not recorded: they arrived";
        return Optional.of(what + " while " + capacity + " were already waiting to be written");
    }
}
