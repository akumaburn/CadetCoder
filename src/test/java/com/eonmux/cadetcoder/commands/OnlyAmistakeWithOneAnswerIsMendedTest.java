package com.eonmux.cadetcoder.commands;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the agent loop repairs on the model's behalf, and what it refuses to guess at.
 *
 * <p>Mending an action means acting on an inference about what the model meant, so the line between
 * a near-certain inference and an invented one is the whole of this class's correctness. It had no
 * test of either side: not that a file found elsewhere in the conversation is substituted, and not
 * that a refused path or an unparseable pattern is left to fail. The second is the one that
 * matters -- a wrong repair runs a command against a file nobody named.</p>
 */
public class OnlyAmistakeWithOneAnswerIsMendedTest {

    private ActionRecovery recovery;
    private ChatContext    context;

    @Before
    public void setUp() {
        recovery = new ActionRecovery(new LoggingCommandSupport() { });
        context  = new ChatContext();
    }

    @Test
    public void onlyAmissingFileOrAwriteWithNoContentIsWorthGuessingAbout() {
        ChatCommand.AIAction read = action("read", "Missing.java");

        assertThat(ActionRecovery.isMendable(read, ActionOutcome.FILE_NOT_FOUND, "no such file")).isTrue();
        assertThat(ActionRecovery.isMendable(read, ActionOutcome.VALIDATION, "invalid_arguments_for_read"))
                .isTrue();
        assertThat(ActionRecovery.isMendable(read, ActionOutcome.VALIDATION, "path is outside the project"))
                .isFalse();
        assertThat(ActionRecovery.isMendable(read, ActionOutcome.PERMISSION, "denied")).isFalse();
        assertThat(ActionRecovery.isMendable(null, ActionOutcome.FILE_NOT_FOUND, "x")).isFalse();
        assertThat(ActionRecovery.isMendable(read, null, "x")).isFalse();
    }

    /** A path this conversation has already proved beats one the project's shape merely suggests. */
    @Test
    public void afileThisConversationAlreadyFoundIsTheOneTheActionIsPointedAt() {
        Map<String, String> found = new HashMap<>();
        found.put("Parser.java", "src/main/java/com/example/Parser.java");
        context.setFoundFiles(found);

        ChatCommand.AIAction mended = recovery.mended(
                action("read", "Parser.java", "-l", "20"), ActionOutcome.FILE_NOT_FOUND, "no such file", context);

        assertThat(mended).isNotNull();
        assertThat(mended.command).isEqualTo("read");
        assertThat(mended.arguments).containsExactly("src/main/java/com/example/Parser.java", "-l", "20");
        assertThat(mended.explanation).endsWith("(recovered)");
    }

    /** A write that named a path and nothing else meant the empty file it named. */
    @Test
    public void awriteGivenOnlyApathIsGivenTheEmptyContentItMeant() {
        ChatCommand.AIAction mended = recovery.mended(
                action("write", "notes.md"), ActionOutcome.VALIDATION, "invalid_arguments_for_write", context);

        assertThat(mended).isNotNull();
        assertThat(mended.arguments).containsExactly("notes.md", "");
        assertThat(mended.explanation).endsWith("(fixed args)");
    }

    /** A validation failure reported for one command must not rewrite another. */
    @Test
    public void afailureNamingAnotherCommandIsNotAnInstructionToRewriteThisOne() {
        assertThat(recovery.mended(action("write", "notes.md"),
                                   ActionOutcome.VALIDATION, "invalid_arguments_for_read", context))
                .isNull();
        assertThat(recovery.mended(action("read", "notes.md"),
                                   ActionOutcome.VALIDATION, "invalid_arguments_for_read", context))
                .as("only write has an inferable missing argument")
                .isNull();
        assertThat(recovery.mended(action("write", "notes.md", "already has content"),
                                   ActionOutcome.VALIDATION, "invalid_arguments_for_write", context))
                .as("nothing was missing")
                .isNull();
    }

    @Test
    public void anActionWithNoPathToCorrectIsLeftToFail() {
        assertThat(recovery.mended(action("read"), ActionOutcome.FILE_NOT_FOUND, "x", context)).isNull();
        assertThat(recovery.mended(action("read", ""), ActionOutcome.FILE_NOT_FOUND, "x", context)).isNull();
    }

    private static ChatCommand.AIAction action(String command, String... arguments) {
        return new ChatCommand.AIAction(command, arguments, "because");
    }
}
