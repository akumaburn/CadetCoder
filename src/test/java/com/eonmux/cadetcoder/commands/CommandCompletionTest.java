package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Predicting a command name from what has been typed. */
public class CommandCompletionTest {

    private static final Set<String> COMMANDS = Set.of(
            "chat", "commit", "config", "context", "copilot",
            "read", "refactor", "grep", "glob", "ls");

    private static CommandCompletion complete(String line) {
        return CommandCompletion.of(line, COMMANDS);
    }

    @Test
    public void aUniquePrefixCompletesToTheWholeName() {
        // "rea" can only be "read".
        assertThat(complete("/rea").getSuffix()).isEqualTo("d");
        assertThat(complete("/rea").getMatches()).isEqualTo(1);
    }

    @Test
    public void aPrefixSharedByTwoCommandsAddsNothing() {
        // "re" is read AND refactor, and they share no further character.
        assertThat(complete("/re").getSuffix()).isEmpty();
        assertThat(complete("/re").getMatches()).isEqualTo(2);
    }

    @Test
    public void anAmbiguousPrefixAddsOnlyWhatEveryCandidateShares() {
        // chat, commit, config, context, copilot all begin with "c" and nothing more, so there is
        // nothing safe to add -- completing to the first alphabetically would be a guess.
        assertThat(complete("/c").getSuffix()).isEmpty();
        assertThat(complete("/c").getMatches()).isEqualTo(5);

        // commit, config, context, copilot share "co".
        assertThat(complete("/co").getSuffix()).isEqualTo("");
        assertThat(complete("/com").getSuffix()).isEqualTo("mit");
    }

    @Test
    public void theHintSaysHowManyCommandsRemainWhileTheChoiceIsOpen() {
        assertThat(complete("/c").ghostText()).contains("5 commands");
        assertThat(complete("/comm").ghostText()).isEqualTo("it");
    }

    @Test
    public void aCompleteNameSuggestsNothingFurther() {
        assertThat(complete("/read").getSuffix()).isEmpty();
        assertThat(complete("/read").getMatches()).isEqualTo(1);
        assertThat(complete("/read").isEmpty()).isTrue();
    }

    @Test
    public void aMessageForTheAiIsNeverCompleted() {
        // Without the leading slash the line goes to the model. Predicting a command name into prose
        // would suggest the shell was about to run something it is not.
        assertThat(complete("re").getSuffix()).isEmpty();
        assertThat(complete("read the config file").getSuffix()).isEmpty();
        assertThat(complete("").getSuffix()).isEmpty();
    }

    @Test
    public void anEscapedMessageIsNeverCompleted() {
        assertThat(complete("//read").getSuffix()).isEmpty();
    }

    @Test
    public void argumentsAreNotCompleted() {
        // Once a space is typed the name is settled; what follows is arguments.
        assertThat(complete("/read po").getSuffix()).isEmpty();
        assertThat(complete("/read ").getSuffix()).isEmpty();
    }

    @Test
    public void anUnknownPrefixSuggestsNothing() {
        assertThat(complete("/zzz").getSuffix()).isEmpty();
        assertThat(complete("/zzz").getMatches()).isZero();
    }

    @Test
    public void matchingIsCaseInsensitiveButKeepsTheRegisteredSpelling() {
        assertThat(complete("/REA").getSuffix()).isEqualTo("d");
    }

    @Test
    public void aSlashOnItsOwnDoesNotPredictAnything() {
        assertThat(complete("/").getSuffix()).isEmpty();
    }

    @Test
    public void missingInputsAreHandledRatherThanThrowing() {
        assertThat(CommandCompletion.of(null, COMMANDS)).isSameAs(CommandCompletion.NONE);
        assertThat(CommandCompletion.of("/re", null)).isSameAs(CommandCompletion.NONE);
        assertThat(CommandCompletion.of("/re", Set.of())).isSameAs(CommandCompletion.NONE);
    }
}
