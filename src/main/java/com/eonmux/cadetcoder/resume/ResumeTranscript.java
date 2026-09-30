package com.eonmux.cadetcoder.resume;

import java.util.ArrayList;
import java.util.List;

/**
 * The part of an interrupted run's conversation that is kept for a resume.
 *
 * <p>The conversation holds every command's output, and a long run can hold megabytes of it. The
 * session file is written whole on every save, so the point keeps the latest
 * {@link #MAX_CHARS} characters, which is the part a resumed run needs most: what it did last. What
 * is left out is counted in a line of its own, so the resumed run knows the record starts part-way
 * through.</p>
 */
public final class ResumeTranscript {

    /** How many characters of the conversation are kept. */
    public static final int MAX_CHARS = 60_000;

    private ResumeTranscript() {
    }

    /**
     * @param transcript a run's conversation, oldest first
     * @return its latest entries, within {@link #MAX_CHARS} characters
     */
    public static List<String> bounded(List<String> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        int          size = 0;
        int          from = transcript.size();
        while (from > 0) {
            String entry = transcript.get(from - 1);
            String text  = entry == null ? "" : entry;
            if (size + text.length() > MAX_CHARS) {
                if (kept.isEmpty()) {
                    // One entry larger than the whole allowance: its end is what it ended with.
                    kept.add(0, text.substring(text.length() - MAX_CHARS));
                    from--;
                }
                break;
            }
            kept.add(0, text);
            size += text.length();
            from--;
        }
        if (from > 0) {
            kept.add(0, "System: " + from + (from == 1 ? " earlier entry of this run is"
                                                        : " earlier entries of this run are")
                        + " not kept.");
        }
        return List.copyOf(kept);
    }
}
