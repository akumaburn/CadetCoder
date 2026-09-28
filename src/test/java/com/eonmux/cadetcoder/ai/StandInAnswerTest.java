package com.eonmux.cadetcoder.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How the model's answer to a question asked for the user is read. */
class StandInAnswerTest {

    @Test
    void aconfirmationIsAnsweredYesOrNoWithItsReason() {
        StandInAnswer.Answer answer =
                StandInAnswer.read("**ANSWER:** Yes.\nBECAUSE: it edits one line.", true).orElseThrow();

        assertThat(answer.text()).isEqualTo("yes");
        assertThat(answer.reason()).isEqualTo("it edits one line.");
    }

    @Test
    void aconfirmationAnsweredWithAnythingElseHasNoAnswer() {
        assertThat(StandInAnswer.read("ANSWER: probably\nBECAUSE: unsure", true)).isEmpty();
    }

    @Test
    void ananswerThatIsNotSaidFirstIsNotRead() {
        assertThat(StandInAnswer.read("Let me think.\nANSWER: yes", true)).isEmpty();
    }

    @Test
    void aquestionThatIsNotAconfirmationTakesTheAnswerAsGiven() {
        assertThat(StandInAnswer.read("ANSWER: 2\nBECAUSE: the source file", false).orElseThrow()
                                .text()).isEqualTo("2");
    }
}
