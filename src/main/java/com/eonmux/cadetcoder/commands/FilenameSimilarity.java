package com.eonmux.cadetcoder.commands;

/**
 * Whether two filenames are close enough to be the same file said differently.
 *
 * <h2>What this is for</h2>
 *
 * <p>A model naming a file it has not read gets it nearly right: the right name in the wrong
 * package, {@code UserService.java} for {@code UserServiceImpl.java}, a test for the class it
 * tests. This decides whether a path already seen in this conversation is close enough to be worth
 * substituting for the one that was asked for.</p>
 *
 * <h2>Why the comparison is staged</h2>
 *
 * <p>Cheap and certain first, expensive and approximate last: equality, containment, a shared
 * extension with a shared prefix or suffix, and only then an edit distance. A long pair
 * short-circuits to counting shared characters, because the distance matrix is quadratic and
 * nothing here is worth that.</p>
 *
 * <h2>Why there is no rule for the Java pairings</h2>
 *
 * <p>There was one -- {@code X} against {@code XTest}, {@code XImpl}, {@code IX} -- and it could
 * never answer, because each of those three shares three characters at one end with the name it is
 * paired with, which the affix rule ahead of it has already accepted. A stage that cannot decide
 * anything still tells the next reader that removing the affix rule would leave the conventions
 * covered, and it would not.</p>
 */
final class FilenameSimilarity {

    /** How alike two base names must be before an edit distance is taken as a match. */
    private static final double SIMILARITY_THRESHOLD = 0.7;

    /** Above this length the edit distance is abandoned for something linear. */
    private static final int LONG_STRING = 100;

    /** How much of the shorter name has to match at one end to count as a shared affix. */
    private static final int AFFIX_LENGTH = 3;

    private final LoggingCommandSupport log;

    /**
     * @param log where to record what was compared and why it matched
     */
    FilenameSimilarity(LoggingCommandSupport log) {
        this.log = log;
    }

    /**
     * The filename at the end of a path.
     *
     * <p>Drops a trailing line number ({@code file.java:10}), takes the last segment of either slash
     * style, and leaves a Windows drive letter alone.</p>
     *
     * @param path the path to reduce
     * @return the base filename, or {@code null} when there is none to find
     */
    String baseFilename(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        try {
            String remaining = path.replace('\\', '/');

            int colon = remaining.indexOf(':');
            if (colon > 0) {
                boolean windowsDriveLetter = colon == 1 && Character.isLetter(remaining.charAt(0));
                if (!windowsDriveLetter) {
                    remaining = remaining.substring(0, colon);
                }
            }

            int lastSlash = remaining.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < remaining.length() - 1) {
                return remaining.substring(lastSlash + 1);
            }

            lastSlash = remaining.lastIndexOf('\\');
            if (lastSlash >= 0 && lastSlash < remaining.length() - 1) {
                return remaining.substring(lastSlash + 1);
            }

            return remaining;
        } catch (RuntimeException e) {
            log.logDebug("Filename Extraction",
                    "Error extracting filename from path: " + path + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Whether two filenames name what is probably the same thing.
     *
     * @param file1 one filename
     * @param file2 the other
     * @return {@code true} when they are close enough to substitute one for the other
     */
    boolean areSimilar(String file1, String file2) {
        if (file1 == null || file2 == null || file1.isEmpty() || file2.isEmpty()) {
            return false;
        }
        if (file1.equals(file2)) {
            return true;
        }
        // e.g. Test.java against TestImpl.java
        if (file1.contains(file2) || file2.contains(file1)) {
            return true;
        }

        try {
            int dot1 = file1.lastIndexOf('.');
            int dot2 = file2.lastIndexOf('.');
            if (dot1 <= 0 || dot2 <= 0) {
                return false;
            }
            String extension = file1.substring(dot1).toLowerCase();
            if (!extension.equals(file2.substring(dot2).toLowerCase())) {
                return false;
            }
            return baseNamesMatch(file1.substring(0, dot1), file2.substring(0, dot2), file1, file2);
        } catch (RuntimeException e) {
            log.logDebug("Filename Similarity", "Error comparing filenames: " + e.getMessage());
            return false;
        }
    }

    /** The comparison once the extension is known to be shared. */
    private boolean baseNamesMatch(String name1, String name2, String file1, String file2) {
        if (shareAnAffix(name1, name2)) {
            return true;
        }

        double similarity = similarity(name1, name2);
        if (similarity >= SIMILARITY_THRESHOLD) {
            log.logDebug("Filename Similarity",
                    String.format("High similarity (%f) between %s and %s", similarity, file1, file2));
            return true;
        }
        return false;
    }

    /** Whether the two names begin or end alike over the leading few characters. */
    private static boolean shareAnAffix(String name1, String name2) {
        int length = Math.min(AFFIX_LENGTH, Math.min(name1.length(), name2.length()));
        if (length <= 0) {
            return false;
        }
        return name1.startsWith(name2.substring(0, length))
               || name2.startsWith(name1.substring(0, length))
               || name1.endsWith(name2.substring(name2.length() - length))
               || name2.endsWith(name1.substring(name1.length() - length));
    }

    /**
     * How alike two strings are, by Levenshtein distance.
     *
     * @return 0.0 for nothing in common, 1.0 for identical
     */
    private static double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0) {
            return len2 == 0 ? 1.0 : 0.0;
        }
        if (len2 == 0) {
            return 0.0;
        }
        // The matrix below is len1 * len2; for anything long that cost buys nothing worth having.
        if (len1 > LONG_STRING || len2 > LONG_STRING) {
            return commonCharacterRatio(s1, s2);
        }

        int[][] distance = new int[len1 + 1][len2 + 1];
        for (int i = 0; i <= len1; i++) {
            distance[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            distance[0][j] = j;
        }
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                distance[i][j] = Math.min(Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                                          distance[i - 1][j - 1] + cost);
            }
        }

        int longest = Math.max(len1, len2);
        return longest == 0 ? 1.0 : 1.0 - ((double) distance[len1][len2] / longest);
    }

    /** The linear stand-in for the distance: how much of one string's alphabet the other has. */
    private static double commonCharacterRatio(String s1, String s2) {
        int common = 0;
        for (char c : s1.toCharArray()) {
            if (s2.indexOf(c) >= 0) {
                common++;
            }
        }
        int longest = Math.max(s1.length(), s2.length());
        return longest == 0 ? 1.0 : (double) common / longest;
    }
}
