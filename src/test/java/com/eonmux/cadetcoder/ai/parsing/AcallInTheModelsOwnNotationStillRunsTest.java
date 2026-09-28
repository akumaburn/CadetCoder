package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool call written in the model's own notation runs the command it names.
 *
 * <h2>Why the notation the prompt asks for is not the only one read</h2>
 *
 * <p>The system prompt asks for an {@code ACTION_START}/{@code ACTION_END} block. A model that was
 * trained to call tools reaches past it for the notation its own vendor taught it, most often on
 * the first turn of a session and after a long transcript. Nothing read those notations, so a reply
 * that named the command, named its arguments and even declared the type of each argument was
 * refused as a format error, re-prompted twice, and the run ended with
 * {@code AI failed to provide proper ACTION format after 2 attempts}. Every notation here is one a
 * vendor documents, and each says what to run at least as precisely as the block does.</p>
 */
public class AcallInTheModelsOwnNotationStillRunsTest {

    private final ToolCallParser parser = new ToolCallParser();

    /** The context a real run hands the parser: it knows which commands exist. */
    private ParsingContext context() {
        return new ParsingContext.Builder("read the main class")
                .setAvailableCommands(new CommandRegistry().getCommands().keySet())
                .build();
    }

    private ParsedResponse parse(String response) {
        return parser.parse(response, context());
    }

    /** The first action a reply parses to, which must have parsed at all. */
    private ParsedAction onlyAction(String response) {
        ParsedResponse parsed = parse(response);
        assertThat(parsed.getActions()).as("the reply names a command outright").isNotEmpty();
        return parsed.getActions().get(0);
    }

    private List<String> dispatchedArguments(ParsedAction action) throws Exception {
        ChatCommand.AIAction legacy    = action.toLegacyAction();
        Field                arguments = ChatCommand.AIAction.class.getDeclaredField("arguments");
        arguments.setAccessible(true);
        return Arrays.asList((String[]) arguments.get(legacy));
    }

    @Test
    public void adsmlCallNamesTheCommandAndItsArguments() {
        ParsedAction action = onlyAction(
                "<｜DSML｜ calls>\n"
                + "<｜DSML｜ invoke name=\"read\">\n"
                + "<｜DSML｜ parameter name=\"file_path\" string=\"true\">src/Main.java"
                + "</｜DSML｜ parameter>\n"
                + "</｜DSML｜ invoke>\n"
                + "</｜DSML｜ calls>");

        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getStringParameter("file_path")).isEqualTo("src/Main.java");
        assertThat(action.getValidation()).isEqualTo(ParsedAction.ValidationResult.VALID);
    }

    /**
     * The declaration is the only thing that separates a count from a path made of digits, so a
     * parameter that says it is JSON is read as JSON and one that says it is a string is not.
     */
    @Test
    public void adsmlParameterIsReadAsTheTypeItDeclares() {
        ParsedAction action = onlyAction(
                "<｜DSML｜ invoke name=\"read\">\n"
                + "<｜DSML｜ parameter name=\"file_path\" string=\"true\">2024"
                + "</｜DSML｜ parameter>\n"
                + "<｜DSML｜ parameter name=\"limit\" string=\"false\">50"
                + "</｜DSML｜ parameter>\n"
                + "</｜DSML｜ invoke>");

        assertThat(action.getParameter("file_path")).isEqualTo("2024");
        assertThat(action.getParameter("limit")).isEqualTo(50);
    }

    /** V4 wrote the tags without the space that V4.1 added, and both spellings are in use. */
    @Test
    public void thedsmlTagsAreReadWithOrWithoutTheSpaceThatV41Added() {
        ParsedAction action = onlyAction(
                "<｜DSML｜tool_calls>\n"
                + "<｜DSML｜invoke name=\"ls\">\n"
                + "</｜DSML｜invoke>\n"
                + "</｜DSML｜tool_calls>");

        assertThat(action.getCommand()).isEqualTo("ls");
    }

    /**
     * A reply that has been through a gateway, a log or a terminal that cannot render the fullwidth
     * bar arrives with the ASCII one. The call it carries is unchanged.
     */
    @Test
    public void adsmlCallSurvivesTheBarBeingWrittenInAscii() {
        ParsedAction action = onlyAction(
                "<|DSML| invoke name=\"read\">\n"
                + "<|DSML| parameter name=\"file_path\">src/Main.java</|DSML| parameter>\n"
                + "</|DSML| invoke>");

        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getStringParameter("file_path")).isEqualTo("src/Main.java");
    }

    /** DeepSeek namespaces a tool as {@code namespace::tool}; the namespace is not the name. */
    @Test
    public void anamespacedToolNameDispatchesUnderTheToolsOwnName() {
        assertThat(onlyAction(
                "<｜DSML｜ invoke name=\"files::read\">\n"
                + "<｜DSML｜ parameter name=\"file_path\">src/Main.java"
                + "</｜DSML｜ parameter>\n"
                + "</｜DSML｜ invoke>").getCommand())
                .isEqualTo("read");
    }

    /**
     * The notation DSML replaced, still what V3 and R1 write. Its arguments come in a fenced block.
     */
    @Test
    public void adeepSeekTokenCallNamesTheCommandAndItsArguments() {
        ParsedAction action = onlyAction(
                "<\uFF5Ctool\u2581calls\u2581begin\uFF5C><\uFF5Ctool\u2581call\u2581begin\uFF5C>"
                + "function<\uFF5Ctool\u2581sep\uFF5C>read\n```json\n"
                + "{\"file_path\": \"src/Main.java\"}\n```"
                + "<\uFF5Ctool\u2581call\u2581end\uFF5C><\uFF5Ctool\u2581calls\u2581end\uFF5C>");

        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getStringParameter("file_path")).isEqualTo("src/Main.java");
        assertThat(action.getValidation()).isEqualTo(ParsedAction.ValidationResult.VALID);
    }

    /** Two calls in one block, with the second separated only by its own opening token. */
    @Test
    public void severalDeepSeekTokenCallsAreSeveralActions() {
        ParsedResponse parsed = parse(
                "<\uFF5Ctool\u2581calls\u2581begin\uFF5C><\uFF5Ctool\u2581call\u2581begin\uFF5C>"
                + "function<\uFF5Ctool\u2581sep\uFF5C>read\n```json\n{\"file_path\": \"a.txt\"}\n```"
                + "<\uFF5Ctool\u2581call\u2581end\uFF5C>\n"
                + "<\uFF5Ctool\u2581call\u2581begin\uFF5C>function<\uFF5Ctool\u2581sep\uFF5C>ls"
                + "\n```json\n{}\n```<\uFF5Ctool\u2581call\u2581end\uFF5C>"
                + "<\uFF5Ctool\u2581calls\u2581end\uFF5C>");

        assertThat(parsed.getActions()).hasSize(2);
        assertThat(parsed.getActions().get(0).getCommand()).isEqualTo("read");
        assertThat(parsed.getActions().get(1).getCommand()).isEqualTo("ls");
    }

    /** Cohere names the tool under {@code tool_name}, and means the same thing by it. */
    @Test
    public void acallThatNamesItsToolUnderToolNameIsStillAcall() {
        assertThat(onlyAction(
                "<tool_call>{\"tool_name\": \"ls\", \"parameters\": {}}</tool_call>")
                .getCommand())
                .isEqualTo("ls");
    }

    @Test
    public void aninvokeTagCallNamesTheCommandAndItsArguments() {
        ParsedAction action = onlyAction(
                "<function_calls>\n"
                + "<invoke name=\"read\">\n"
                + "<parameter name=\"file_path\">src/Main.java</parameter>\n"
                + "</invoke>\n"
                + "</function_calls>");

        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getStringParameter("file_path")).isEqualTo("src/Main.java");
    }

    /** The invocation says what to run; the block around it only groups a batch of them. */
    @Test
    public void aninvokeTagCallIsReadWithoutTheBlockThatUsuallyWrapsIt() {
        assertThat(onlyAction("<invoke name=\"ls\">\n</invoke>").getCommand())
                .isEqualTo("ls");
    }

    @Test
    public void ajsonToolCallBehindItsMarkerNamesTheCommand() {
        ParsedAction action = onlyAction(
                "<tool_call>\n{\"name\": \"read\", \"arguments\": {\"file_path\": \"src/Main.java\"}}\n"
                + "</tool_call>");

        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getStringParameter("file_path")).isEqualTo("src/Main.java");
    }

    @Test
    public void amistralToolCallsArrayIsRead() {
        ParsedAction action = onlyAction(
                "[TOOL_CALLS] [{\"name\": \"read\", \"arguments\": {\"file_path\": \"src/Main.java\"}}]");

        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getStringParameter("file_path")).isEqualTo("src/Main.java");
    }

    @Test
    public void allamaPythonTagCallIsRead() {
        ParsedAction action = onlyAction(
                "<|python_tag|>{\"name\": \"read\", \"parameters\": {\"file_path\": \"src/Main.java\"}}");

        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getStringParameter("file_path")).isEqualTo("src/Main.java");
    }

    /** The Harmony notation addresses the tool in the channel header, not in the body. */
    @Test
    public void aharmonyCallIsAddressedToTheToolItRuns() {
        ParsedAction action = onlyAction(
                "<|start|>assistant<|channel|>commentary to=functions.read <|constrain|>json"
                + "<|message|>{\"file_path\": \"src/Main.java\"}<|call|>");

        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getStringParameter("file_path")).isEqualTo("src/Main.java");
    }

    /**
     * A server that stops the model on the terminator strips it, so the last call in a reply
     * frequently has none. Insisting on it read no call at all from a complete reply.
     */
    @Test
    public void aharmonyCallIsStillReadWhenItsTerminatorWasStripped() {
        assertThat(onlyAction("<|channel|>commentary to=functions.ls<|message|>{}")
                .getCommand())
                .isEqualTo("ls");
    }

    /** The OpenAI wire sends the arguments as a string holding JSON, not as an object. */
    @Test
    public void argumentsSentAsAjsonStringAreStillRead() {
        ParsedAction action = onlyAction(
                "{\"tool_calls\": [{\"type\": \"function\", \"function\": {\"name\": \"read\","
                + " \"arguments\": \"{\\\"file_path\\\": \\\"src/Main.java\\\"}\"}}]}");

        assertThat(action.getCommand()).isEqualTo("read");
        assertThat(action.getStringParameter("file_path")).isEqualTo("src/Main.java");
    }

    /**
     * The catalogue teaches {@code job start} as the name of a thing to do, so a model writing a
     * tool call names the tool that way. No command has a space in its name.
     */
    @Test
    public void atoolNamedForAcommandAndItsSubcommandSplitsIntoBoth() throws Exception {
        ParsedAction action = onlyAction(
                "<tool_call>{\"name\": \"job start\", \"arguments\":"
                + " {\"command\": \"mvn -o test\", \"description\": \"the full suite\"}}</tool_call>");

        assertThat(action.getCommand()).isEqualTo("job");
        assertThat(dispatchedArguments(action))
                .as("the description is this command's own option and precedes what it runs")
                .containsExactly("start", "-d", "the full suite", "mvn -o test");
    }

    /** The same name written the way an identifier is written, which is the commoner spelling. */
    @Test
    public void asubcommandJoinedByAnUnderscoreSplitsTheSameWay() {
        assertThat(onlyAction("<tool_call>{\"name\": \"job_list\", \"arguments\": {}}</tool_call>")
                .getCommand())
                .isEqualTo("job");
    }

    /**
     * A tool name is written the way an identifier is written, so a one-word command is offered as
     * two. Joining is only done when it names a command that exists.
     */
    @Test
    public void atoolNamedTheWayAnidentifierIsWrittenStillFindsItsCommand() {
        assertThat(onlyAction(
                "<tool_call>{\"name\": \"web_search\", \"arguments\": {\"query\": \"jgit\"}}"
                + "</tool_call>").getCommand())
                .isEqualTo("websearch");
        assertThat(onlyAction(
                "<tool_call>{\"name\": \"notebook-read\", \"arguments\":"
                + " {\"file_path\": \"a.ipynb\"}}</tool_call>").getCommand())
                .isEqualTo("notebookread");
    }

    /** A command whose own name holds a separator must not be split at it. */
    @Test
    public void acommandWhoseNameHoldsAseparatorIsLeftWhole() {
        assertThat(onlyAction(
                "<tool_call>{\"name\": \"todowrite\", \"arguments\": {\"todos\": []}}</tool_call>")
                .getCommand())
                .isEqualTo("todowrite");
    }

    /**
     * A JSON document is not a tool call. Asking a model to write a {@code package.json} produced a
     * reply whose top-level {@code name} would have been run as a command.
     */
    @Test
    public void ajsonAnswerThatMerelyHasAnameIsNotAcall() {
        String packageJson = "{\"name\": \"cadet\", \"version\": \"1.0.0\", \"scripts\": {}}";

        assertThat(parser.canHandle(packageJson))
                .as("nothing in it says a tool was called")
                .isFalse();
        assertThat(parse(packageJson).isSuccessful()).isFalse();
    }

    /** Prose alone must never look like a call, because the parser is offered every reply. */
    @Test
    public void plainProseIsNotAcall() {
        assertThat(parser.canHandle("I read the file and everything looks correct."))
                .isFalse();
    }

    /** A call naming a command that does not exist is scored below the acceptance threshold. */
    @Test
    public void acallOnAcommandThatDoesNotExistIsNotAccepted() {
        ParsedResponse parsed = parse(
                "<tool_call>{\"name\": \"teleport\", \"arguments\": {}}</tool_call>");

        assertThat(parsed.getActions().get(0).getValidation())
                .isEqualTo(ParsedAction.ValidationResult.INVALID_COMMAND);
        assertThat(parsed.getConfidence())
                .as("below the engine's acceptance threshold, so the turn is re-prompted")
                .isLessThan(0.7);
    }

    /** One block may hold several invocations, and each is an action. */
    @Test
    public void severalInvocationsInOneBlockAreSeveralActions() {
        ParsedResponse parsed = parse(
                "<｜DSML｜ calls>\n"
                + "<｜DSML｜ invoke name=\"ls\"></｜DSML｜ invoke>\n"
                + "<｜DSML｜ invoke name=\"read\">"
                + "<｜DSML｜ parameter name=\"file_path\">a.txt</｜DSML｜ parameter>"
                + "</｜DSML｜ invoke>\n"
                + "</｜DSML｜ calls>");

        assertThat(parsed.getActions()).hasSize(2);
        assertThat(parsed.getActions().get(1).getCommand()).isEqualTo("read");
    }

    /**
     * A parameter written on its own lines carries one line break the notation added at each end.
     * Trimming instead loses the blank line a file is meant to end with.
     */
    @Test
    public void avalueOnItsOwnLinesKeepsEveryLineTheModelWrote() {
        ParsedAction action = onlyAction(
                "<invoke name=\"write\">\n"
                + "<parameter name=\"file_path\">notes.md</parameter>\n"
                + "<parameter name=\"content\">\nfirst line\n  indented\n</parameter>\n"
                + "</invoke>");

        assertThat(action.getStringParameter("content")).isEqualTo("first line\n  indented");
    }

    /** The sentence before a call is the nearest thing the notation has to a stated reason. */
    @Test
    public void theProseBeforeAcallBecomesItsReason() {
        ParsedAction action = onlyAction(
                "I need to see the main class first.\n"
                + "<tool_call>{\"name\": \"read\", \"arguments\": {\"file_path\": \"src/Main.java\"}}"
                + "</tool_call>");

        assertThat(action.getReasoning()).isEqualTo("I need to see the main class first.");
    }

    /** A thinking block is the model's own working, not the reason it gives for the call. */
    @Test
    public void athinkingBlockIsNotTheReason() {
        ParsedAction action = onlyAction(
                "<think>Maybe the config, maybe the parser.</think>Checking the parser.\n"
                + "<tool_call>{\"name\": \"read\", \"arguments\": {\"file_path\": \"src/Main.java\"}}"
                + "</tool_call>");

        assertThat(action.getReasoning()).isEqualTo("Checking the parser.");
    }

    /** The engine has to reach the same conclusion the parser does, or nothing downstream sees it. */
    @Test
    public void theEngineReadsAcallAsAstructuredAction() {
        ParsedResponse parsed = new ResponseParsingEngine().parseResponse(
                "<\uFF5CDSML\uFF5C invoke name=\"read\">\n"
                + "<\uFF5CDSML\uFF5C parameter name=\"file_path\">src/Main.java"
                + "</\uFF5CDSML\uFF5C parameter>\n"
                + "</\uFF5CDSML\uFF5C invoke>",
                "read the main class");

        assertThat(parsed.isSuccessful()).isTrue();
        assertThat(parsed.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.TOOL_CALL);
        assertThat(ResponseParsingEngine.isFallbackOnly(parsed))
                .as("a call is written, never inferred, so it must not be marked as a guess")
                .isFalse();
        assertThat(parsed.getActions().get(0).getCommand()).isEqualTo("read");
    }

    /**
     * A reply carrying both meant the block, which is the format the prompt asked for. The call
     * beside it is the model restating itself, and running both would run the command twice.
     */
    @Test
    public void anactionBlockBesideAcallIsWhatWins() {
        ParsedResponse parsed = new ResponseParsingEngine().parseResponse(
                "ACTION_START\nCOMMAND: ls\nARGS: \nREASON: check the tree\nACTION_END\n"
                + "<tool_call>{\"name\": \"read\", \"arguments\": {\"file_path\": \"a.txt\"}}"
                + "</tool_call>",
                "check the tree");

        assertThat(parsed.getStrategyUsed()).isEqualTo(ParsedResponse.ParsingStrategy.ACTION_BLOCK);
        assertThat(parsed.getActions().get(0).getCommand()).isEqualTo("ls");
    }
}
