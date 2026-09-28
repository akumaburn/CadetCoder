package com.eonmux.cadetcoder.ai.metrics;

import java.util.Optional;

/**
 * Where a backend leaves the token usage a provider reported, for the request in flight on this
 * thread.
 *
 * <h2>Why a hand-off rather than a return value</h2>
 *
 * <p>{@code LLMBackend.complete} returns the completion text, and the usage block arrives in the same
 * response. Widening that return type would change one interface, eight backends, three client
 * wrappers and every stub in the suite in order to carry a figure only one caller ever reads. This
 * carries it instead, between two points a few frames apart on one stack: the backend that parsed the
 * body and the manager that started the request.</p>
 *
 * <h2>Why thread-local</h2>
 *
 * <p>Workers issue completions concurrently. A single shared slot would let one worker's usage be
 * attributed to another's request — silently, and in a way that looks like a plausible number rather
 * than an error. Each thread has its own slot, and the request that set it is always the request that
 * reads it, because the provider call is synchronous.</p>
 *
 * <p>Readers consume: {@link #take()} clears the slot, so a backend that reports nothing on the next
 * request cannot have the previous one's figures read a second time.</p>
 *
 * <h2>Why the slot accumulates rather than being overwritten</h2>
 *
 * <p>One request is often several attempts. A reply that arrived truncated, or empty, or wearing a
 * legacy {@code "Error: ..."} sentinel was still generated and still billed, and the attempt that
 * finally answered is billed on top of it. Overwriting meant the earlier attempts contributed
 * nothing to what the session reports having spent -- so the requests that cost the most were the
 * ones reported most wrongly. The slot is emptied once per request by {@link #clear()}, and every
 * attempt inside it adds to what is there.</p>
 */
public final class ReportedUsage {

    private static final ThreadLocal<TokenUsage> LAST = new ThreadLocal<>();

    private ReportedUsage() {
    }

    /**
     * Adds what the provider reported for the attempt this thread just made.
     *
     * @param usage what this attempt reported; {@code null} when the provider reported none, which
     *              says nothing about what an earlier attempt at the same request reported and so
     *              leaves it alone
     */
    public static void report(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        TokenUsage earlier = LAST.get();
        LAST.set(earlier == null ? usage : earlier.plus(usage));
    }

    /**
     * Takes and clears whatever the last backend call on this thread reported.
     *
     * @return the reported usage, or empty when the provider reported none
     */
    public static Optional<TokenUsage> take() {
        TokenUsage usage = LAST.get();
        LAST.remove();
        return Optional.ofNullable(usage);
    }

    /**
     * Discards any usage left over from an earlier request, before starting a new one.
     *
     * <p>Called once per request rather than once per attempt: the attempts of one request are all
     * billed to it, and clearing between them is what discarded the ones that were paid for and
     * rejected.</p>
     */
    public static void clear() {
        LAST.remove();
    }
}
