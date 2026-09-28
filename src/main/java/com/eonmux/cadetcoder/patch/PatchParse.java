package com.eonmux.cadetcoder.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading a unified diff back into the change it describes.
 *
 * <h2>Why this is written here rather than taken from JGit</h2>
 *
 * <p>JGit parses patches, and its parser works in terms of a repository: it wants an object
 * database to resolve blobs against and a working tree laid out as a checkout. A patch applied to
 * an ordinary directory has neither, and this tool edits directories that are often not
 * repositories at all.</p>
 *
 * <h2>What is read</h2>
 *
 * <p>Every shape a real patch arrives in. A {@code diff --git} header and an {@code index} line are
 * skipped, git's {@code a/} and {@code b/} prefix pair comes off the path, and a trailing timestamp
 * on the header is not part of the name. A {@code /dev/null} on either side says the patch adds or
 * removes the whole file, which is what any patch touching a new or deleted file looks like. A
 * {@code \ No newline at end of file} marker is kept, because it is the only thing in the format
 * that says what the end of the file is. Anything that is not a patch reads as no files, rather
 * than as an error, because callers hand this whatever a model wrote.</p>
 *
 * <h2>Why a broken hunk is an error rather than a short one</h2>
 *
 * <p>Text that is not a patch describes no change, and reading it as no files is the right answer.
 * A hunk whose body does not say what its header says it does is different: it describes a change,
 * and it describes it wrongly. Reading it as the shorter change it happens to spell out replaced
 * fewer lines than the patch called for and reported success, so the file was left holding lines
 * the patch meant to take out. There is no safe reading of it, so it is refused.</p>
 */
public final class PatchParse {

    /** The path git writes for a side of the patch that is not a file. */
    private static final String NOTHING = "/dev/null";

    /** The prefixes git puts on the old and the new side of a header pair. */
    private static final String OLD_SIDE_PREFIX = "a/";
    private static final String NEW_SIDE_PREFIX = "b/";

    /** What opens the line saying the file above it has no newline at its end. */
    private static final String NO_NEWLINE_MARKER = "\\";

    /** The line that opens a hunk, and the numbers in it. */
    private static final Pattern HUNK =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    /** The two file headers, with the path up to the first tab. */
    private static final Pattern OLD_FILE = Pattern.compile("^--- ([^\t]*)(?:\t.*)?$");
    private static final Pattern NEW_FILE = Pattern.compile("^\\+\\+\\+ ([^\t]*)(?:\t.*)?$");

    private PatchParse() {
    }

    /**
     * Reads a patch.
     *
     * @param patch the unified diff, in any of the shapes described above
     * @return one entry per file the patch changes, in the order stated; empty when the text is not
     *         a patch
     * @throws IllegalArgumentException when the text is a patch and one of its hunks does not say
     *                                  what its own header says it does
     */
    public static List<PatchedFile> read(String patch) {
        if (patch == null || patch.isBlank()) {
            return List.of();
        }
        return new Reader(withoutTrailingBlank(patch.split("\r?\n", -1))).read();
    }

    /** One pass over the lines of a patch, holding where it has got to. */
    private static final class Reader {

        private final String[] lines;
        private final List<PatchedFile> files = new ArrayList<>();

        private int at;

        Reader(String[] lines) {
            this.lines = lines;
        }

        List<PatchedFile> read() {
            while (at < lines.length) {
                Subject subject = subjectAtHeader();
                if (subject == null) {
                    at++;
                    continue;
                }
                List<PatchedHunk> hunks = hunks();
                if (!hunks.isEmpty()) {
                    files.add(new PatchedFile(subject.path, subject.kind, hunks));
                }
            }
            return files;
        }

        /**
         * The file named by a header pair at the current line, having stepped past it.
         *
         * @return what the pair names, or {@code null} when the current line does not open a file
         */
        private Subject subjectAtHeader() {
            Matcher oldSide = OLD_FILE.matcher(lines[at]);
            if (!oldSide.matches() || at + 1 >= lines.length) {
                return null;
            }
            Matcher newSide = NEW_FILE.matcher(lines[at + 1]);
            if (!newSide.matches()) {
                return null;
            }
            at += 2;

            String  before   = named(oldSide.group(1));
            String  after    = named(newSide.group(1));
            boolean noBefore = before.isEmpty() || NOTHING.equals(before);
            boolean noAfter  = after.isEmpty() || NOTHING.equals(after);

            if (noBefore && noAfter) {
                return null;
            }
            if (gitPrefixed(before, noBefore, after, noAfter)) {
                before = noBefore ? before : before.substring(OLD_SIDE_PREFIX.length());
                after  = noAfter  ? after  : after.substring(NEW_SIDE_PREFIX.length());
            }
            if (noBefore) {
                return new Subject(after, PatchKind.CREATE);
            }
            if (noAfter) {
                return new Subject(before, PatchKind.DELETE);
            }
            // The new side names where the content ends up, which is the file to write. A rename
            // is not handled as one: the patch is applied to the name it writes to.
            return new Subject(after, PatchKind.MODIFY);
        }

        /** Every hunk that follows, up to the next file header or the end. */
        private List<PatchedHunk> hunks() {
            List<PatchedHunk> hunks = new ArrayList<>();
            while (at < lines.length) {
                Matcher header = HUNK.matcher(lines[at]);
                if (!header.matches()) {
                    if (OLD_FILE.matcher(lines[at]).matches()) {
                        return hunks;
                    }
                    at++;
                    continue;
                }
                at++;
                hunks.add(body(Integer.parseInt(header.group(1)),
                               Integer.parseInt(header.group(3)),
                               count(header.group(2)), count(header.group(4))));
            }
            return hunks;
        }

        /**
         * The lines of one hunk.
         *
         * <h2>Why the counts in the header decide where it ends</h2>
         *
         * <p>The marker on each line does not, because the format is ambiguous: a removal of a line
         * whose own text begins with {@code -- } looks exactly like the {@code ---} that opens the
         * next file, and an added line beginning with {@code ++} looks like the {@code +++} beside
         * it. Reading by marker alone swallowed the header of the second file in a two-file patch,
         * so the second file was never seen. The header says how many lines each side has, so that
         * is what is read, and a body that does not come to those counts is refused.</p>
         *
         * <h2>Why the missing-newline marker is looked for twice</h2>
         *
         * <p>It describes the line above it, and the line it describes is usually the last line of
         * the hunk -- which is where the counts are met and the reading stops. A marker sitting
         * just past that point is the one that says the patch takes the final newline away, so it
         * is picked up after the loop as well as inside it.</p>
         */
        private PatchedHunk body(int oldStart, int newStart, int oldCount, int newCount) {
            List<String> before = new ArrayList<>();
            List<String> after  = new ArrayList<>();
            Ending       ending = new Ending();

            while (at < lines.length && (before.size() < oldCount || after.size() < newCount)) {
                String line = lines[at];
                if (line.startsWith(NO_NEWLINE_MARKER)) {
                    ending.describesTheLineAbove();
                    at++;
                    continue;
                }
                if (line.isEmpty()) {
                    // Several tools drop the trailing space from an empty context line.
                    before.add("");
                    after.add("");
                    ending.follows(' ');
                    at++;
                    continue;
                }
                char   marker = line.charAt(0);
                String text   = line.substring(1);
                if (marker == ' ') {
                    before.add(text);
                    after.add(text);
                } else if (marker == '-') {
                    before.add(text);
                } else if (marker == '+') {
                    after.add(text);
                } else {
                    throw malformed(oldStart, "line " + (at + 1) + " of the patch begins \"" + marker
                                    + "\", which is not how a line of a hunk begins");
                }
                ending.follows(marker);
                at++;
            }
            while (at < lines.length && lines[at].startsWith(NO_NEWLINE_MARKER)) {
                ending.describesTheLineAbove();
                at++;
            }
            if (before.size() != oldCount || after.size() != newCount) {
                throw malformed(oldStart, "its header describes " + oldCount + " lines before the"
                                + " change and " + newCount + " after, and its body writes out "
                                + before.size() + " and " + after.size());
            }
            return new PatchedHunk(oldStart, newStart, before, after,
                                   ending.beforeEndsWithoutNewline, ending.afterEndsWithoutNewline);
        }
    }

    /**
     * What the hunk has said so far about the end of the file.
     *
     * <p>A {@code \ No newline at end of file} marker belongs to the side of the hunk the line
     * above it belongs to: after a removed line it describes the file as it was, after an added
     * line the file as it will be, and after a context line both at once.</p>
     */
    private static final class Ending {

        private char    above;
        private boolean beforeEndsWithoutNewline;
        private boolean afterEndsWithoutNewline;

        /** Notes which side of the hunk the line just read belongs to. */
        void follows(char marker) {
            above = marker;
        }

        /** Takes a marker line as describing the end of whichever sides the line above is on. */
        void describesTheLineAbove() {
            beforeEndsWithoutNewline |= above == ' ' || above == '-';
            afterEndsWithoutNewline  |= above == ' ' || above == '+';
        }
    }

    /** Refuses a hunk that does not describe one coherent change. */
    private static IllegalArgumentException malformed(int oldStart, String what) {
        return new IllegalArgumentException("The hunk at line " + oldStart + " is malformed: "
                                            + what + ".");
    }

    /** How many lines one side of a hunk has; a header that omits the number means one. */
    private static int count(String written) {
        return written == null ? 1 : Integer.parseInt(written);
    }

    /**
     * The lines of a patch without the empty one a trailing newline leaves behind.
     *
     * <p>Splitting keeps it, and an empty line inside a hunk is a context line, so the artifact was
     * read as a blank line the file is expected to hold.</p>
     */
    private static String[] withoutTrailingBlank(String[] lines) {
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            String[] shorter = new String[lines.length - 1];
            System.arraycopy(lines, 0, shorter, 0, shorter.length);
            return shorter;
        }
        return lines;
    }

    /** What one header pair names: a file, and what is happening to it. */
    private record Subject(String path, PatchKind kind) {
    }

    /** A header path as written, with the whitespace around it removed. */
    private static String named(String path) {
        return path == null ? "" : path.trim();
    }

    /**
     * Whether a header pair carries git's prefixes rather than two real directories.
     *
     * <h2>Why both sides have to agree</h2>
     *
     * <p>git writes {@code a/} on the old side of a pair and {@code b/} on the new one, so a pair
     * that carries the prefixes carries one of each. Taking two characters off anything that began
     * {@code a/} or {@code b/} resolved a patch against a project with a real top-level directory
     * named {@code a} -- which {@code git diff --no-prefix} and every non-git differ write on both
     * sides -- to a file one directory above the one the patch names.</p>
     *
     * @param noBefore whether the old side is {@code /dev/null} rather than a file, in which case
     *                 it carries no prefix and has no say
     * @param noAfter  the same for the new side
     */
    private static boolean gitPrefixed(String before, boolean noBefore,
                                       String after, boolean noAfter) {
        return (noBefore || before.startsWith(OLD_SIDE_PREFIX))
               && (noAfter || after.startsWith(NEW_SIDE_PREFIX));
    }
}
