package com.eonmux.cadetcoder.commands;

/**
 * Where the caret lands when it moves a word at a time.
 *
 * <h2>Why a word is a run of letters, digits and underscores</h2>
 *
 * <p>The lines typed here are mostly paths, flags and prose, and a rule that treats every other
 * character as a boundary is what makes a path navigable: {@code src/main/java/Foo.java} is six
 * stops rather than one. The alternative -- "a word is anything that is not a space" -- turns the
 * longest thing anyone types at this prompt back into a single jump, which is the case the key is
 * for.</p>
 *
 * <h2>Why it is here and not in {@code ShellKeys}</h2>
 *
 * <p>That class answers what a keystroke means to the <em>shell</em>. Moving the caret is what a
 * keystroke means to the line being typed, and the answer depends on the line rather than on
 * anything on screen -- so it is a function of the text and the caret, and testable as one.</p>
 */
final class InputEditing {

    private InputEditing() {
    }

    /**
     * Where the caret goes when it moves one word to the left.
     *
     * <p>Over any separators immediately behind it, then over the word behind those. So a press at
     * the end of {@code "read Foo.java"} lands before {@code java}, and the next before the dot.</p>
     *
     * @param text  the line being typed
     * @param caret where the caret is, as an index into it
     * @return the new index, never less than 0
     */
    static int previousWord(String text, int caret) {
        String line = text == null ? "" : text;
        int    at   = clamp(caret, line.length());
        while (at > 0 && !isWordChar(line.charAt(at - 1))) {
            at--;
        }
        while (at > 0 && isWordChar(line.charAt(at - 1))) {
            at--;
        }
        return at;
    }

    /**
     * Where the caret goes when it moves one word to the right.
     *
     * @param text  the line being typed
     * @param caret where the caret is, as an index into it
     * @return the new index, never past the end
     */
    static int nextWord(String text, int caret) {
        String line = text == null ? "" : text;
        int    end  = line.length();
        int    at   = clamp(caret, end);
        while (at < end && !isWordChar(line.charAt(at))) {
            at++;
        }
        while (at < end && isWordChar(line.charAt(at))) {
            at++;
        }
        return at;
    }

    /** Letters, digits and underscore; everything else separates one word from the next. */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int clamp(int caret, int length) {
        return Math.max(0, Math.min(caret, length));
    }
}
