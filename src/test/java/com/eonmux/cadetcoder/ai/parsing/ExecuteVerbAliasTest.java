package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the reported agent-loop failure: a model emits a well-formed {@code ACTION_START} block
 * whose verb is {@code execute} (its synonym for "run a shell command") carrying a real shell command.
 * Before the fix the chat loop dispatched the literal {@code execute} verb to the script-file
 * {@link com.eonmux.cadetcoder.commands.ExecuteCommand}, which resolved the shell string as a file path
 * and reported "File not found". The action must instead dispatch as {@code bash} with the command
 * preserved, so it reaches the shell runner (and its security validation).
 */
public class ExecuteVerbAliasTest {

    private final ActionBlockParser parser = new ActionBlockParser();

    /** No command whitelist, so command-availability is not what is under test here. */
    private ParsingContext ctx(String userRequest) {
        return new ParsingContext.Builder(userRequest).build();
    }

    @Test
    public void executeActionBlockWithShellCommand_dispatchesAsBash() throws Exception {
        String response = "ACTION_START\n"
                + "COMMAND: execute\n"
                + "ARGS: find /home/me/project/src -type f -name \"*.java\"\n"
                + "REASON: List the Java source files to expand the feature list.\n"
                + "ACTION_END";

        ParsedResponse result = parser.parse(response, ctx("list the java files"));

        assertThat(result.isSuccessful()).isTrue();
        ParsedAction action = result.getActions().get(0);
        // The parsed verb is preserved as what the model said...
        assertThat(action.getCommand()).isEqualTo("execute");

        // ...but the executable legacy action dispatches under the canonical shell runner.
        ChatCommand.AIAction legacy = action.toLegacyAction();
        assertThat(commandOf(legacy)).isEqualTo("bash");
        // The whole shell command survives as a single argument (BashCommand re-joins / parses it).
        assertThat(argsOf(legacy)).hasSize(1);
        assertThat(argsOf(legacy)[0]).startsWith("find ").contains("-name").contains("*.java");
    }

    @Test
    public void runVerb_alsoDispatchesAsBash() throws Exception {
        ParsedAction action = new ParsedAction.Builder("run")
                .addParameter("command", "ls -la")
                .setReasoning("inspect the directory")
                .build();

        assertThat(commandOf(action.toLegacyAction())).isEqualTo("bash");
    }

    @Test
    public void realBashVerbIsUnchanged() throws Exception {
        String response = "ACTION_START\nCOMMAND: bash\nARGS: ls -la\nREASON: list\nACTION_END";

        ParsedResponse result = parser.parse(response, ctx("list"));
        ParsedAction action = result.getActions().get(0);

        assertThat(commandOf(action.toLegacyAction())).isEqualTo("bash");
        assertThat(argsOf(action.toLegacyAction())[0]).isEqualTo("ls -la");
    }

    @Test
    public void fileCommandIsNotAffected() throws Exception {
        String response = "ACTION_START\nCOMMAND: read\nARGS: src/Main.java\nREASON: read it\nACTION_END";

        ParsedResponse result = parser.parse(response, ctx("read main"));
        ChatCommand.AIAction legacy = result.getActions().get(0).toLegacyAction();

        assertThat(commandOf(legacy)).isEqualTo("read");
        assertThat(argsOf(legacy)).containsExactly("src/Main.java");
    }

    private String commandOf(ChatCommand.AIAction action) throws ReflectiveOperationException {
        Field f = ChatCommand.AIAction.class.getDeclaredField("command");
        f.setAccessible(true);
        return (String) f.get(action);
    }

    private String[] argsOf(ChatCommand.AIAction action) throws ReflectiveOperationException {
        Field f = ChatCommand.AIAction.class.getDeclaredField("arguments");
        f.setAccessible(true);
        return (String[]) f.get(action);
    }
}
