package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * An answer is either accepted or asked again — never complained about and then used anyway.
 *
 * <p>The rejection used to be cosmetic: "Expected yes/no answer, but got: maybe" was printed and
 * "maybe" was returned unchanged. Everything downstream reads anything that is not yes as a
 * refusal, so the user was told their answer was wrong and then had it acted on as the opposite of
 * what they might have meant.</p>
 */
class AnswerValidationTest {

    /** @return the complaint to show before re-asking, or {@code null} when the answer stands */
    private static String reason(String input, String prompt) {
        return new UserAsk(true).rejectionReason(input, prompt);
    }

    @Test
    @DisplayName("A real yes or no is accepted, in any spelling or case")
    void plainAnswersAreAccepted() throws Exception {
        for (String answer : new String[]{"y", "Y", "yes", "YES", "n", "N", "no", "No"}) {
            assertThat(reason(answer, "Do you want to continue? (yes/no)"))
                    .as("'" + answer + "' answers the question")
                    .isNull();
        }
    }

    @Test
    @DisplayName("Anything that is not yes or no is asked again, not quietly taken as a refusal")
    void nonAnswersAreRejected() throws Exception {
        assertThat(reason("maybe", "Do you want to continue? (yes/no)"))
                .isEqualTo("Please answer yes or no");
        assertThat(reason("sure", "Proceed? (y/n)")).isEqualTo("Please answer yes or no");
        assertThat(reason("2", "Should we proceed: yes or no?")).isEqualTo("Please answer yes or no");
    }

    @Test
    @DisplayName("'true' and 'false' are asked again rather than read as a refusal")
    void booleanSpellingsAreNotSilentlyMisread() throws Exception {
        // These used to be accepted, and then read downstream as "not yes" -- so someone typing
        // "true" to agree had it recorded as a denial.
        assertThat(reason("true", "Do you want to continue? (yes/no)"))
                .isEqualTo("Please answer yes or no");
        assertThat(reason("false", "Do you want to continue? (yes/no)"))
                .isEqualTo("Please answer yes or no");
    }

    @Test
    @DisplayName("A question that is not a yes/no is not policed at all")
    void freeTextAnswersAreLeftAlone() throws Exception {
        assertThat(reason("src/main/java/Foo.java", "Which file would you like to edit?")).isNull();
        assertThat(reason("anything at all", "Describe the change you want.")).isNull();
    }

    @Test
    @DisplayName("A numbered choice is not judged against a range this method cannot know")
    void numberedChoicesAreNotRangeChecked() throws Exception {
        // There used to be an accept-1-through-10 rule with no relation to how many options were
        // offered, so an eleventh listed file drew a warning for a perfectly good answer.
        assertThat(reason("11", "Found multiple files. Please specify the file number:")).isNull();
        assertThat(reason("47", "Pick one: option 1 or option 2")).isNull();
        assertThat(reason("1", "Pick one: option 1 or option 2")).isNull();
    }

    @Test
    @DisplayName("A null prompt does not throw")
    void aMissingPromptIsTolerated() throws Exception {
        assertThat(reason("whatever", null)).isNull();
    }
}
