package com.eonmux.cadetcoder.ai.metrics;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Measures every LLM request: how much of the prompt was reusable, how much was new, how long the
 * request took, and how fast the session is producing output.
 *
 * <p>Instrumentation lives here rather than in each command because
 * {@code AIManager.complete(PromptData, Map)} is the single point every completion funnels through.
 * One hook there measures the chat loop, the agent loop and every one-shot command identically.</p>
 *
 * <h2>How the cached/new split is measured</h2>
 *
 * <p>Prompt caching serves a shared leading prefix. The recorder keeps the previous request's full
 * prompt and reports the longest common prefix as cached and the remainder as new. That measures the
 * property the caller actually controls -- whether the prompt is built append-only -- and it is what
 * regresses if someone reintroduces a sliding window or injects a timestamp into the preamble. When
 * a provider reports real usage, {@link #completeWithUsage} records that instead and the figures stop
 * being estimates.</p>
 *
 * <p>Thread-safe. A request may be issued from an agent worker thread while the TUI reads the
 * in-flight elapsed time from the render thread.</p>
 */
public final class RequestMetricsRecorder {

    private static final RequestMetricsRecorder INSTANCE = new RequestMetricsRecorder();

    /** Output throughput: how fast the session is generating. */
    private final TokenRateMeter rateMeter;

    /**
     * Input throughput: how fast the session is feeding prompt tokens to the provider.
     *
     * <p>Reported alongside generation because they answer different questions and move
     * independently. A run whose generation rate is low because every turn ships a large prompt
     * looks identical to one that is simply waiting on a slow model, until the input rate is shown
     * next to it.</p>
     */
    private final TokenRateMeter inputRateMeter;
    private final LongSupplier   clock;

    /**
     * Sentinel for "no request in flight".
     *
     * <p>Deliberately not {@code 0}: zero is a legitimate clock reading, so using it as the sentinel
     * made a request that started at time zero indistinguishable from no request at all, and the
     * elapsed-time counter silently reported nothing.</p>
     */
    private static final long NOT_IN_FLIGHT = Long.MIN_VALUE;

    /** Millisecond timestamp of the in-flight request, or {@link #NOT_IN_FLIGHT} when none is running. */
    private final AtomicLong inFlightSince = new AtomicLong(NOT_IN_FLIGHT);

    /**
     * The previous request's full prompt on THIS thread, used to measure the reusable prefix.
     *
     * <p>Per-thread because a cache prefix is a property of one conversation. Workers run their own
     * agent loops concurrently against the same recorder, and a single shared baseline meant worker
     * A's prompt was compared against whatever worker B had most recently sent — producing a cache
     * figure for a pairing no provider will ever see. Each thread now measures itself.</p>
     *
     * <p>Worker threads are created per run and discarded with it, so nothing stale carries over.</p>
     */
    private final ThreadLocal<String> previousPrompt = ThreadLocal.withInitial(() -> "");

    /**
     * Every request currently in flight, oldest first.
     *
     * <p>A single slot was enough while only one request could be outstanding. Workers made that
     * false: with several running, the last to start overwrote the others, and the first to finish
     * cleared the slot — so the live counter stopped ticking while three requests were still going,
     * which is the one appearance a working session must never have.</p>
     */
    private final java.util.Set<InFlight> inFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private long totalCachedInputTokens;
    private long totalNewInputTokens;

    /**
     * Output tokens across the process.
     *
     * <p>Atomic because the render thread reads it once per frame for the live line while a command
     * thread is completing requests, and the other totals are only ever touched under this object's
     * monitor.</p>
     */
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private long totalDurationMillis;
    private long requestCount;

    /**
     * Whether any completed request's figures had to be estimated.
     *
     * <p>The session totals mix requests from whichever providers were used, and a provider that
     * reports usage may sit alongside one that does not. One estimated request makes the total an
     * estimate, so the {@code ~} is shown if ANY were — claiming precision for a sum that contains a
     * guess would be the worse error of the two.</p>
     */
    private boolean anyEstimated;

    RequestMetricsRecorder() {
        this(new TokenRateMeter(), System::currentTimeMillis);
    }

    /**
     * Creates a recorder with an explicit meter and clock, so the window and timings are testable
     * without sleeping.
     *
     * @param rateMeter the throughput meter to feed
     * @param clock     the millisecond clock
     */
    RequestMetricsRecorder(TokenRateMeter rateMeter, LongSupplier clock) {
        this.rateMeter      = rateMeter;
        this.inputRateMeter = new TokenRateMeter(rateMeter.windowMillis(), clock);
        this.clock          = clock;
    }

    /** @return the shared recorder */
    public static RequestMetricsRecorder getInstance() {
        return INSTANCE;
    }

    /** A request that has started but not yet completed. */
    public static final class InFlight {
        private final long   startedAtMillis;
        private final String prompt;
        private final int    cachedInputTokens;
        private final int    newInputTokens;

        private InFlight(long startedAtMillis, String prompt, int cachedInputTokens, int newInputTokens) {
            this.startedAtMillis   = startedAtMillis;
            this.prompt            = prompt;
            this.cachedInputTokens = cachedInputTokens;
            this.newInputTokens    = newInputTokens;
        }

        /** @return the estimated input tokens a provider could serve from cache */
        public int cachedInputTokens() {
            return cachedInputTokens;
        }

        /** @return the estimated input tokens that differ from the previous request */
        public int newInputTokens() {
            return newInputTokens;
        }
    }

    /**
     * Marks the start of a request and measures its prompt against the previous one.
     *
     * @param systemPrompt the system prompt (may be {@code null})
     * @param userPrompt   the user prompt (may be {@code null})
     * @return a handle to pass to {@link #complete} or {@link #completeWithUsage}
     */
    public synchronized InFlight begin(String systemPrompt, String userPrompt) {
        String prompt = (systemPrompt == null ? "" : systemPrompt)
                + "\n"
                + (userPrompt == null ? "" : userPrompt);

        int sharedChars = TokenEstimator.commonPrefixLength(previousPrompt.get(), prompt);
        int cached      = TokenEstimator.estimateFromLength(sharedChars);
        int total       = TokenEstimator.estimate(prompt);
        int fresh       = Math.max(0, total - cached);

        long     now      = clock.getAsLong();
        InFlight started  = new InFlight(now, prompt, cached, fresh);
        inFlight.add(started);
        inFlightSince.set(oldestStart(now));
        return started;
    }

    /**
     * Records a completed request whose token counts must be estimated.
     *
     * @param inFlight the handle from {@link #begin}
     * @param response the model's response text (may be {@code null})
     * @return the metrics for this request
     */
    public RequestMetrics complete(InFlight inFlight, String response) {
        return record(inFlight, inFlight.cachedInputTokens, inFlight.newInputTokens,
                TokenEstimator.estimate(response), true);
    }

    /**
     * Records a completed request using token counts the provider reported.
     *
     * @param inFlight          the handle from {@link #begin}
     * @param cachedInputTokens provider-reported cached input tokens
     * @param newInputTokens    provider-reported uncached input tokens
     * @param outputTokens      provider-reported output tokens
     * @return the metrics for this request
     */
    public RequestMetrics completeWithUsage(InFlight inFlight, int cachedInputTokens,
                                            int newInputTokens, int outputTokens) {
        return record(inFlight, cachedInputTokens, newInputTokens, outputTokens, false);
    }

    /**
     * Abandons an in-flight request that failed, so a failed call does not leave the elapsed-time
     * counter running forever.
     *
     * @param inFlight the handle from {@link #begin}; {@code null} is tolerated
     */
    public void abandon(InFlight handle) {
        if (handle != null) {
            inFlight.remove(handle);
        }
        inFlightSince.set(oldestStart(clock.getAsLong()));
    }

    private RequestMetrics record(InFlight handle, int cachedInput, int newInput,
                                  int output, boolean estimated) {
        long duration = Math.max(0, clock.getAsLong() - handle.startedAtMillis);
        inFlight.remove(handle);
        inFlightSince.set(oldestStart(clock.getAsLong()));

        // The prompt just sent becomes the baseline the NEXT request ON THIS THREAD is compared
        // against; another worker's prompt is a different conversation and would not share a prefix.
        previousPrompt.set(handle.prompt);

        rateMeter.record(output);
        inputRateMeter.record(cachedInput + (long) newInput);
        return accumulate(cachedInput, newInput, output, duration, estimated);
    }

    /** Folds one completed request into the session totals. */
    private synchronized RequestMetrics accumulate(int cachedInput, int newInput, int output,
                                                   long duration, boolean estimated) {

        totalCachedInputTokens += cachedInput;
        totalNewInputTokens    += newInput;
        totalOutputTokens.addAndGet(output);
        totalDurationMillis    += duration;
        requestCount++;
        anyEstimated |= estimated;

        return new RequestMetrics(cachedInput, newInput, output, duration, estimated);
    }

    /**
     * @param now the current clock reading
     * @return when the oldest in-flight request started, or {@link #NOT_IN_FLIGHT} when none are
     */
    private long oldestStart(long now) {
        long oldest = Long.MAX_VALUE;
        for (InFlight handle : inFlight) {
            oldest = Math.min(oldest, handle.startedAtMillis);
        }
        return oldest == Long.MAX_VALUE ? NOT_IN_FLIGHT : Math.min(oldest, now);
    }

    /** A live view of the request currently in flight. */
    public static final class InFlightStatus {
        private final long    elapsedMillis;
        private final int     cachedInputTokens;
        private final int     newInputTokens;
        private final long    totalOutputTokens;
        private final boolean outputEstimated;
        private final int     concurrentRequests;

        private InFlightStatus(long elapsedMillis, int cachedInputTokens, int newInputTokens,
                               long totalOutputTokens, boolean outputEstimated,
                               int concurrentRequests) {
            this.elapsedMillis      = elapsedMillis;
            this.cachedInputTokens  = cachedInputTokens;
            this.newInputTokens     = newInputTokens;
            this.totalOutputTokens  = totalOutputTokens;
            this.outputEstimated    = outputEstimated;
            this.concurrentRequests = concurrentRequests;
        }

        /** @return how many requests are outstanding right now, across the shell and any workers */
        public int concurrentRequests() {
            return concurrentRequests;
        }

        /** @return output tokens generated across the process so far */
        public long totalOutputTokens() {
            return totalOutputTokens;
        }

        public long elapsedMillis() {
            return elapsedMillis;
        }

        public int cachedInputTokens() {
            return cachedInputTokens;
        }

        public int newInputTokens() {
            return newInputTokens;
        }

        public int inputTokens() {
            return cachedInputTokens + newInputTokens;
        }

        /**
         * Renders the live counter: elapsed time plus what was sent.
         *
         * <p>Only the INPUT side is available mid-request. Responses are not streamed, so output
         * tokens do not exist until the request completes -- showing a running output count would be
         * a fiction. Example: {@code 4.2s · ~5,554 in (96% cached)}.</p>
         *
         * <p>The input figure is always marked estimated: a provider reports its counts WITH the
         * response, so while a request is in flight there is nothing but the estimate, whatever the
         * completed requests behind it were measured by.</p>
         *
         * @return the formatted counter
         */
        public String render() {
            StringBuilder text = new StringBuilder(RequestMetrics.formatDuration(elapsedMillis));
            if (concurrentRequests > 1) {
                // The elapsed time is the OLDEST request's, so without this the line reads as one
                // slow call rather than several running together.
                text.append(" \u00d7").append(concurrentRequests);
            }
            int total = inputTokens();
            if (total > 0) {
                text.append(" · ~").append(RequestMetrics.formatCompact(total)).append(" in");
                text.append(" (").append(Math.round(100.0 * cachedInputTokens / total))
                    .append("% cached)");
            }
            // The output side is a PROCESS TOTAL, and labelled as one. This request's output does
            // not exist yet -- responses are not streamed -- and a zero here would be a claim about
            // the request in flight that cannot be made, so the clause is omitted until something
            // has actually been generated.
            long out = totalOutputTokens;
            if (out > 0) {
                text.append(" · total ").append(outputEstimated ? "~" : "")
                    .append(RequestMetrics.formatCompact(out)).append(" out");
            }
            return text.toString();
        }
    }

    /**
     * A live view of the in-flight request, for a counter that ticks while waiting.
     *
     * @return the status, or empty when no request is in flight
     */
    public java.util.Optional<InFlightStatus> inFlightStatus() {
        long since = inFlightSince.get();
        if (since == NOT_IN_FLIGHT || inFlight.isEmpty()) {
            return java.util.Optional.empty();
        }
        // Summed across everything outstanding, so a worker run reports what it is really sending
        // rather than whichever request happened to register last.
        int cached = 0;
        int fresh  = 0;
        int count  = 0;
        for (InFlight handle : inFlight) {
            cached += handle.cachedInputTokens;
            fresh  += handle.newInputTokens;
            count++;
        }
        return java.util.Optional.of(new InFlightStatus(
                Math.max(0, clock.getAsLong() - since),
                cached, fresh, totalOutputTokens.get(), anyEstimated, count));
    }

    /**
     * How long the in-flight request has been running.
     *
     * <p>Lets a caller display a counter that ticks while waiting, instead of only reporting the
     * duration once the response has already arrived.</p>
     *
     * @return the elapsed milliseconds, or empty when no request is in flight
     */
    public OptionalLong inFlightElapsedMillis() {
        long since = inFlightSince.get();
        return since == NOT_IN_FLIGHT
                ? OptionalLong.empty()
                : OptionalLong.of(Math.max(0, clock.getAsLong() - since));
    }

    /** @return output throughput over the meter's trailing window, in tokens per second */
    public double windowTokensPerSecond() {
        return rateMeter.tokensPerSecond();
    }

    /**
     * @return input throughput over the trailing window, in prompt tokens per second, aggregated
     *         across the shell and every worker
     */
    public double windowInputTokensPerSecond() {
        return inputRateMeter.tokensPerSecond();
    }

    /** @return the trailing window length in whole seconds, for labelling */
    public long windowSeconds() {
        return Math.max(1, rateMeter.windowMillis() / 1000);
    }

    /**
     * Forgets the previous prompt, so the next request is measured as entirely new.
     *
     * <p>Called when a new run starts: carrying the previous run's prompt forward would report a
     * cache hit that the provider will not actually have.</p>
     */
    public void resetConversation() {
        previousPrompt.remove();
    }

    /** Clears every accumulated figure. Intended for tests and for a fresh session. */
    public synchronized void resetAll() {
        previousPrompt.remove();
        inFlight.clear();
        totalCachedInputTokens = 0;
        totalNewInputTokens    = 0;
        totalOutputTokens.set(0);
        totalDurationMillis    = 0;
        requestCount           = 0;
        anyEstimated           = false;
        inFlightSince.set(NOT_IN_FLIGHT);
        rateMeter.reset();
        inputRateMeter.reset();
    }

    /**
     * Renders the cumulative session totals.
     *
     * @return a one-line summary, or {@code null} when no request has completed yet
     */
    public synchronized String sessionSummary() {
        if (requestCount == 0) {
            return null;
        }
        long   totalInput = totalCachedInputTokens + totalNewInputTokens;
        double hitRatio   = totalInput == 0 ? 0.0 : (double) totalCachedInputTokens / totalInput;
        String mark       = anyEstimated ? "~" : "";
        return String.format("%d request%s · %s%,d in (%.0f%% cached) · %s%,d out · %s",
                requestCount, requestCount == 1 ? "" : "s", mark, totalInput, hitRatio * 100,
                mark, totalOutputTokens.get(), RequestMetrics.formatDuration(totalDurationMillis));
    }

    /** @return how many requests have completed since the last reset */
    public synchronized long requestCount() {
        return requestCount;
    }

    /**
     * @return the wall-clock time of every completed request, summed
     *
     * <p>The same figure {@link #sessionSummary()} renders, offered as a number so a caller that
     * has to reason about it does not have to parse the sentence it appears in.</p>
     */
    public synchronized long totalDurationMillis() {
        return totalDurationMillis;
    }
}
