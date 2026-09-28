package com.eonmux.cadetcoder.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * Putting the hunks of a patch into a text.
 *
 * <h2>Why the line number in a hunk header is a hint</h2>
 *
 * <p>A patch is written against one version of a file and applied to another. Any edit above a hunk
 * moves every line below it, so the numbers in the header are often already wrong when the patch
 * arrives. What locates a hunk is the run of lines it expects to find. The header says where to
 * look first, and the search widens outwards from there, which is what {@code git apply} does.</p>
 *
 * <h2>Why the search is bounded and any distance travelled is reported</h2>
 *
 * <p>A hint is only worth following while it is still a hint. A run of lines found thousands of
 * lines from where the patch says it is is not the run the patch meant, it is a coincidence in a
 * file the patch was not written against, and applying a hunk there silently rewrites a part of the
 * file nobody was talking about. So the search gives up past {@link #FURTHEST_A_HUNK_MAY_HAVE_MOVED}
 * lines, and a hunk that did have to move says how far, the way {@code git apply} does.</p>
 *
 * <h2>Why nothing is applied in part</h2>
 *
 * <p>A file with three of four hunks in it is a state nobody wrote and nobody asked for, and the
 * caller cannot tell from the result which three went in. Every hunk is placed, or the text comes
 * back unchanged with the reason.</p>
 *
 * <h2>Why the file's own line endings survive</h2>
 *
 * <p>A patch is read with its line endings taken off, because a diff written on Windows and a diff
 * written anywhere else describe the same change. The file being patched is the one whose endings
 * have to survive it: comparing its lines with the carriage returns still on them matched no hunk
 * at all, and rewriting it with bare newlines would show up as every line changed in the next
 * diff.</p>
 */
public final class PatchApply {

    /**
     * How far either side of its stated line a hunk's lines may be looked for.
     *
     * <p>Generous enough for a file that has been added to and rearranged since the patch was
     * written, and short of the distance at which a match is a coincidence rather than the run of
     * lines the patch meant.</p>
     */
    private static final int FURTHEST_A_HUNK_MAY_HAVE_MOVED = 1000;

    /** The two line endings a text file is written with. */
    private static final String LF   = "\n";
    private static final String CRLF = "\r\n";

    private PatchApply() {
    }

    /**
     * What happened to a patch.
     *
     * @param applied        whether every hunk went in
     * @param text           the new text when it did, {@code null} when it did not
     * @param reason         why it did not, or {@code null} when it did
     * @param alreadyApplied whether the text already reads as the patch intended
     * @param offsets        one sentence per hunk that went in somewhere other than the line its
     *                       header named, in the order the patch stated them
     */
    public record Result(boolean applied, String text, String reason, boolean alreadyApplied,
                         List<String> offsets) {

        public Result {
            offsets = List.copyOf(offsets);
        }

        static Result changed(String text, List<String> offsets) {
            return new Result(true, text, null, false, offsets);
        }

        static Result refused(String reason, boolean alreadyApplied) {
            return new Result(false, null, reason, alreadyApplied, List.of());
        }
    }

    /**
     * Applies every hunk to a text.
     *
     * @param text  the text as it stands, or {@code null} for an empty one
     * @param hunks the changes to make, in the order the patch stated them
     * @return the new text, or the reason it could not be made
     */
    public static Result to(String text, List<PatchedHunk> hunks) {
        String original = text == null ? "" : text;
        if (hunks == null || hunks.isEmpty()) {
            return Result.changed(original, List.of());
        }

        List<String> lines           = linesOf(original);
        boolean      endsWithNewline = original.isEmpty() || original.endsWith(LF);

        // Every hunk is located in the ORIGINAL lines, and the replacements are collected as spans
        // to cut out. Searching the text as it grows would move later hunks by the length of
        // earlier ones, and a hunk written against the original would then be looked for in the
        // wrong place.
        List<Placement> placements = new ArrayList<>();
        List<String>    offsets    = new ArrayList<>();
        for (int i = 0; i < hunks.size(); i++) {
            PatchedHunk hunk     = hunks.get(i);
            int         position = i + 1;
            int         hoped    = hopedFor(hunk, lines.size());
            int         at       = locate(lines, hunk, hoped);
            if (at < 0) {
                return Result.refused(whyNot(position, hunk, lines), fits(lines, hunk.after()));
            }
            String clash = clashWith(placements, at, position);
            if (clash != null) {
                return Result.refused(clash, false);
            }
            if (at != hoped) {
                offsets.add("Hunk " + position + " applied at line " + (at + 1) + " (offset "
                            + (at - hoped) + " lines).");
            }
            placements.add(new Placement(position, at, hunk));
        }

        return Result.changed(rebuilt(lines, placements, endsWithNewline, endingOf(original)),
                              offsets);
    }

    /**
     * One hunk, where in the original text it was found, and which hunk of the patch it is.
     *
     * @param position which hunk of the patch this is, counted from one, so that a refusal can
     *                 name it the way the patch's reader would
     */
    private record Placement(int position, int at, PatchedHunk hunk) {

        /** The index just past the last line this hunk replaces. */
        int end() {
            return at + hunk.before().size();
        }
    }

    /**
     * The line a hunk says to look at, counted from zero.
     *
     * <h2>Why a hunk that removes nothing counts differently</h2>
     *
     * <p>The old side of a hunk header names the first line the hunk replaces, so a hunk that
     * replaces lines begins at {@code oldStart - 1} counted from zero. A hunk that replaces nothing
     * -- {@code @@ -N,0 +M,k @@} -- has no such line: N is the line the new lines go AFTER, so the
     * index to insert at is N itself. Counting it back by one put every insertion one line early,
     * and because an empty run of expected lines matches at any index, nothing noticed: the lines
     * landed in the wrong place and the patch reported success.</p>
     */
    private static int hopedFor(PatchedHunk hunk, int size) {
        int line = hunk.before().isEmpty() ? hunk.oldStart() : hunk.oldStart() - 1;
        return Math.min(Math.max(0, line), size);
    }

    /**
     * Where a hunk's expected lines sit in the text.
     *
     * <p>The header's line is tried first, then lines either side of it, widening outwards. That
     * finds the nearest fit rather than the first one from the top, which matters when a file
     * repeats a short run of lines.</p>
     *
     * @return the index of the first expected line, or {@code -1} when it is nowhere near
     */
    private static int locate(List<String> lines, PatchedHunk hunk, int hoped) {
        if (matchesAt(lines, hunk.before(), hoped)) {
            return hoped;
        }
        int furthest = Math.min(lines.size(), FURTHEST_A_HUNK_MAY_HAVE_MOVED);
        for (int away = 1; away <= furthest; away++) {
            if (matchesAt(lines, hunk.before(), hoped - away)) {
                return hoped - away;
            }
            if (matchesAt(lines, hunk.before(), hoped + away)) {
                return hoped + away;
            }
        }
        return -1;
    }

    /**
     * Why a hunk cannot go where it was found, given the hunks placed before it.
     *
     * <h2>Why two hunks may not share a line</h2>
     *
     * <p>Each hunk is located on its own, and the new text is then built by walking the placements
     * once from the top of the file. Two hunks whose spans overlap, or that resolve in the opposite
     * order to the one the patch states, send that walk backwards: lines between them are written
     * twice or dropped altogether, and the file comes back changed in ways no hunk described while
     * the result reports that the patch applied. Checking only the placement before this one is
     * enough, because every earlier one has already been checked against its own predecessor.</p>
     *
     * @return the reason, or {@code null} when the hunk may go there
     */
    private static String clashWith(List<Placement> placed, int at, int position) {
        if (placed.isEmpty()) {
            return null;
        }
        Placement last = placed.get(placed.size() - 1);
        if (at >= last.end()) {
            return null;
        }
        if (at < last.at()) {
            return "Hunk " + position + " was found at line " + (at + 1) + ", above hunk "
                   + last.position() + " at line " + (last.at() + 1) + ". The hunks of a patch have"
                   + " to run down the file in the order the patch states them.";
        }
        return "Hunk " + position + " was found at line " + (at + 1) + ", inside the lines hunk "
               + last.position() + " replaces (lines " + (last.at() + 1) + " to " + last.end()
               + "). Two hunks cannot change the same lines.";
    }

    /** Whether the expected lines sit at exactly this index. */
    private static boolean matchesAt(List<String> lines, List<String> expected, int at) {
        if (at < 0 || at + expected.size() > lines.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!lines.get(at + i).equals(expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether a run of lines appears anywhere in the text. */
    private static boolean fits(List<String> lines, List<String> wanted) {
        if (wanted.isEmpty()) {
            return false;
        }
        for (int at = 0; at + wanted.size() <= lines.size(); at++) {
            if (matchesAt(lines, wanted, at)) {
                return true;
            }
        }
        return false;
    }

    /** Why a hunk could not be placed, in a sentence that names it and what it wanted. */
    private static String whyNot(int position, PatchedHunk hunk, List<String> lines) {
        if (fits(lines, hunk.after())) {
            return "Hunk " + position + " is already applied: the file already reads as the patch"
                   + " intends.";
        }
        if (fits(lines, hunk.before())) {
            return "Hunk " + position + " expects line " + hunk.oldStart() + ", and the lines it"
                   + " expects are more than " + FURTHEST_A_HUNK_MAY_HAVE_MOVED + " lines away from"
                   + " there. That is not the same run of lines; the patch was written against a"
                   + " different file.";
        }
        String wanted = hunk.before().isEmpty() ? "" : hunk.before().get(0);
        return "Hunk " + position + " does not fit. It expects line " + hunk.oldStart()
               + " to begin \"" + wanted + "\", and no run of those lines is in the file.";
    }

    /**
     * The text with each placed hunk's lines swapped for its replacement.
     *
     * <h2>Why the last hunk can move the end of the file</h2>
     *
     * <p>Whether the last line has a newline after it is part of what the file is, and the only
     * hunk that can change it is one whose replacement reaches the end. Re-imposing the original's
     * ending on the result left a patch that adds or removes the final newline applying cleanly and
     * changing nothing. A hunk whose patch carried no {@code \ No newline at end of file} marker
     * says nothing about the ending, so the file keeps its own.</p>
     */
    private static String rebuilt(List<String> lines, List<Placement> placements,
                                  boolean endsWithNewline, String ending) {
        StringBuilder built     = new StringBuilder();
        int           at        = 0;
        boolean       newlineAtEnd = endsWithNewline;

        for (Placement placement : placements) {
            for (; at < placement.at(); at++) {
                built.append(lines.get(at)).append(ending);
            }
            for (String line : placement.hunk().after()) {
                built.append(line).append(ending);
            }
            at = placement.end();
            if (at == lines.size() && placement.hunk().speaksOfFinalNewline()) {
                newlineAtEnd = !placement.hunk().afterEndsWithoutNewline();
            }
        }
        for (; at < lines.size(); at++) {
            built.append(lines.get(at)).append(ending);
        }

        if (!newlineAtEnd && built.length() >= ending.length()) {
            built.setLength(built.length() - ending.length());
        }
        return built.toString();
    }

    /**
     * Which line ending to write the result with.
     *
     * <p>The majority of what the file already uses, so that a file with one stray ending in it is
     * tidied towards the one it is written in rather than the other way about. A file with no line
     * endings at all has nothing to preserve and gets the ordinary one.</p>
     */
    private static String endingOf(String text) {
        int newlines        = 0;
        int carriageReturns = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') {
                continue;
            }
            newlines++;
            if (i > 0 && text.charAt(i - 1) == '\r') {
                carriageReturns++;
            }
        }
        return carriageReturns > 0 && carriageReturns * 2 >= newlines ? CRLF : LF;
    }

    /**
     * The text as lines, without their line endings and without the empty line a trailing newline
     * leaves behind.
     *
     * <p>The carriage return of a CRLF file comes off here because a hunk read out of a patch never
     * has one: leaving it on made every line of such a file differ from the line the patch expected,
     * so no hunk ever matched and every patch was refused as not fitting.</p>
     */
    private static List<String> linesOf(String text) {
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split(LF, -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        if (lines.get(lines.size() - 1).isEmpty() && text.endsWith(LF)) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
