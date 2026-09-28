package com.eonmux.cadetcoder.ai.parsing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recovery only pays for itself if what it hands back can be parsed.
 *
 * <p>{@code ErrorRecoveryManager} pulled the COMMAND/ARGS/REASON lines out of an ACTION block and
 * interpolated them into a JSON string with {@code String.format}. Nothing was escaped. A double
 * quote is not exotic in an ARGS line -- {@code grep "TODO" --include="*.java"} has four of them,
 * and every write of a Java string literal has two -- and one of them ends the JSON string early,
 * so the recovered response fails to parse and the recovery is thrown away. The user sees the
 * original failure, having spent an extra parse discovering nothing.</p>
 *
 * <p>A backslash does the same thing more quietly: {@code C:\src\Main.java} becomes the escape
 * sequences {@code \s} and {@code \M}, which are not valid JSON escapes at all.</p>
 *
 * <h2>Why these tests changed</h2>
 *
 * <p>A recovered block used to carry its whole argument line under one {@code args} parameter. It
 * now carries the parameters the block's own reader makes of it, which is what the ordinary parse
 * produces for the same block, so what is checked here is that those values survive the round trip
 * rather than that one unread string does.</p>
 */
public class RecoveredJsonIsWellFormedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode recoverAndParse(String response) throws Exception {
        ErrorRecoveryManager.RecoveryResult result = new ErrorRecoveryManager().attemptRecovery(
                response, new ParsingContext.Builder("fix the bug").build(), "no action found");

        assertThat(result.isSuccess()).as("the ACTION block is right there to be extracted").isTrue();
        return MAPPER.readTree(result.getRecoveredResponse());
    }

    @Test
    public void argumentsContainingQuotesStillProduceParseableJson() throws Exception {
        JsonNode recovered = recoverAndParse(
                "ACTION_START\n"
                + "COMMAND: grep\n"
                + "ARGS: \"TODO\" --include=\"*.java\"\n"
                + "REASON: find the leftovers\n"
                + "ACTION_END");

        assertThat(recovered.path("action").asText()).isEqualTo("grep");
        assertThat(recovered.path("parameters").path("pattern").asText())
                .as("the arguments must survive the round trip character for character")
                .isEqualTo("TODO");
        assertThat(recovered.path("parameters").path("include").asText()).isEqualTo("*.java");
    }

    @Test
    public void aBackslashInAPathIsNotReadAsAnEscapeSequence() throws Exception {
        JsonNode recovered = recoverAndParse(
                "ACTION_START\n"
                + "COMMAND: read\n"
                + "ARGS: C:\\src\\Main.java\n"
                + "REASON: inspect it\n"
                + "ACTION_END");

        assertThat(recovered.path("parameters").path("file_path").asText())
                .isEqualTo("C:\\src\\Main.java");
    }

    @Test
    public void aReasonContainingAQuoteSurvives() throws Exception {
        JsonNode recovered = recoverAndParse(
                "ACTION_START\n"
                + "COMMAND: read\n"
                + "ARGS: src/Main.java\n"
                + "REASON: the user asked about \"the main class\"\n"
                + "ACTION_END");

        assertThat(recovered.path("reasoning").asText())
                .isEqualTo("the user asked about \"the main class\"");
    }

    @Test
    public void anActionBlockWithNoArgumentsIsStillWellFormed() throws Exception {
        JsonNode recovered = recoverAndParse(
                "ACTION_START\nCOMMAND: status\nACTION_END");

        assertThat(recovered.path("action").asText()).isEqualTo("status");
        assertThat(recovered.path("confidence").asDouble()).isGreaterThan(0.0);
    }

    /** The inference strategy builds its JSON by hand too, and is fed paths from the user's text. */
    @Test
    public void anInferredActionIsWellFormedJson() throws Exception {
        ErrorRecoveryManager.RecoveryResult result = new ErrorRecoveryManager().attemptRecovery(
                "I could not work out what to do.",
                new ParsingContext.Builder("please read src/Main.java for me").build(),
                "no action found");

        assertThat(result.isSuccess()).isTrue();
        JsonNode recovered = MAPPER.readTree(result.getRecoveredResponse());
        assertThat(recovered.path("action").asText()).isNotBlank();
    }
}
