package com.eonmux.cadetcoder.ai.parsing;

import java.util.Comparator;

/**
 * Orders parameter names the way they are counted rather than the way they are spelt.
 *
 * <h2>Why plain text order is wrong here</h2>
 *
 * <p>A command with no arm of its own keeps the arguments it was given as {@code arg0},
 * {@code arg1}, ..., and the order those names are visited in IS the order the command line is
 * rebuilt in. Sorted as text, {@code arg10} falls between {@code arg1} and {@code arg2}, so from the
 * eleventh argument on the command line no longer says what the model wrote -- silently, since a
 * reordered argument list is still a valid one. Up to ten, text order and counting order agree,
 * which is why it went unseen.</p>
 *
 * <p>Digits are compared as numbers and everything else as text, so the ordering stays total and
 * deterministic for names that carry no number at all.</p>
 */
final class NumberedNameOrder implements Comparator<String> {

    /** The one instance; the comparator holds no state. */
    static final NumberedNameOrder INSTANCE = new NumberedNameOrder();

    private NumberedNameOrder() {
    }

    @Override
    public int compare(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i);
            char b = right.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int endA = runOfDigits(left, i);
                int endB = runOfDigits(right, j);
                int byNumber = compareNumbers(left.substring(i, endA), right.substring(j, endB));
                if (byNumber != 0) {
                    return byNumber;
                }
                i = endA;
                j = endB;
                continue;
            }
            if (a != b) {
                return Character.compare(a, b);
            }
            i++;
            j++;
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    /** @return the index just past the digits beginning at {@code from} */
    private static int runOfDigits(String text, int from) {
        int end = from;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return end;
    }

    /**
     * Compares two runs of digits by the value they spell.
     *
     * <p>Parsed as text rather than as a number: a run of digits in a parameter name has no length
     * limit, and one long enough to overflow a {@code long} would otherwise throw here rather than
     * be ordered. Leading zeros are dropped first, so {@code 007} and {@code 7} compare equal and
     * the tie is settled by whatever follows them.</p>
     */
    private static int compareNumbers(String left, String right) {
        String a = withoutLeadingZeros(left);
        String b = withoutLeadingZeros(right);
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }

    private static String withoutLeadingZeros(String digits) {
        int first = 0;
        while (first < digits.length() - 1 && digits.charAt(first) == '0') {
            first++;
        }
        return digits.substring(first);
    }
}
