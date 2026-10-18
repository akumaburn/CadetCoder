package com.eonmux.cadetcoder.ai.parsing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reply with an ACTION block runs that block, whatever JSON object it also holds.
 *
 * <h2>The defect</h2>
 *
 * <p>The JSON strategy ran first and took a JSON object from anywhere in the reply. A model that
 * quoted the prompt's forbidden JSON form in its prose, and then wrote the ACTION block the prompt
 * asks for, had the quoted object run and its block ignored. The chat prompt forbids the JSON form,
 * so the ACTION block parser goes first.</p>
 */
class AnActionBlockOutranksAquotedJsonObjectTest {

    private static final String BLOCK =
            "ACTION_START\n"
            + "COMMAND: ls\n"
            + "ARGS: src\n"
            + "REASON: list the sources\n"
            + "ACTION_END\n";

    @Test
    void theblockRunsRatherThanTheQuotedObject() {
        String reply = "The prompt rules out "
                       + "`{\"action\": \"read\", \"file\": \"notes.md\"}`, so:\n"
                       + BLOCK;

        ParsedResponse parsed = new ResponseParsingEngine().parseResponse(reply, "list src");

        assertThat(parsed.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.ACTION_BLOCK);
        assertThat(parsed.getActions()).hasSize(1);
        assertThat(parsed.getActions().get(0).getCommand()).isEqualTo("ls");
    }

    /**
     * A block that fails validation is still the model's request. Its own verdict decides the
     * turn: an unknown command is sent back for a format correction, and the quoted object is
     * not run in its place.
     */
    @Test
    void ablockThatFailsValidationIsStillTheBlock() {
        String reply = "The prompt rules out "
                       + "`{\"action\": \"read\", \"file\": \"notes.md\"}`, so:\n"
                       + BLOCK.replace("COMMAND: ls", "COMMAND: frobnicate");

        ParsedResponse parsed = new ResponseParsingEngine().parseResponse(reply, "list src");

        assertThat(parsed.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.ACTION_BLOCK);
        assertThat(parsed.getActions()).extracting(ParsedAction::getCommand)
                .containsExactly("frobnicate");
    }

    @Test
    void ajsonPayloadInsideTheBlockIsContent() {
        String reply = "ACTION_START\n"
                       + "COMMAND: write\n"
                       + "ARGS: hooks.json\n"
                       + "{\"action\": \"read\", \"file\": \"notes.md\"}\n"
                       + "REASON: save the hook settings\n"
                       + "ACTION_END\n";

        ParsedResponse parsed = new ResponseParsingEngine().parseResponse(reply, "save hooks");

        assertThat(parsed.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.ACTION_BLOCK);
        assertThat(parsed.getActions().get(0).getCommand()).isEqualTo("write");
    }

    @Test
    void areplyThatIsOnlyJsonStillParsesAsJson() {
        String reply = "{\"action\": \"read\", \"parameters\": {\"file_path\": \"notes.md\"}}";

        ParsedResponse parsed = new ResponseParsingEngine().parseResponse(reply, "read notes");

        assertThat(parsed.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.JSON_SCHEMA);
        assertThat(parsed.getActions().get(0).getCommand()).isEqualTo("read");
    }
}
