package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a typed answer to "shall I apply this?".
 *
 * <p><b>The defect</b>: the answer was read by substring, in the order yes, apply, no. Both halves
 * of that are wrong. The order put the destructive reading first, so anything ambiguous rewrote the
 * file; and a substring test cannot tell an answer from a sentence containing one, so "nothing" said
 * no, "yesterday" said yes, and the question's own word -- "apply" -- said yes wherever it appeared,
 * including in "no, don't apply that".</p>
 *
 * <p><b>What is locked here</b>: that every way of refusing is read before any way of agreeing, so
 * an answer carrying both is a refusal; that the words are matched whole; and that an answer which
 * says none of the three is not guessed at.</p>
 */
public class AnAnswerIsReadAsAWholeWordTest {

    @Test
    public void arefusalIsReadBeforeAnythingElseInTheSameSentence() {
        assertThat(ChangeConsent.readFrom("no, don't apply that")).isEqualTo(ChangeConsent.CANCEL);
        assertThat(ChangeConsent.readFrom("yes, but not the second one"))
                .isEqualTo(ChangeConsent.CANCEL);
        assertThat(ChangeConsent.readFrom("cancel and apply nothing"))
                .isEqualTo(ChangeConsent.CANCEL);
    }

    @Test
    public void theplainAnswersMeanWhatTheySay() {
        assertThat(ChangeConsent.readFrom("yes")).isEqualTo(ChangeConsent.APPLY);
        assertThat(ChangeConsent.readFrom("  Y  ")).isEqualTo(ChangeConsent.APPLY);
        assertThat(ChangeConsent.readFrom("apply")).isEqualTo(ChangeConsent.APPLY);
        assertThat(ChangeConsent.readFrom("no")).isEqualTo(ChangeConsent.CANCEL);
        assertThat(ChangeConsent.readFrom("modify")).isEqualTo(ChangeConsent.MODIFY);
    }

    /** A word inside another word is not that word. */
    @Test
    public void awordInsideAnotherWordIsNotAnAnswer() {
        assertThat(ChangeConsent.readFrom("yesterday's version was fine"))
                .as("'yesterday' is not 'yes'")
                .isEqualTo(ChangeConsent.UNCLEAR);
        assertThat(ChangeConsent.readFrom("what happens to the notes?"))
                .as("'notes' is not 'no'")
                .isEqualTo(ChangeConsent.UNCLEAR);
    }

    @Test
    public void anAnswerToSomeOtherQuestionIsNotAnAnswerToThisOne() {
        assertThat(ChangeConsent.readFrom("what does the second edit do?"))
                .isEqualTo(ChangeConsent.UNCLEAR);
        assertThat(ChangeConsent.readFrom("")).isEqualTo(ChangeConsent.UNCLEAR);
        assertThat(ChangeConsent.readFrom(null)).isEqualTo(ChangeConsent.UNCLEAR);
    }

    /** Asking for something else is not agreeing to this, and not refusing outright either. */
    @Test
    public void askingForSomethingElseIsItsOwnAnswer() {
        assertThat(ChangeConsent.readFrom("change the second one to use a constant"))
                .isEqualTo(ChangeConsent.MODIFY);
        assertThat(ChangeConsent.readFrom("do it differently"))
                .isEqualTo(ChangeConsent.MODIFY);
    }
}
