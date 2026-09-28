package com.eonmux.cadetcoder.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The screen an action passes before it is dispatched reads every command it would start.
 *
 * <h2>The defect this guards against</h2>
 *
 * <p>One {@code job} action can start a command on each of several lines. The screen was shown
 * one command per action, so it would read the first line and let the rest through unread.</p>
 */
class EveryJobOfAnActionIsScreenedTest {

    @Test
    void everyLineIsShownToTheScreen() {
        ChatCommand.AIAction action = new ChatCommand.AIAction(
                "job",
                JobCommand.argvFor("start\nsleep 1\n-d \"the other\" rm -rf /", null)
                          .toArray(new String[0]),
                "two jobs");

        assertThat(ActionRun.shellCommandsIn(action)).containsExactly("sleep 1", "rm -rf /");
    }

    @Test
    void abashActionIsOneCommand() {
        ChatCommand.AIAction action =
                new ChatCommand.AIAction("bash", new String[] {"ls -la"}, "look");

        assertThat(ActionRun.shellCommandsIn(action)).containsExactly("ls -la");
    }

    @Test
    void anActionThatRunsNothingHasNothingToScreen() {
        ChatCommand.AIAction action =
                new ChatCommand.AIAction("job", new String[] {"list"}, "look");

        assertThat(ActionRun.shellCommandsIn(action)).isEmpty();
    }
}
