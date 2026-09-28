package com.eonmux.cadetcoder.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Command output that is kept but not shown, until someone asks to see it.
 *
 * <h2>Why the output is marked rather than cut</h2>
 *
 * <p>An agentic turn runs a dozen commands and each one's output can be hundreds of lines, so the
 * console hides it by default. Hiding used to mean CUTTING: four lines were printed, the rest was
 * replaced by a count, and the text was gone -- there was nothing left on screen to open, and the
 * only way to see what a command had actually said was to turn the setting on and run it again.</p>
 *
 * <p>So the text is sent to the shell whole, wrapped in two marker lines. The live console skips
 * what is between them; the shell already focuses one result when it is clicked, and a focused
 * result shows everything it holds. The announcement above the output -- the command and the
 * model's reason for running it -- is what the click lands on, which is the line that explains why
 * the output exists in the first place.</p>
 *
 * <h2>Where the markers must not go</h2>
 *
 * <p>They are display control, meaningful only to the shell's renderer. They are emitted only when
 * the shell is drawing the screen and the printing thread's output is not being collected for
 * something else, and they are removed from the session log. A worker's transcript is read back by
 * {@code workers show} and reaches the model, so a worker collapses the plain way instead: a line
 * saying how much was hidden.</p>
 */
public final class CollapsedOutput {

    /**
     * Opens a run of lines the live console leaves out.
     *
     * <p>Starts with a control character so no command output can produce it by accident, and no
     * terminal will render it if one ever escapes the filter.</p>
     */
    public static final String OPEN = "\u0001cadet:collapsed\u0001";

    /** Closes the run opened by {@link #OPEN}. */
    public static final String CLOSE = "\u0001cadet:/collapsed\u0001";

    private CollapsedOutput() {
    }

    /**
     * Whether collapsed output would reach a console that can expand it again.
     *
     * <p>Two conditions, and both are about who will read the lines. The shell has to be drawing
     * the screen, because nothing else knows what the markers mean. And the printing thread's
     * output must not be on its way into a worker's transcript, which is read back as text by
     * {@code workers show} and by the model.</p>
     *
     * @return whether to mark output rather than summarise it
     */
    public static boolean isSupported() {
        return TuiMode.isActive() && !OutputCapture.isCapturing();
    }

    /**
     * Prints something the live console leaves out, where it can be opened again.
     *
     * <p>For the lines about a step rather than from it: the iteration it belongs to and the record
     * of how it went. They are the run's bookkeeping, one or two lines per command, and a turn that
     * issues a dozen commands spends more of the screen on them than on what it did. A focused
     * result shows them in place, in order, with the output they describe.</p>
     *
     * <p>Where no renderer can open them again -- a plain console, a worker's collected transcript
     * -- they are printed as they always were. The same is true when {@code ui.showCommandOutput}
     * is on, which is the setting that asks for everything inline.</p>
     *
     * @param printing what to print between the markers
     */
    public static void hiding(Runnable printing) {
        if (printing == null) {
            return;
        }
        if (CommandOutputVisibility.isVisible() || !isSupported()) {
            printing.run();
            return;
        }
        ThemedOutputFormatter.routeOutput(OPEN);
        try {
            printing.run();
        } finally {
            // Closed even when the printing threw, or the rest of the run would be filed as part of
            // a run of hidden lines and vanish from the live console.
            ThemedOutputFormatter.routeOutput(CLOSE);
        }
    }

    /**
     * Whether a line is one of the markers.
     *
     * <p>Leading and trailing space is ignored: the block a marker surrounds is printed through the
     * formatter, which indents what it prints.</p>
     *
     * @param line the line to test
     * @return whether it is a marker rather than output
     */
    public static boolean isMarker(String line) {
        return opens(line) || closes(line);
    }

    /** @return whether {@code line} opens a collapsed run */
    public static boolean opens(String line) {
        return line != null && line.strip().equals(OPEN);
    }

    /** @return whether {@code line} closes a collapsed run */
    public static boolean closes(String line) {
        return line != null && line.strip().equals(CLOSE);
    }

    /**
     * The lines as the live console shows them: everything except the collapsed runs.
     *
     * <p>A run left open by a buffer that dropped its closing marker ends where the lines do, which
     * hides the tail of one result rather than every result after it.</p>
     *
     * @param lines the segment's lines
     * @return the lines to draw
     */
    public static List<String> visible(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        List<String> shown    = new ArrayList<>(lines.size());
        boolean      collapsed = false;
        for (String line : lines) {
            if (opens(line)) {
                collapsed = true;
                continue;
            }
            if (closes(line)) {
                collapsed = false;
                continue;
            }
            if (!collapsed) {
                shown.add(line);
            }
        }
        return shown;
    }

    /**
     * The lines as a focused result shows them: all of them, without the markers.
     *
     * @param lines the segment's lines
     * @return the lines to draw
     */
    public static List<String> expanded(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        List<String> shown = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (!isMarker(line)) {
                shown.add(line);
            }
        }
        return shown;
    }

    /**
     * Text with the marker lines taken out, for anything that stores or forwards it.
     *
     * <p>Removes the markers of {@link ProgramOutput} as well. Both kinds are display control, and
     * a log or a prompt has no use for either.</p>
     *
     * @param text the text as it was printed
     * @return the same text without the markers
     */
    public static String strip(String text) {
        if (text == null || text.indexOf('\u0001') < 0) {
            return text;
        }
        StringBuilder kept = new StringBuilder(text.length());
        int           at   = 0;
        while (at <= text.length()) {
            int end  = text.indexOf('\n', at);
            int stop = end < 0 ? text.length() : end;
            String line = text.substring(at, stop);
            if (!isMarker(line) && !ProgramOutput.isMarker(line)) {
                kept.append(line);
                if (end >= 0) {
                    kept.append('\n');
                }
            }
            if (end < 0) {
                break;
            }
            at = end + 1;
        }
        return kept.toString();
    }
}
