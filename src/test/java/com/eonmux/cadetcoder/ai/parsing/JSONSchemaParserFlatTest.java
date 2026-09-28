package com.eonmux.cadetcoder.ai.parsing;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link JSONSchemaParser} robustness to the JSON shapes real models actually emit — flat
 * top-level parameters and file aliases — which previously failed to map (no {@code file_path} was
 * found, yielding an INVALID_PARAMETERS action that the harness mis-executed as raw text).
 */
public class JSONSchemaParserFlatTest {

    private final JSONSchemaParser parser = new JSONSchemaParser();

    /** A context with no command whitelist, so command validity is not the thing under test here. */
    private ParsingContext ctx(String userRequest) {
        return new ParsingContext.Builder(userRequest).build();
    }

    @Test
    public void flatFilesArray_mapsToReadWithFirstPath_atHighConfidence() {
        String response = "ACTION\n{\n  \"action\": \"read\",\n"
                + "  \"files\": [\"README.md\", \"COMMAND_REFERENCE.md\"]\n}";

        ParsedResponse result = parser.parse(response, ctx("read the project docs"));

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getActions()).hasSize(1);
        ParsedAction action = result.getActions().get(0);
        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getFilePath()).isEqualTo("README.md");
        assertThat(action.getValidation()).isEqualTo(ParsedAction.ValidationResult.VALID);
        // A well-formed, valid action must clear the engine's 0.7 acceptance threshold so it is not
        // discarded in favour of a fabricated fuzzy/semantic guess.
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.7);

        // And it converts to a clean single-positional read with no stray "[...]" token.
        com.eonmux.cadetcoder.commands.ChatCommand.AIAction legacy = action.toLegacyAction();
        assertThat(argsOf(legacy)).containsExactly("README.md");
    }

    @Test
    public void commandAlias_andFlatFileScalar_mapToRead() {
        String response = "{\"command\": \"read\", \"file\": \"src/Main.java\"}";

        ParsedResponse result = parser.parse(response, ctx("open main"));

        assertThat(result.isSuccessful()).isTrue();
        ParsedAction action = result.getActions().get(0);
        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getFilePath()).isEqualTo("src/Main.java");
    }

    @Test
    public void flatGrepParameters_arePreserved() {
        String response = "{\"action\": \"grep\", \"pattern\": \"TODO\", \"path\": \"src/\"}";

        ParsedResponse result = parser.parse(response, ctx("find todos"));

        assertThat(result.isSuccessful()).isTrue();
        ParsedAction action = result.getActions().get(0);
        assertThat(action.getCommand()).isEqualTo("grep");
        assertThat(action.getParameter("pattern")).isEqualTo("TODO");
        assertThat(action.getParameter("path")).isEqualTo("src/");
    }

    @Test
    public void nestedParametersStillWork_unchanged() {
        String response = "{\"action\": \"read\", \"parameters\": {\"file_path\": \"a.txt\"},"
                + " \"reasoning\": \"check it\", \"confidence\": 0.95}";

        ParsedResponse result = parser.parse(response, ctx("read a"));

        assertThat(result.isSuccessful()).isTrue();
        ParsedAction action = result.getActions().get(0);
        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getFilePath()).isEqualTo("a.txt");
        assertThat(action.getReasoning()).isEqualTo("check it");
    }

    @Test
    public void nestedFormatWithStrayTopLevelKey_doesNotCorruptContent() throws Exception {
        // Regression guard: a stray top-level field must NOT leak into the positional argument stream
        // and corrupt write content. Only recognised parameter keys are absorbed.
        String response = "{\"action\":\"write\",\"parameters\":{\"file_path\":\"o.txt\","
                + "\"content\":\"hello world\"},\"note\":\"JUNK\"}";

        ParsedResponse result = parser.parse(response, ctx("write the file"));

        ParsedAction action = result.getActions().get(0);
        assertThat(action.getParameters()).doesNotContainKey("note");
        // file path first, then exactly the real content — no "JUNK" token.
        assertThat(argsOf(action.toLegacyAction())).containsExactly("o.txt", "hello world");
    }

    @Test
    public void flatBashCommand_reachesParameters_andIsFlaggedDangerous() {
        // A flat {"action":"bash","command":"rm -rf /"} must surface the command as a parameter so the
        // dangerous-command check can see it (previously "command" was dropped, blinding the validator).
        ParsedResponse result = parser.parse("{\"action\":\"bash\",\"command\":\"rm -rf /\"}", ctx("run it"));

        ParsedAction action = result.getActions().get(0);
        assertThat(action.getCommand()).isEqualTo("bash");
        assertThat(action.getParameter("command")).isEqualTo("rm -rf /");
        assertThat(action.getValidation()).isEqualTo(ParsedAction.ValidationResult.SECURITY_RISK);
    }

    @Test
    public void toolKeyWithSiblingCommand_usesToolAsVerb_commandBecomesParameter() {
        // {"tool":"bash","command":"rm -rf /"} — the action verb is "bash" (from 'tool'), and the sibling
        // "command" is the bash argument, not the action label. The dangerous command stays visible.
        ParsedResponse result = parser.parse("{\"tool\":\"bash\",\"command\":\"rm -rf /\"}", ctx("run it"));

        ParsedAction action = result.getActions().get(0);
        assertThat(action.getCommand()).isEqualTo("bash");
        assertThat(action.getParameter("command")).isEqualTo("rm -rf /");
        assertThat(action.getValidation()).isEqualTo(ParsedAction.ValidationResult.SECURITY_RISK);
    }

    @Test
    public void commandUsedAsVerb_isNotReabsorbedAsAStrayPositional() throws Exception {
        // When 'command' supplies the verb (no 'action'/'tool'), it must not also be emitted as an arg.
        ParsedResponse result = parser.parse("{\"command\":\"read\",\"file\":\"x.java\"}", ctx("open x"));

        ParsedAction action = result.getActions().get(0);
        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(argsOf(action.toLegacyAction())).containsExactly("x.java");
    }

    @Test
    public void emptyFilesList_doesNotProduceABracketPath() throws Exception {
        ParsedResponse result = parser.parse("{\"action\":\"read\",\"files\":[]}", ctx("read nothing"));

        ParsedAction action = result.getActions().get(0);
        assertThat(action.getFilePath()).isNull();
        // No file path -> no positional "[]" token at all.
        assertThat(argsOf(action.toLegacyAction())).isEmpty();
    }

    @Test
    public void grepPatternAsArray_isCoerced_notThrown() throws Exception {
        // A model that emits a JSON array for a scalar field must degrade to the first element, never
        // throw ClassCastException out of toLegacyAction.
        ParsedResponse result = parser.parse("{\"action\":\"grep\",\"pattern\":[\"foo\",\"bar\"]}", ctx("find foo"));

        ParsedAction action = result.getActions().get(0);
        assertThat(argsOf(action.toLegacyAction())).containsExactly("foo");
    }

    private String[] argsOf(com.eonmux.cadetcoder.commands.ChatCommand.AIAction action) throws RuntimeException {
        try {
            java.lang.reflect.Field f =
                    com.eonmux.cadetcoder.commands.ChatCommand.AIAction.class.getDeclaredField("arguments");
            f.setAccessible(true);
            return (String[]) f.get(action);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
