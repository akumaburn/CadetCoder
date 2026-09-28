package com.eonmux.cadetcoder.ui;

/**
 * What a program printed, marked so the shell shows it as it was printed.
 *
 * <h2>Why it is marked</h2>
 *
 * <p>The shell renders a result as Markdown, because a model writes its answers in Markdown. A
 * program does not. In {@code git diff} output a line that starts with {@code -} or {@code +} is a
 * removed or an added line, and read as Markdown both became list items behind the same bullet. A
 * shell comment became a heading, and an unmatched fence turned everything after it into code.</p>
 *
 * <p>The text is therefore sent between two marker lines, and {@link MarkdownRenderer} reads
 * nothing between them as Markdown. The shell's own status lines inside the run keep their
 * style, because the renderer recognises them by their markers and not by Markdown.</p>
 *
 * <h2>Where the markers go</h2>
 *
 * <p>The rules are those of {@link CollapsedOutput}: the markers are printed only while the shell
 * draws the screen and the printing thread's output is not collected for something else, and
 * {@link CollapsedOutput#strip} removes them from what is stored or forwarded.</p>
 */
public final class ProgramOutput {

    /** Opens a run of lines that a program printed. */
    public static final String OPEN = "\u0001cadet:program\u0001";

    /** Closes the run opened by {@link #OPEN}. */
    public static final String CLOSE = "\u0001cadet:/program\u0001";

    private ProgramOutput() {
    }

    /**
     * Whether program output would reach a renderer that reads the markers.
     *
     * @return whether to mark output
     */
    public static boolean isSupported() {
        return CollapsedOutput.isSupported();
    }

    /**
     * Prints what a program printed, between the markers where the shell reads them.
     *
     * <p>Where nothing reads the markers, the text is printed exactly as before. Where it is marked,
     * the closing marker always starts a line of its own, so output without a final newline cannot
     * swallow it.</p>
     *
     * @param text the program's output
     */
    public static void print(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean begun = begin();
        try {
            UnifiedOutput.print(text);
            if (begun && !text.endsWith("\n")) {
                UnifiedOutput.println();
            }
        } finally {
            end(begun);
        }
    }

    /**
     * Prints what a program printed, followed by a line break, as {@code println} would.
     *
     * @param text the program's output
     * @see #print(String)
     */
    public static void println(String text) {
        boolean begun = begin();
        try {
            UnifiedOutput.println(text);
        } finally {
            end(begun);
        }
    }

    /**
     * Opens a run of program output that the caller prints line by line.
     *
     * <p>Pass the result to {@link #end(boolean)} in a {@code finally} block, or the rest of the
     * result would be shown as program output.</p>
     *
     * @return whether the opening marker was printed
     */
    public static boolean begin() {
        if (!isSupported()) {
            return false;
        }
        UnifiedOutput.println(OPEN);
        return true;
    }

    /**
     * Closes a run opened by {@link #begin()}.
     *
     * @param begun what {@link #begin()} returned
     */
    public static void end(boolean begun) {
        if (begun) {
            UnifiedOutput.println(CLOSE);
        }
    }

    /**
     * Whether a line is one of the markers.
     *
     * <p>Leading and trailing space is ignored, as for {@link CollapsedOutput#isMarker}.</p>
     *
     * @param line the line to test
     * @return whether it is a marker rather than output
     */
    public static boolean isMarker(String line) {
        return opens(line) || closes(line);
    }

    /** @return whether {@code line} opens a run of program output */
    public static boolean opens(String line) {
        return line != null && line.strip().equals(OPEN);
    }

    /** @return whether {@code line} closes a run of program output */
    public static boolean closes(String line) {
        return line != null && line.strip().equals(CLOSE);
    }
}
