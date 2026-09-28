package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent prompt is a contract, in the same way the chat prompt is.
 *
 * <p>{@code ChatPromptContractTest} has bound every example in {@code chat.md} to the parser for
 * some time. {@code agent.md} had no equivalent, and drifted: it documented only a single-line
 * {@code ARGS:} while its catalog advertised {@code write <filepath> <content>} and
 * {@code multiedit <filepath> <edits>}, both of which are inherently multi-line. Every one of its
 * worked examples used {@code edit}, so nothing in the file ever demonstrated the two commands
 * whose format it failed to describe -- and each attempt cost the agent a step at runtime with no
 * signal anywhere.</p>
 */
public class AgentPromptContractTest {

    private static final Pattern ACTION_BLOCK =
            Pattern.compile("ACTION_START\\n.*?ACTION_END", Pattern.DOTALL);

    private String promptText() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/prompts/default/content/agent.md")) {
            assertThat(in).as("packaged agent prompt").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** What the agent would make of one reply. Warnings are not what these tests assert on. */
    private static AgentAction parse(String response) {
        return AgentActions.from(response, message -> { });
    }

    private static String commandOf(AgentAction action) {
        return action.command;
    }

    private static String[] argsOf(AgentAction action) {
        return action.args;
    }

    private List<String> exampleBlocks() throws Exception {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = ACTION_BLOCK.matcher(promptText());
        while (matcher.find()) {
            blocks.add(matcher.group());
        }
        return blocks;
    }

    @Test
    public void everyActionExampleInTheAgentPromptParsesIntoADispatchableAction() throws Exception {
        Set<String> registered = new CommandRegistry().getCommands().keySet();
        List<String> blocks = exampleBlocks();

        assertThat(blocks).as("the agent prompt must contain ACTION examples").isNotEmpty();

        for (String block : blocks) {
            // The format skeleton uses the literal placeholder "command_name": it shows the shape
            // rather than a call, so only its structure can be checked.
            if (block.contains("COMMAND: command_name")) {
                assertThat(parse(block)).as("parse of format skeleton:\n%s", block).isNotNull();
                continue;
            }

            AgentAction action = parse(block);
            assertThat(action).as("parse of agent prompt example:\n%s", block).isNotNull();
            assertThat(registered)
                    .as("command of agent prompt example:\n%s", block)
                    .contains(commandOf(action));
            assertThat(argsOf(action))
                    .as("dispatched arguments for agent prompt example:\n%s", block)
                    .isNotEmpty();
        }
    }

    /** The two commands whose format the prompt failed to describe. */
    @Test
    public void thePromptShowsHowToPassAMultiLinePayload() throws Exception {
        String prompt = promptText();

        assertThat(prompt)
                .as("write and multiedit are advertised to the agent; without this the model has "
                    + "no spelling for their payload and cannot call either one")
                .contains("ARGS_BEGIN")
                .contains("ARGS_END");
        assertThat(prompt).contains("COMMAND: write");
        assertThat(prompt).contains("COMMAND: multiedit");
    }

    @Test
    public void theMultiLineExamplesTheAgentIsShownActuallyParse() throws Exception {
        List<String> multiLine = new ArrayList<>();
        for (String block : exampleBlocks()) {
            if (block.contains("ARGS_BEGIN") || block.contains("COMMAND: write")) {
                multiLine.add(block);
            }
        }

        assertThat(multiLine).as("the prompt must demonstrate a multi-line payload").isNotEmpty();

        for (String block : multiLine) {
            AgentAction action = parse(block);
            assertThat(action).as("parse of:\n%s", block).isNotNull();
            assertThat(argsOf(action))
                    .as("a payload example that parses to no arguments teaches a call that fails:\n%s",
                        block)
                    .hasSizeGreaterThanOrEqualTo(2);
        }
    }
}
