package com.eonmux.cadetcoder.ai.metrics;

/**
 * What one completed LLM request cost and how long it took.
 *
 * <p>Immutable. Built by {@link RequestMetricsRecorder} at the single point every completion funnels
 * through, so every request is measured the same way regardless of which command issued it.</p>
 *
 * <h2>Cached vs. new input</h2>
 *
 * <p>Prompt caching matches on a shared leading prefix: the part of this request that is
 * byte-identical to the previous one is the part a provider can serve from cache. The split reported
 * here is measured that way -- {@link #cachedInputTokens()} is the longest common prefix with the
 * previous request, {@link #newInputTokens()} is everything after it. That is a property of how the
 * prompt was built, which is exactly what the caller controls and what a regression would show up
 * in, so it is worth reporting even when the provider tells us nothing.</p>
 *
 * <p>When the provider does report real usage, those numbers are used instead and
 * {@link #isEstimated()} is {@code false}. Otherwise every token figure is an estimate from
 * {@link TokenEstimator} and must be presented as one.</p>
 */
public final class RequestMetrics {

    private final int     cachedInputTokens;
    private final int     newInputTokens;
    private final int     outputTokens;
    private final long    durationMillis;
    private final boolean estimated;

    /**
     * @param cachedInputTokens input tokens a provider could serve from a cached prefix
     * @param newInputTokens    input tokens that differ from the previous request
     * @param outputTokens      tokens the model produced
     * @param durationMillis    wall-clock time from request start to response in hand
     * @param estimated         whether the token figures are estimated rather than provider-reported
     */
    public RequestMetrics(int cachedInputTokens, int newInputTokens, int outputTokens,
                          long durationMillis, boolean estimated) {
        this.cachedInputTokens = Math.max(0, cachedInputTokens);
        this.newInputTokens    = Math.max(0, newInputTokens);
        this.outputTokens      = Math.max(0, outputTokens);
        this.durationMillis    = Math.max(0, durationMillis);
        this.estimated         = estimated;
    }

    public int cachedInputTokens() {
        return cachedInputTokens;
    }

    public int newInputTokens() {
        return newInputTokens;
    }

    /** @return total input tokens, cached and new */
    public int inputTokens() {
        return cachedInputTokens + newInputTokens;
    }

    public int outputTokens() {
        return outputTokens;
    }

    public long durationMillis() {
        return durationMillis;
    }

    /** @return {@code true} when the token figures come from {@link TokenEstimator}, not the provider */
    public boolean isEstimated() {
        return estimated;
    }

    /**
     * The share of the input that was reusable.
     *
     * @return a value in {@code [0.0, 1.0]}; {@code 0.0} when there was no input at all
     */
    public double cacheHitRatio() {
        int total = inputTokens();
        return total == 0 ? 0.0 : (double) cachedInputTokens / total;
    }

    /**
     * Generation speed for this request alone.
     *
     * @return output tokens divided by elapsed seconds, or {@code 0.0} when nothing was produced or
     *         no time elapsed
     */
    public double tokensPerSecond() {
        if (outputTokens == 0 || durationMillis == 0) {
            return 0.0;
        }
        return outputTokens / (durationMillis / 1000.0);
    }

    /**
     * Prompt-processing speed for this request alone.
     *
     * <h2>What this is measured against</h2>
     *
     * <p>Both this and {@link #tokensPerSecond()} divide by the WHOLE request duration, because that
     * is the only clock available. A provider that reported prompt-eval and generation time
     * separately (as llama.cpp does locally) would let the two phases be timed apart; over an HTTP
     * completion there is one duration covering both, so neither figure is a decoder speed in
     * isolation. They are still the right pair to compare turns with: a turn that slowed down
     * because its prompt grew moves this number, and one that slowed down because the answer got
     * longer moves the other.</p>
     *
     * @return input tokens divided by elapsed seconds, or {@code 0.0} when there was no input or no
     *         time elapsed
     */
    public double inputTokensPerSecond() {
        int total = inputTokens();
        if (total == 0 || durationMillis == 0) {
            return 0.0;
        }
        return total / (durationMillis / 1000.0);
    }

    /**
     * Renders the one-line summary shown after a request.
     *
     * <p>Example:
     * {@code ~1,024 in (912 cached · 112 new) · ~340 out · 4.2s · 243 in/s · 81 out/s (47 over 5s)}.
     * The leading {@code ~} marks estimated figures so they are never mistaken for billed usage.</p>
     *
     * @param windowRate the session rate over the trailing window, or a negative value to omit it
     * @param windowSeconds the trailing window length in seconds, used only for the label
     * @return the formatted summary line
     */
    public String toSummaryLine(double windowRate, long windowSeconds) {
        String mark = estimated ? "~" : "";
        StringBuilder line = new StringBuilder();
        line.append(mark).append(formatCount(inputTokens())).append(" in (")
            .append(formatCount(cachedInputTokens)).append(" cached · ")
            .append(formatCount(newInputTokens)).append(" new)");
        line.append(" · ").append(mark).append(formatCount(outputTokens)).append(" out");
        line.append(" · ").append(formatDuration(durationMillis));
        if (inputTokensPerSecond() > 0) {
            line.append(" · ").append(formatCount((int) Math.round(inputTokensPerSecond())))
                .append(" in/s");
        }
        if (tokensPerSecond() > 0) {
            line.append(" · ").append(formatCount((int) Math.round(tokensPerSecond()))).append(" out/s");
            // The windowed rate is NESTED inside the per-request rate rather than appended as a
            // sibling. Two bare "N tok/s" terms side by side read as a mistake -- the same unit
            // twice, with nothing saying that the second one measures a different thing.
            if (windowRate >= 0) {
                line.append(" (").append(formatCount((int) Math.round(windowRate)))
                    .append(" over ").append(windowSeconds).append("s)");
            }
        } else if (windowRate >= 0) {
            line.append(" · ").append(formatCount((int) Math.round(windowRate)))
                .append(" out/s over ").append(windowSeconds).append('s');
        }
        return line.toString();
    }

    /**
     * Formats a count with thousands separators.
     *
     * @param count the value
     * @return the formatted count
     */
    /**
     * Formats a count compactly for a line that shares its row with other things.
     *
     * <p>{@code 5.6k} rather than {@code 5,554}. A digit of precision buys nothing in a figure the
     * estimator is only good to roughly a fifth on, and the live line is the first thing to overflow
     * on a narrow terminal.</p>
     *
     * @param count the value
     * @return e.g. {@code "812"}, {@code "5.6k"}, {@code "1.2m"}
     */
    public static String formatCompact(long count) {
        if (count < 1_000) {
            return Long.toString(count);
        }
        if (count < 1_000_000) {
            return trimZero(count / 1_000.0) + "k";
        }
        return trimZero(count / 1_000_000.0) + "m";
    }

    private static String trimZero(double value) {
        String text = String.format("%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    public static String formatCount(int count) {
        return String.format("%,d", count);
    }

    /**
     * Formats an elapsed time compactly: sub-second in milliseconds, then seconds, then minutes.
     *
     * @param millis the elapsed time
     * @return e.g. {@code "840ms"}, {@code "4.2s"}, {@code "1m 05s"}
     */
    public static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        }
        if (millis < 60_000) {
            return String.format("%.1fs", millis / 1000.0);
        }
        long minutes = millis / 60_000;
        long seconds = (millis % 60_000) / 1000;
        return String.format("%dm %02ds", minutes, seconds);
    }

    @Override
    public String toString() {
        return toSummaryLine(-1, 0);
    }
}
