package com.eonmux.cadetcoder.util;

import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * What two texts differ by, in the format everything else already reads.
 *
 * <h2>Why unified format</h2>
 *
 * <p>It is what {@code git diff} prints and what {@code patch} reads. A change written this way can
 * be shown to a person, applied by this tool, applied by {@code git apply}, or read by a model that
 * has seen more of this format than of any other. This tool's own SEARCH/REPLACE block is none of
 * those things outside this tool.</p>
 *
 * <h2>Why JGit does the comparing</h2>
 *
 * <p>JGit is already a dependency, and its histogram difference is the same algorithm
 * {@code git diff} uses, so a change described here is described the way the project's own history
 * describes it. Nothing here needs a repository: the comparison works on two byte arrays.</p>
 */
public final class UnifiedDiff {

    /** How many unchanged lines surround each change unless a caller says otherwise. */
    public static final int DEFAULT_CONTEXT = 3;

    /**
     * What ends the two header lines.
     *
     * <h2>Why not the platform's separator</h2>
     *
     * <p>JGit writes the hunks with a bare newline on every platform, so a header written with
     * {@code System.lineSeparator()} gave a diff on Windows whose first two lines ended one way and
     * whose body ended another. No reader of the format expects that, and a run on any other
     * platform never shows it.</p>
     */
    private static final String HEADER_END = "\n";

    private UnifiedDiff() {
    }

    /**
     * How many lines a change adds and removes.
     *
     * @param added   lines present in the new text and not in the old
     * @param removed lines present in the old text and not in the new
     */
    public record Tally(int added, int removed) {

        /** Whether the two texts differ at all. */
        public boolean changed() {
            return added > 0 || removed > 0;
        }
    }

    /**
     * The difference between two texts.
     *
     * @param oldName what to call the first text in the header
     * @param oldText the first text, or {@code null} for an empty one
     * @param newName what to call the second text in the header
     * @param newText the second text, or {@code null} for an empty one
     * @param context how many unchanged lines to show around each change
     * @return the unified diff, or an empty string when the texts are the same
     */
    public static String between(String oldName, String oldText,
                                 String newName, String newText, int context) {
        RawText  before = raw(oldText);
        RawText  after  = raw(newText);
        EditList edits  = new HistogramDiff().diff(RawTextComparator.DEFAULT, before, after);
        if (edits.isEmpty()) {
            return "";
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(body)) {
            formatter.setContext(Math.max(0, context));
            formatter.format(edits, before, after);
        } catch (IOException impossible) {
            // The sink is a byte array in memory. Nothing here can fail to write to it.
            throw new UncheckedIOException(impossible);
        }
        return "--- " + oldName + HEADER_END
               + "+++ " + newName + HEADER_END
               + body.toString(StandardCharsets.UTF_8);
    }

    /**
     * How much two texts differ, without writing the difference out.
     *
     * @param oldText the first text, or {@code null} for an empty one
     * @param newText the second text, or {@code null} for an empty one
     * @return the number of lines added and removed
     */
    public static Tally tally(String oldText, String newText) {
        RawText  before = raw(oldText);
        RawText  after  = raw(newText);
        EditList edits  = new HistogramDiff().diff(RawTextComparator.DEFAULT, before, after);

        int added   = 0;
        int removed = 0;
        for (Edit edit : edits) {
            removed += edit.getEndA() - edit.getBeginA();
            added   += edit.getEndB() - edit.getBeginB();
        }
        return new Tally(added, removed);
    }

    /** A text as JGit reads it, with a missing text read as an empty one. */
    private static RawText raw(String text) {
        return new RawText((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }
}
