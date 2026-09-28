package com.eonmux.cadetcoder.agents;

import com.eonmux.cadetcoder.commands.AgentCommand;
import com.eonmux.cadetcoder.commands.IterativeCommand;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker's agent must not stop to ask whether it may run.
 *
 * <p>This is the defect behind the unexplained {@code ? >>>} prompt. {@code AgentCommand} confirms
 * before starting — "I'll help you with: ... Do you want to proceed? (yes/no)" — which is right for
 * someone typing {@code agent} at a shell and impossible for a worker: the question is collected
 * into that worker's own output where nobody sees it, while the bare input prompt reaches the
 * terminal. Several workers then contend for the one input line.</p>
 *
 * <p>Closing that by making workers unable to prompt is only half an answer. The safe default for a
 * confirmation nobody can answer is "no", so the workers would have cancelled themselves instead —
 * quietly, and looking for all the world like they had run. The consent has to come from the caller
 * that already has it.</p>
 */
class WorkerConfirmationTest {

    @TempDir
    Path sandbox;

    private String originalUserHome;
    private String originalBaseDir;

    /**
     * Keeps this test off the user's real configuration.
     *
     * <p>Driving {@code AgentCommand} through a step builds
     * {@link com.eonmux.cadetcoder.config.ConfigManager}, which resolves and then chmods the config
     * file it finds. Left alone that is the caller's actual {@code ~/.cadet/config.json} — a unit
     * test has no business reading, writing or changing the permissions of it — and the singleton
     * stays bound to it afterwards, which breaks any later test that points {@code user.home}
     * somewhere else.</p>
     */
    @BeforeEach
    void isolateConfiguration() throws Exception {
        originalUserHome = System.getProperty("user.home");
        originalBaseDir  = Configuration.defaultBaseDir;
        System.setProperty("user.home", sandbox.toString());
        Configuration.defaultBaseDir = sandbox.resolve(".cadet").toString();
        resetConfigManagerSingleton();
    }

    @AfterEach
    void restoreConfiguration() throws Exception {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        Configuration.defaultBaseDir = originalBaseDir;
        resetConfigManagerSingleton();
    }

    private static void resetConfigManagerSingleton() throws Exception {
        java.lang.reflect.Field instance =
                com.eonmux.cadetcoder.config.ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /** The argv WorkerPool hands one worker; private, and its content is the thing under test. */
    private static String[] workerArguments(WorkerTask task, int maxSteps) throws Exception {
        Method method = WorkerPool.class.getDeclaredMethod("arguments", WorkerTask.class, int.class);
        method.setAccessible(true);
        return (String[]) method.invoke(null, task, maxSteps);
    }

    @Test
    @DisplayName("A worker's agent is started with consent already recorded")
    void workersStartTheirAgentPreconfirmed() throws Exception {
        WorkerTask task = new WorkerTask(1, "review the net package", "");

        assertThat(workerArguments(task, 0)).contains("-y");
        assertThat(workerArguments(task, 12)).contains("-y", "-m", "12");
    }

    @Test
    @DisplayName("Without consent the agent asks, and the question is a real one for the user")
    void anOrdinaryAgentInvocationStillConfirms() {
        Map<String, Object> context = new HashMap<>();
        context.put("step", "confirm_task");
        context.put("task", "review the net package");
        context.put("maxStepCount", 12);

        IterativeCommand.StepResult result =
                new AgentCommand().executeStep(new String[]{"review the net package"}, context, null);

        assertThat(result.getNextPrompt()).contains("Do you want to proceed? (yes/no)");
        assertThat(result.isComplete()).isFalse();
    }

    @Test
    @DisplayName("With consent recorded the agent answers its own confirmation and moves on")
    void aPreconfirmedAgentDoesNotAsk() {
        // Intercepted at the step the decision hands off to, not deeper. Letting it run further
        // prints the agent's opening header, which initialises the logging and configuration
        // singletons and leaves them set for whatever test runs next -- and the decision under test
        // is already made by then.
        java.util.List<String> handedOffWith = new java.util.ArrayList<>();
        AgentCommand agent = new AgentCommand() {
            @Override
            public IterativeCommand.StepResult executeStep(String[] args, Map<String, Object> ctx,
                                                           String llmResponse) {
                if ("confirm_execution".equals(ctx.get("step")) && handedOffWith.isEmpty()) {
                    handedOffWith.add(String.valueOf(llmResponse));
                    return IterativeCommand.StepResult.success("intercepted", ctx);
                }
                return super.executeStep(args, ctx, llmResponse);
            }
        };

        Map<String, Object> context = new HashMap<>();
        context.put("step", "confirm_task");
        context.put("task", "review the net package");
        context.put("maxStepCount", 12);
        context.put("preconfirmed", true);

        IterativeCommand.StepResult result =
                agent.executeStep(new String[]{"review the net package"}, context, null);

        // The confirmation was answered on the caller's authority rather than asked into a void.
        assertThat(handedOffWith).containsExactly("yes");
        assertThat(result.getNextPrompt() == null ? "" : result.getNextPrompt())
                .doesNotContain("Do you want to proceed");
    }

    @Test
    @DisplayName("Answering the confirmation with the safe default cancels — which is why it is not asked")
    void theSafeDefaultForThatConfirmationIsCancellation() {
        // Pins the reason the flag exists. If this ever stops cancelling, the flag is still correct
        // but this test's premise has changed and the comment above it would be wrong.
        Map<String, Object> context = new HashMap<>();
        context.put("step", "confirm_execution");
        context.put("task", "review the net package");
        context.put("maxStepCount", 12);

        IterativeCommand.StepResult result =
                new AgentCommand().executeStep(new String[]{"t"}, context, "no");

        assertThat(result.isComplete()).isTrue();
        assertThat(result.isInterrupted())
                .as("nobody authorised the run, so it never started -- that is not a failed task")
                .isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(result.getOutput()).contains("Agent execution cancelled");
    }

    @Test
    @DisplayName("The -y flag is documented, not just accepted")
    void theFlagIsInTheUsage() {
        String usage = new AgentCommand().getUsage();

        assertThat(usage).contains("-y");
        List<String> mustMention = List.of("--yes", "confirmation");
        assertThat(mustMention).allSatisfy(word -> assertThat(usage).contains(word));
    }
}
