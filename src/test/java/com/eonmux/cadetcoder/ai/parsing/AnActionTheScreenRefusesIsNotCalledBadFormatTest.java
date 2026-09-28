package com.eonmux.cadetcoder.ai.parsing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A well-formed action that the safety screen refuses is reported as refused.
 *
 * <h2>The defect</h2>
 *
 * <p>The screen did its job and produced a result saying which action it blocked and why. The
 * engine then saw a result that was not a success and handed the reply to error recovery, which
 * looked for an action format, found one it had already rejected, and replaced the refusal with
 * "No structured action format (JSON / ACTION block / XML) found in the response". The reason was
 * gone. The model was told its format was wrong, sent back the same correct block, was told again,
 * and the run ended with "AI failed to provide proper ACTION format after 2 attempts" printed above
 * a perfectly formed ACTION block.</p>
 *
 * <p>Recovery repairs a reply that could not be read. A reply that was read and refused needs no
 * repair, and whatever recovery made of it would be refused for the same reason.</p>
 */
class AnActionTheScreenRefusesIsNotCalledBadFormatTest {

    private static final String REFUSED =
            "ACTION_START\n"
            + "COMMAND: bash\n"
            + "ARGS: rm .commandcode/taste/taste.md\n"
            + "REASON: Delete the tooling file that stage-all committed\n"
            + "ACTION_END";

    @Test
    void theresultSaysTheActionWasRefusedAndWhy() {
        ParsedResponse parsed = new ResponseParsingEngine().parseResponse(REFUSED, "tidy the commit");

        assertThat(parsed.getResult()).isEqualTo(ParsedResponse.ParseResult.SECURITY_ERROR);
        assertThat(ResponseParsingEngine.refusedActions(parsed))
                .as("an action was read and refused, which a reply refused as a whole is not")
                .isTrue();
        assertThat(String.join("; ", parsed.getErrors()))
                .contains("rm")
                .doesNotContain("No structured action format");
        assertThat(parsed.getActions()).as("nothing refused is handed on to be run").isEmpty();
    }

    @Test
    void areplyRefusedAsAwholeIsNotReportedAsArefusedAction() {
        String answer = "SUCCESS: I added a new instruction to the README for the setup step.";

        ParsedResponse parsed = new ResponseParsingEngine().parseResponse(answer, "document setup");

        assertThat(ResponseParsingEngine.refusedActions(parsed))
                .as("there was no action in it to refuse")
                .isFalse();
    }

    @Test
    void thesameBlockWithAnAllowedCommandStillParses() {
        String allowed = REFUSED.replace("ARGS: rm ", "ARGS: git rm --cached ");

        ParsedResponse parsed = new ResponseParsingEngine().parseResponse(allowed, "tidy the commit");

        assertThat(parsed.isSuccessful()).isTrue();
        assertThat(parsed.getActions()).hasSize(1);
    }
}
