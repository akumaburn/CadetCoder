package com.eonmux.cadetcoder.ai.parsing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recovery strategy is remembered as the cure for an error only once its output was used.
 *
 * <p><b>The defect</b>: the manager learnt from a strategy the moment it produced text. Producing
 * text is not recovering: whether the text parses into something worth running is decided by the
 * caller afterwards. So a strategy whose output was then thrown away as too low-confidence was
 * recorded as the cure -- and the last-resort strategy, which always produces something and whose
 * confidence can never be accepted, was recorded for every error that reached it. The statistics
 * could report a hundred per cent recovery rate having recovered nothing.</p>
 */
class ArecoveryIsLearntWhenItIsAcceptedTest {

    private static final String AN_ERROR = "No ACTION block found in response";

    @Test
    void producingTextIsNotYetArecovery() {
        ErrorRecoveryManager manager = new ErrorRecoveryManager();

        manager.attemptRecovery("just some prose about the file",
                                new ParsingContext.Builder("do the work").build(), AN_ERROR);

        assertThat(manager.getStatistics().getSuccessfulRecoveries())
                .as("nothing has accepted anything yet")
                .isEmpty();
    }

    @Test
    void whatWasAcceptedIsWhatIsRemembered() {
        ErrorRecoveryManager manager = new ErrorRecoveryManager();

        manager.attemptRecovery("just some prose about the file",
                                new ParsingContext.Builder("do the work").build(), AN_ERROR);
        manager.recordAccepted(AN_ERROR, "StructuredBlockRepair");

        assertThat(manager.getStatistics().getSuccessfulRecoveries())
                .containsValue("StructuredBlockRepair");
    }

    @Test
    void theErrorIsStillCountedEvenWhenNothingWasAccepted() {
        ErrorRecoveryManager manager = new ErrorRecoveryManager();

        manager.attemptRecovery("just some prose about the file",
                                new ParsingContext.Builder("do the work").build(), AN_ERROR);

        assertThat(manager.getStatistics().getErrorPatterns())
                .as("what went wrong is worth knowing whether or not it was fixed")
                .isNotEmpty();
    }
}
