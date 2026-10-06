package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.harness.cadet.CommandEnvironment;
import com.eonmux.cadetcoder.harness.loop.SystemPrompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * The system prompts of {@code chat}, {@code agent} and a worker open with the same text.
 *
 * <h2>The defect</h2>
 *
 * <p>A provider serves a request from its cache only as far as the request repeats the opening
 * text of an earlier one. The command list was the same in every loop's prompt, but it sat in the
 * middle of the chat prompt and near the end of the harness prompt, after the task. So a chat and an
 * agent run shared only the operating principles, and two agent runs or two workers with different
 * tasks shared nothing after the task.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That each loop's own prompt starts with {@link CommandCatalog#promptBlock}; that the harness
 * puts the task last; and that a worker's list leaves out {@code workers}, which a worker may not
 * use.</p>
 */
public class EveryLoopOpensWithTheSameCommandListTest {

    private static final String TASK = "count the lines of notes.txt";

    private static final String CORE = CommandCatalog.promptBlock(CommandCatalog.coreCommands());

    @Test
    public void theChatPromptOpensWithTheCommandList() {
        assertThat(new ChatCommand().getIterativeSystemPrompt()).startsWith(CORE);
    }

    @Test
    public void theClassicAgentPromptOpensWithTheCommandList() {
        assertThat(AgentPrompts.system(null, AgentOptions.UNLIMITED, message -> { }))
                .startsWith(CORE);
    }

    @Test
    public void theHarnessPromptOpensWithTheCommandList(@TempDir Path workspace) {
        assertThat(SystemPrompt.render(harness(workspace))).startsWith(CORE);
    }

    @Test
    public void allThreeLoopsShareTheOpeningUpToTheEndOfTheList(@TempDir Path workspace) {
        String chat    = new ChatCommand().getIterativeSystemPrompt();
        String classic = AgentPrompts.system(null, AgentOptions.UNLIMITED, message -> { });
        String harness = SystemPrompt.render(harness(workspace));

        assertThat(chat.substring(0, CORE.length()))
                .isEqualTo(classic.substring(0, CORE.length()))
                .isEqualTo(harness.substring(0, CORE.length()));
    }

    @Test
    public void theShippedTemplatesNameTheHeadingTheListIsUnder() {
        // Both templates point the model at the list by its heading, so a renamed heading would
        // leave them pointing at nothing.
        assertThat(com.eonmux.cadetcoder.prompts.PromptResource.text("chat"))
                .contains(CommandCatalog.HEADING);
        assertThat(com.eonmux.cadetcoder.prompts.PromptResource.text("agent"))
                .contains(CommandCatalog.HEADING);
    }

    @Test
    public void theHarnessPromptEndsWithTheTask(@TempDir Path workspace) {
        // Everything before the task is the same for every run, so it is what a later run, or
        // another worker started beside this one, can be served from the cache.
        assertThat(SystemPrompt.render(harness(workspace)).strip()).endsWith(TASK);
    }

    @Test
    public void theClassicAgentPromptNamesTheTaskAfterEverythingElse() {
        String prompt = AgentPrompts.system(null, AgentOptions.UNLIMITED, message -> { });

        assertThat(prompt.indexOf("(see user request)"))
                .as("the task, after the instructions that are the same for every run")
                .isGreaterThan(prompt.indexOf("## Best Practices"));
    }

    @Test
    public void aWorkerIsNotOfferedWorkers(@TempDir Path workspace) {
        String worker = CommandCatalog.promptBlock(CommandCatalog.workerCommands());
        try (MockedStatic<WorkerPool> pool = mockStatic(WorkerPool.class, CALLS_REAL_METHODS)) {
            pool.when(WorkerPool::insideWorker).thenReturn(true);

            assertThat(SystemPrompt.render(harness(workspace))).startsWith(worker)
                    .doesNotContain("- workers:");
            assertThat(AgentPrompts.system(null, AgentOptions.UNLIMITED, message -> { }))
                    .startsWith(worker);
            assertThat(new ChatCommand().getIterativeSystemPrompt()).startsWith(worker);
        }
    }

    private static CommandEnvironment harness(Path workspace) {
        return new CommandEnvironment(new CommandRegistry(), workspace, TASK, null);
    }
}
