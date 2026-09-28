package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * End-to-end coverage of {@link ResponseParsingEngine}: the strategy SELECTION loop plus the argument
 * round trip, exercised the way the harness does it - through {@code parseResponse}, against the real
 * command registry (so command validation is realistic) and all the way out to the CLI arguments
 * {@link ParsedAction#toLegacyAction()} emits.
 *
 * <p>The individual parsers had unit tests, but nothing covered the loop that chooses BETWEEN them.
 * That loop unconditionally replaced its stored result with any later success, and both fallback
 * parsers accept any non-empty string - so a valid structured block was routinely overwritten by a
 * guess. The most dangerous instance is pinned first: an ACTION block missing only its {@code REASON:}
 * line used to come back as {@code bash} with a shell string synthesised from the model's prose.</p>
 */
public class ResponseParsingEngineTest {

    private ResponseParsingEngine engine;

    @Before
    public void setUp() {
        System.setProperty("cadet.test.mode", "true");
        engine = new ResponseParsingEngine();
    }

    @After
    public void tearDown() {
        System.clearProperty("cadet.test.mode");
    }

    private String[] argsOf(ChatCommand.AIAction action) throws Exception {
        Field field = ChatCommand.AIAction.class.getDeclaredField("arguments");
        field.setAccessible(true);
        return (String[]) field.get(action);
    }

    private String commandOf(ChatCommand.AIAction action) throws Exception {
        Field field = ChatCommand.AIAction.class.getDeclaredField("command");
        field.setAccessible(true);
        return (String) field.get(action);
    }

    /** Parses an ACTION block with the given command and ARGS payload (no REASON unless supplied). */
    private ParsedResponse parseBlock(String command, String argsPayload, String reason) {
        StringBuilder block = new StringBuilder();
        block.append("ACTION_START\n")
             .append("COMMAND: ").append(command).append("\n")
             .append("ARGS: ").append(argsPayload).append("\n");
        if (reason != null) {
            block.append("REASON: ").append(reason).append("\n");
        }
        block.append("ACTION_END");
        return engine.parseResponse(block.toString(), "work on the project");
    }

    /** The single action's dispatched CLI arguments. */
    private List<String> argvOf(ParsedResponse response) throws Exception {
        assertThat(response.getActions()).hasSize(1);
        return Arrays.asList(argsOf(response.getActions().get(0).toLegacyAction()));
    }

    // ---------------------------------------------------------------- selection

    @Test
    public void reasonlessActionBlock_staysAnActionBlockRead_neverBecomesBash() throws Exception {
        String response = "I need to look at the file first.\n"
                + "ACTION_START\n"
                + "COMMAND: read\n"
                + "ARGS: a.java\n"
                + "ACTION_END";

        ParsedResponse result = engine.parseResponse(response, "explain a.java");

        // Previously: SEMANTIC_PARSING at exactly 0.7 with command 'bash' and a synthesised shell
        // string (": read\nARGS: a.java\nACTION_END") - a read request silently became a shell
        // execution. A structured block that parsed into actions is now authoritative.
        assertThat(result.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.ACTION_BLOCK);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(ResponseParsingEngine.isFallbackOnly(result)).isFalse();
        assertThat(result.getActions()).hasSize(1);

        ParsedAction action = result.getActions().get(0);
        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(commandOf(action.toLegacyAction())).isEqualTo("read");
        assertThat(argsOf(action.toLegacyAction())).containsExactly("a.java");

        // A missing REASON is cosmetic: the block must still clear the engine's acceptance threshold.
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    public void fencedJsonWithNestedParameters_parsesAsJsonSchema() throws Exception {
        String response = "Sure, here is the action:\n"
                + "```json\n"
                + "{\n"
                + "  \"action\": \"read\",\n"
                + "  \"parameters\": {\"file_path\": \"a.java\", \"limit\": 50},\n"
                + "  \"reasoning\": \"Read the file\",\n"
                + "  \"confidence\": 0.95\n"
                + "}\n"
                + "```\n";

        ParsedResponse result = engine.parseResponse(response, "read a.java");

        // The old fenced/inline JSON regexes stopped at the first '}', so the documented shape - which
        // NESTS a parameters object - never matched and the response degraded to the fuzzy parser.
        assertThat(result.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.JSON_SCHEMA);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getActions().get(0).getCommand()).isEqualTo("read");
        assertThat(result.getActions().get(0).getFilePath()).isEqualTo("a.java");
        assertThat(argvOf(result)).containsExactly("a.java", "--limit", "50");
    }

    @Test
    public void jsonWithBracesInsideStringValues_isExtractedWhole() throws Exception {
        String response = "{\"action\": \"write\", \"parameters\": "
                + "{\"file_path\": \"out.json\", \"content\": \"{ \\\"nested\\\": true }\"}}";

        ParsedResponse result = engine.parseResponse(response, "write a json file");

        assertThat(result.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.JSON_SCHEMA);
        assertThat(argvOf(result)).containsExactly("out.json", "{ \"nested\": true }");
    }

    @Test
    public void jsonAfterAnUnclosedBraceInProse_isStillFound() throws Exception {
        String response = "The guard reads: if (session != null) {\n"
                + "Anyway, here is the action:\n"
                + "{\"action\": \"read\", \"parameters\": {\"file_path\": \"a.java\"}}";

        ParsedResponse result = engine.parseResponse(response, "read a.java");

        assertThat(result.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.JSON_SCHEMA);
        assertThat(argvOf(result)).containsExactly("a.java");
    }

    @Test
    public void plainProse_isReportedAsFormatErrorNotSilentSuccess() {
        String response = "The AIManager class is a singleton that manages the AI backends.";

        ParsedResponse result = engine.parseResponse(response, "explain AIManager");

        // "The model answered" and "the parse failed" must be distinguishable: only fallback parsers
        // matched, so this is a FORMAT error the caller re-prompts for - not a success whose invented
        // action gets executed.
        assertThat(ResponseParsingEngine.isFallbackOnly(result)).isTrue();
        assertThat(result.getResult()).isEqualTo(ParsedResponse.ParseResult.FORMAT_ERROR);
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrors())
                .anyMatch(e -> e.contains("No structured action format"));
    }

    @Test
    public void unparseableResponse_doesNotYieldAFabricatedAction() {
        // Error recovery's last-resort strategy always "succeeds" by emitting a parameterless
        // {"action":"read"}. That is a fabrication, not the model's request, and must not be executed.
        ParsedResponse result = engine.parseResponse("I'm not sure what to do with that request.",
                                                     "do something weird");

        assertThat(ResponseParsingEngine.isFallbackOnly(result)).isTrue();
        assertThat(result.isSuccessful()).isFalse();
    }

    @Test
    public void actionBlockWithMissingParameters_scoresBelowTheAcceptanceThreshold() {
        ParsedResponse result = parseBlock("read", "", "Read something");

        // Derived from the validation result, as the JSON parser already does: no file path means
        // INVALID_PARAMETERS, which must not score like a usable action.
        assertThat(result.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.ACTION_BLOCK);
        assertThat(result.getActions().get(0).getValidation())
                .isEqualTo(ParsedAction.ValidationResult.INVALID_PARAMETERS);
        assertThat(result.getConfidence()).isLessThan(0.7);
    }

    @Test
    public void unknownCommandBlock_scoresBelowTheAcceptanceThreshold() {
        ParsedResponse result = parseBlock("frobnicate", "everything", "Frobnicate it");

        assertThat(result.getActions().get(0).getValidation())
                .isEqualTo(ParsedAction.ValidationResult.INVALID_COMMAND);
        assertThat(result.getConfidence()).isLessThan(0.7);
    }

    // ---------------------------------------------------------------- argv round trip

    @Test
    public void write_roundTripsPathAndQuotedContent() throws Exception {
        ParsedResponse result = parseBlock("write", "out.txt \"hello world\"", "Create the file");

        // Previously the content landed under "limit" and was dropped, so every write was rejected
        // with "write command requires at least two arguments".
        assertThat(result.getActions().get(0).getCommand()).isEqualTo("write");
        assertThat(argvOf(result)).containsExactly("out.txt", "hello world");
    }

    @Test
    public void write_roundTripsMultiLineContentAndForceFlag() throws Exception {
        String response = "ACTION_START\n"
                + "COMMAND: write\n"
                + "ARGS: notes.md -f\n"
                + "first line\n"
                + "second line\n"
                + "REASON: Save the notes\n"
                + "ACTION_END";

        ParsedResponse result = engine.parseResponse(response, "write the notes down");

        assertThat(result.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.ACTION_BLOCK);
        assertThat(argvOf(result)).containsExactly("notes.md", "first line\nsecond line", "-f");
    }

    @Test
    public void write_roundTripsDelimitedContentContainingLabelLines() throws Exception {
        String response = "ACTION_START\n"
                + "COMMAND: write\n"
                + "ARGS_BEGIN\n"
                + "doc.md\n"
                + "REASON: this line is content, not a label\n"
                + "ARGS_END\n"
                + "REASON: Save the document\n"
                + "ACTION_END";

        ParsedResponse result = engine.parseResponse(response, "write the document");

        assertThat(argvOf(result))
                .containsExactly("doc.md", "REASON: this line is content, not a label");
    }

    @Test
    public void read_roundTripsLimitInBothFlagSpellings() throws Exception {
        assertThat(argvOf(parseBlock("read", "Foo.java --limit=50", "Read the head")))
                .containsExactly("Foo.java", "--limit", "50");
        assertThat(argvOf(parseBlock("read", "Foo.java --limit 50", "Read the head")))
                .containsExactly("Foo.java", "--limit", "50");
        assertThat(argvOf(parseBlock("read", "Foo.java --offset 10 --limit 5", "Read a range")))
                .containsExactly("Foo.java", "--limit", "5", "--offset", "10");
    }

    @Test
    public void grep_roundTripsRegexPatternAndInclude() throws Exception {
        ParsedResponse result = parseBlock("grep", "\"class .*Manager\" --include=*.java --line-number",
                                           "Find the manager classes");

        // The PATTERN is argv[0] - it is not a path, and nothing may turn a flag into one.
        List<String> argv = argvOf(result);
        assertThat(argv.get(0)).isEqualTo("class .*Manager");
        assertThat(argv).contains("--include=*.java", "--line-number");
        assertThat(argv).noneMatch(arg -> arg.startsWith("--path="));
    }

    @Test
    public void multiedit_roundTripsMultiLineEditBlock() throws Exception {
        String response = "ACTION_START\n"
                + "COMMAND: multiedit\n"
                + "ARGS: Sample.java\n"
                + "EDIT_START\n"
                + "OLD: int timeout = 30;\n"
                + "NEW: int timeout = 60;\n"
                + "EDIT_END\n"
                + "REASON: Raise the timeout\n"
                + "ACTION_END";

        ParsedResponse result = engine.parseResponse(response, "raise the timeout");

        // Previously the edits key was absent entirely: the multi-line payload was dropped.
        assertThat(argvOf(result)).containsExactly(
                "Sample.java",
                "EDIT_START\nOLD: int timeout = 30;\nNEW: int timeout = 60;\nEDIT_END");
    }

    @Test
    public void adjacentBlocks_areNotMergedByTheMultiLineArgsPayload() throws Exception {
        // A block missing its REASON followed by a complete one: the multi-line ARGS payload must stop
        // at ACTION_END, never swallow the following block's body.
        String response = "ACTION_START\n"
                + "COMMAND: read\n"
                + "ARGS: a.java\n"
                + "ACTION_END\n"
                + "ACTION_START\n"
                + "COMMAND: write\n"
                + "ARGS: b.txt hi\n"
                + "REASON: Write it\n"
                + "ACTION_END";

        ParsedResponse result = engine.parseResponse(response, "do two things");

        assertThat(result.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.ACTION_BLOCK);
        for (ParsedAction action : result.getActions()) {
            assertThat(action.getParameters().toString()).doesNotContain("ACTION_END");
        }
        assertThat(argsOf(result.getActions().get(0).toLegacyAction()))
                .doesNotContain("ACTION_START");
    }

    // ---------------------------------------------------------------- resilience

    @Test
    public void arrayValuedBashCommand_doesNotThrowAndKeepsTheWholeCommandLine() throws Exception {
        String response = "{\"action\":\"bash\",\"command\":[\"ls\",\"-la\"]}";

        Throwable thrown = catchThrowable(() -> engine.parseResponse(response, "list the files"));

        // A JSON array became a List, and the security validator hard-cast it to String: the
        // ClassCastException escaped the whole parse and tripped the circuit breaker.
        assertThat(thrown).isNull();

        ParsedResponse result = engine.parseResponse(response, "list the files");
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getActions().get(0).getCommand()).isEqualTo("bash");
        // The argv form means ONE command line; keeping only "ls" would run a different command.
        assertThat(argvOf(result)).containsExactly("ls -la");
    }

    @Test
    public void arrayValuedBashCommand_doesNotTripTheCircuitBreaker() {
        String response = "{\"action\":\"bash\",\"command\":[\"ls\",\"-la\"]}";
        for (int i = 0; i < 6; i++) {
            engine.parseResponse(response, "list the files");
        }

        // Six consecutive failures would have opened the circuit (threshold 5); a later, perfectly
        // ordinary response must still parse.
        ParsedResponse result = parseBlock("read", "a.java", "Read the file");
        assertThat(result.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.ACTION_BLOCK);
        assertThat(result.isSuccessful()).isTrue();
    }
}
