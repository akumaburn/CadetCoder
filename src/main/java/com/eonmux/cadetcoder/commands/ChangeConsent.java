package com.eonmux.cadetcoder.commands;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What an answer to "shall I apply this?" actually said.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>Three commands rewrite a file after asking, and each read the answer by testing whether it
 * contained "yes", then "apply", then "no", in that order. Every one of those is a substring test
 * against free text somebody typed, and the word "apply" is in the question -- so it is in half the
 * answers to it. "no, don't apply that" contains "apply", matched the approving branch first, and
 * the file was rewritten; the branch that would have cancelled was never reached. "yes" likewise
 * matched inside "yesterday", and "no" inside "note", "nothing" and "now".</p>
 *
 * <h2>Why refusal is read first</h2>
 *
 * <p>Because the two mistakes do not cost the same. Reading an approval as a refusal wastes a
 * question; reading a refusal as an approval rewrites somebody's file against their word, and there
 * is nothing to undo it with. So every way of refusing is looked for before any way of agreeing, and
 * an answer carrying both -- "yes, but not that one" -- is a refusal.</p>
 *
 * <h2>Why whole words</h2>
 *
 * <p>A substring test cannot tell an answer from a sentence that happens to contain one. Matching on
 * word boundaries is what makes "nothing to change here" a request to change something rather than
 * a refusal, and what stops the question's own words being read back as the answer to it.</p>
 */
enum ChangeConsent {

    /** Go ahead. */
    APPLY,

    /** Do not. */
    CANCEL,

    /** Neither: work out something different first. */
    MODIFY,

    /** None of the three, so the question has not been answered and has to be asked again. */
    UNCLEAR;

    /** Every way of saying no, including the ones that quote the question back. */
    private static final Pattern REFUSED = word("no|nope|nah|not|don't|dont|doesn't|stop|cancel"
                                                + "|cancelled|abort|never|reject|discard|undo|skip");

    /** Every way of asking for something else instead. */
    private static final Pattern OTHERWISE = word("modify|change|changed|different|differently"
                                                  + "|instead|otherwise|revise|adjust|rework|amend");

    /** Every way of saying yes. */
    private static final Pattern AGREED = word("yes|yeah|yep|yup|y|ok|okay|sure|apply|proceed"
                                               + "|continue|go|confirm|accept|do it|please do");

    /**
     * Reads one answer.
     *
     * @param reply what was typed, which may be a word or a sentence; {@code null} is not an answer
     * @return what it said, {@link #UNCLEAR} when it did not answer the question
     */
    static ChangeConsent readFrom(String reply) {
        if (reply == null || reply.isBlank()) {
            return UNCLEAR;
        }
        String said = reply.strip().toLowerCase(Locale.ROOT);
        if (REFUSED.matcher(said).find()) {
            return CANCEL;
        }
        if (OTHERWISE.matcher(said).find()) {
            return MODIFY;
        }
        return AGREED.matcher(said).find() ? APPLY : UNCLEAR;
    }

    /**
     * @param alternatives the words to look for, separated by {@code |}
     * @return a pattern matching any of them as a whole word
     */
    private static Pattern word(String alternatives) {
        return Pattern.compile("(?<![\\p{L}'])(?:" + alternatives + ")(?![\\p{L}'])");
    }
}
