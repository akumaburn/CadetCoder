package com.eonmux.cadetcoder.agents;

import com.eonmux.cadetcoder.ai.parsing.ActionBlockParser;
import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import com.eonmux.cadetcoder.ai.parsing.ParsingContext;
import com.eonmux.cadetcoder.commands.ChatCommand;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A {@code workers} action the model writes has to reach the command with its tasks separated. */
public class WorkersActionParsingTest {

    private List<String> emitted(String argsLine) throws Exception {
        String block = "ACTION_START\n"
                       + "COMMAND: workers\n"
                       + "ARGS: " + argsLine + "\n"
                       + "REASON: split the review\n"
                       + "ACTION_END";
        ParsedResponse response = new ActionBlockParser()
                .parse(block, new ParsingContext.Builder("review this").build());
        assertThat(response.getActions()).isNotEmpty();
        ChatCommand.AIAction legacy = response.getActions().get(0).toLegacyAction();
        Field f = ChatCommand.AIAction.class.getDeclaredField("arguments");
        f.setAccessible(true);
        return Arrays.asList((String[]) f.get(legacy));
    }

    @Test
    public void eachQuotedTaskArrivesAsItsOwnArgument() throws Exception {
        // The whole feature depends on this: joined into one string, every worker would be handed
        // the same combined text and the run would be N copies of one agent.
        List<String> args = emitted("\"review error handling\" \"review test coverage\"");

        assertThat(args).contains("review error handling", "review test coverage");
    }

    @Test
    public void aTaskKeepsItsInternalSpacesAndPunctuation() throws Exception {
        List<String> args = emitted("\"check RetryPolicy: 429, 5xx, and transport\"");

        assertThat(args).contains("check RetryPolicy: 429, 5xx, and transport");
    }

    @Test
    public void theSharedBriefingSurvivesAsAFlagAndItsValue() throws Exception {
        List<String> args = emitted("\"task one\" \"task two\" -b \"the net package\"");

        assertThat(args).contains("task one", "task two", "-b", "the net package");
    }

    @Test
    public void everyLifecycleSubcommandReachesTheCommandIntact() throws Exception {
        // The model is told it can check progress, read results and terminate. Each of those is a
        // subcommand, so each has to survive the action protocol as its own leading argument --
        // joined into one token they would all be read as a task and spawn workers instead.
        assertThat(emitted("status")).containsExactly("status");
        assertThat(emitted("stop")).containsExactly("stop");
        assertThat(emitted("list")).containsExactly("list");
        assertThat(emitted("wait")).containsExactly("wait");
        assertThat(emitted("wait 30")).containsExactly("wait", "30");
        assertThat(emitted("show 2")).containsExactly("show", "2");
    }

    @Test
    public void aBackgroundStartKeepsItsSubcommandAndItsTasksApart() throws Exception {
        List<String> args = emitted("start \"review retries\" \"review the UI\"");

        assertThat(args).containsExactly("start", "review retries", "review the UI");
    }

    @Test
    public void theStepBudgetSurvives() throws Exception {
        List<String> args = emitted("\"task one\" -m 5");

        assertThat(args).contains("task one", "-m", "5");
    }
}
