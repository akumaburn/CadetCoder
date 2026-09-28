package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recovered action has to arrive at the command with the arguments it was recovered with.
 *
 * <p>{@code ErrorRecoveryManager} rewrites an ACTION block's {@code ARGS:} line into
 * {@code {"parameters": {"args": "..."}}}. Nothing on the far side read that key: every per-command
 * arm of {@code ParsedAction.toLegacyAction} looks for its own named parameters, and the default arm
 * would have emitted the whole line as a single unsplit positional. So a recovered
 * {@code grep "TODO" src} dispatched as a bare {@code grep} -- the recovery reported success and
 * produced a guaranteed usage error, which is worse than the failure it replaced because it looks
 * like the model's fault.</p>
 *
 * <h2>Why the path is now written as an option</h2>
 *
 * <p>A recovered block's arguments are read by the block parser's own reader and written by the
 * same writer the ordinary parse uses. {@code GrepCommand} joins its positionals into the pattern,
 * so a bare second positional searched for {@code "TODO src/main"} everywhere rather than for
 * {@code TODO} under {@code src/main}; the path is an option, and that is how it is emitted.</p>
 */
public class RecoveredArgumentsReachTheCommandTest {

    private static List<String> dispatchedArguments(String aiResponse) throws Exception {
        ErrorRecoveryManager.RecoveryResult recovery = new ErrorRecoveryManager().attemptRecovery(
                aiResponse, new ParsingContext.Builder("find the leftovers").build(),
                "no action found");
        assertThat(recovery.isSuccess()).as("the ACTION block is right there to be extracted").isTrue();

        ParsedResponse parsed = new JSONSchemaParser().parse(
                recovery.getRecoveredResponse(),
                new ParsingContext.Builder("find the leftovers").build());
        assertThat(parsed.getActions()).as("the recovered JSON must yield an action").isNotEmpty();

        ChatCommand.AIAction legacy = parsed.getActions().get(0).toLegacyAction();
        Field arguments = ChatCommand.AIAction.class.getDeclaredField("arguments");
        arguments.setAccessible(true);
        return Arrays.asList((String[]) arguments.get(legacy));
    }

    @Test
    public void theArgumentLineIsSplitTheWayAShellWouldSplitIt() throws Exception {
        assertThat(dispatchedArguments(
                "ACTION_START\n"
                + "COMMAND: grep\n"
                + "ARGS: TODO src/main\n"
                + "REASON: find the leftovers\n"
                + "ACTION_END"))
                .as("a grep with no pattern is a usage error, not a recovery")
                .containsExactly("TODO", "--path=src/main");
    }

    @Test
    public void aQuotedArgumentStaysOneArgument() throws Exception {
        assertThat(dispatchedArguments(
                "ACTION_START\n"
                + "COMMAND: grep\n"
                + "ARGS: \"TODO next week\" src\n"
                + "REASON: find the leftovers\n"
                + "ACTION_END"))
                .containsExactly("TODO next week", "--path=src");
    }

    @Test
    public void anActionWithNoArgumentsDispatchesWithNone() throws Exception {
        assertThat(dispatchedArguments("ACTION_START\nCOMMAND: status\nACTION_END"))
                .as("an empty ARGS line must not become an empty-string argument")
                .isEmpty();
    }

    /**
     * The shell runner is the one command that is handed a line, not an argument list.
     *
     * <p>{@code BashCommand} re-joins its argv with a single space, so splitting the line here and
     * letting it be put back together would run {@code grep -r foo bar .} -- a different command,
     * without complaint. The structured JSON path already hands {@code bash} its whole
     * {@code command} parameter as one argument; the recovered path has to agree with it.</p>
     */
    @Test
    public void aRecoveredShellCommandKeepsItsQuotingAndSpacing() throws Exception {
        assertThat(dispatchedArguments(
                "ACTION_START\n"
                + "COMMAND: bash\n"
                + "ARGS: grep -r \"foo bar\" .\n"
                + "REASON: find the leftovers\n"
                + "ACTION_END"))
                .containsExactly("grep -r \"foo bar\" .");
    }

    /** The synonyms canonicalize to the shell runner, so they are handed the line the same way. */
    @Test
    public void aRecoveredShellSynonymIsHandedTheLineWholeToo() throws Exception {
        assertThat(dispatchedArguments(
                "ACTION_START\n"
                + "COMMAND: run\n"
                + "ARGS: echo \"one  two\"\n"
                + "REASON: check the spacing\n"
                + "ACTION_END"))
                .containsExactly("echo \"one  two\"");
    }

    @Test
    public void theWholeLineIsNeverEmittedAsOneToken() throws Exception {
        assertThat(dispatchedArguments(
                "ACTION_START\n"
                + "COMMAND: status\n"
                + "ARGS: --verbose --short\n"
                + "REASON: check the tree\n"
                + "ACTION_END"))
                .containsExactly("--verbose", "--short");
    }
}
