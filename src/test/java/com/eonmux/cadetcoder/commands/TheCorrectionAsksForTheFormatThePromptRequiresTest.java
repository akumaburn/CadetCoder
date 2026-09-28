package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a model is told after a parse failure is what it was told to do in the first place.
 *
 * <p><b>The defect</b>: the chat system prompt is unambiguous -- "ONLY use ACTION_START/ACTION_END
 * format", and it lists {@code {"action": "read", "file": "path"}} among the formats that "DO NOT
 * USE". The correction sent when a reply could not be parsed opened with "Please use this EXACT
 * JSON format", showed JSON, and offered the ACTION block as the "Alternative". So the one moment
 * the model is being corrected was the moment it was told to switch to the format its instructions
 * forbid -- and a model that complied was told off again by the next correction.</p>
 */
public class TheCorrectionAsksForTheFormatThePromptRequiresTest {

    private static String hint() {
        return ChatActions.formatHint(
                new ParsedResponse.Builder(ParsedResponse.ParseResult.FORMAT_ERROR,
                                           ParsedResponse.ParsingStrategy.FUZZY_PARSING,
                                           "I will read the file now.")
                        .addError("no ACTION block found")
                        .build());
    }

    @Test
    public void theCorrectionShowsTheBlockTheSystemPromptAsksFor() {
        assertThat(hint())
                .contains("ACTION_START")
                .contains("COMMAND:")
                .contains("ARGS:")
                .contains("REASON:")
                .contains("ACTION_END");
    }

    @Test
    public void theCorrectionDoesNotAskForAFormatThePromptForbids() {
        assertThat(hint())
                .as("the prompt lists the JSON object among the formats that DO NOT USE")
                .doesNotContain("JSON format")
                .doesNotContain("\"action\"");
    }

    /** A reply with neither an action nor a completion is the failure; both ways out are named. */
    @Test
    public void theCorrectionNamesTheOtherReplyThatIsAccepted() {
        assertThat(hint()).contains("SUCCESS:");
    }

    /** What the parser tried and what it objected to still travels with the correction. */
    @Test
    public void theCorrectionStillSaysWhatWentWrong() {
        assertThat(hint())
                .contains("no ACTION block found")
                .contains("FUZZY_PARSING");
    }
}
