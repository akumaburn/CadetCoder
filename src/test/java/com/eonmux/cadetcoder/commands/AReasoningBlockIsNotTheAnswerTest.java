package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When an action fails the model is asked whether to retry it, skip it, or stop, and the answer is
 * read from what it decided rather than from what it was weighing up.
 *
 * <h2>The defect</h2>
 *
 * <p>Some models emit their deliberation in a {@code <think>} block before answering, and that block
 * discusses all three choices by name. The strip that was meant to remove it was written as
 * {@code replaceAll("<think>.*?</think>", "")}, where {@code .} does not match a line terminator --
 * and a reasoning block is almost always several lines long. So the block survived the strip, the
 * keyword scan ran over it, and the choice taken was whichever of "retry", "skip" or "stop" the
 * model happened to mention first while thinking. A model that reasoned "retrying this would just
 * fail again, so skip it" was made to retry.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>A multi-line reasoning block is removed before the answer is read, so the decision comes from
 * the model's conclusion; the words are still found inside an ordinary sentence, because a model
 * asked a three-way question answers in prose; and a reply that is none of the three is passed
 * through unchanged, so the caller can tell "not understood" from a real choice.</p>
 */
public class AReasoningBlockIsNotTheAnswerTest {

    @Test
    public void aMultiLineReasoningBlockDoesNotDecideTheAnswer() {
        String reply = "<think>\n"
                       + "Retrying this would just fail again for the same reason.\n"
                       + "The file genuinely is not there, so a stop would be premature.\n"
                       + "</think>\n"
                       + "skip";

        assertThat(ChatFollowUp.choiceIn(reply)).isEqualTo("skip");
    }

    @Test
    public void aSingleLineReasoningBlockDoesNotDecideItEither() {
        assertThat(ChatFollowUp.choiceIn("<think>retry or stop?</think> skip")).isEqualTo("skip");
    }

    @Test
    public void theChoiceIsFoundInsideAnOrdinarySentence() {
        assertThat(ChatFollowUp.choiceIn("I think we should retry that one."))
                .isEqualTo("retry");
        assertThat(ChatFollowUp.choiceIn("Let's STOP here."))
                .isEqualTo("stop");
    }

    @Test
    public void aReplyThatIsNoneOfTheThreeIsNotTurnedIntoOne() {
        assertThat(ChatFollowUp.choiceIn("I am not sure what happened"))
                .isEqualTo("i am not sure what happened");
    }

    @Test
    public void aReplyThatIsOnlyReasoningLeavesNothingToChoose() {
        assertThat(ChatFollowUp.choiceIn("<think>\nretry\nskip\nstop\n</think>")).isEmpty();
    }
}
