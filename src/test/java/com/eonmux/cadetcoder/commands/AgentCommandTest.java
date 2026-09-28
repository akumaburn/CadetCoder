package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.context.ContextEngine;
import org.junit.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertSubheader;
import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertWarning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * The loop {@code --classic} asks for: one model reply per step, driven by {@link IterativeExecutor}.
 *
 * <p>These tests build the run context themselves and enter at {@code confirm_execution}, so each
 * says {@code classic} there -- the same thing the {@code initial} step puts in from the invocation.
 * The default is the agent harness, which owns its own loop and keeps a ledger; what a run under it
 * is asked for is settled in {@code WhichHarnessARunGetsIsTheCallersChoiceTest}, and what it does
 * with the request in {@code ARunIsBoundToThisToolsOwnCommandsTest}.</p>
 */
public class AgentCommandTest {

    /** The task these tests give the agent; its wording is never what is being tested. */
    private static final String[] TASK_ARGV = {"keep going"};

    /** Seconds a run is allowed, where a run in these tests is allowed anything at all. */
    private static final int TIME_BUDGET_SECONDS = 30;

    /** Seconds are what a person asks for; milliseconds are what the clock reads in. */
    private static final long MILLIS_PER_SECOND = 1000L;

    /** The same budget in the unit the clock is read in. */
    private static final long TIME_BUDGET_MILLIS = TIME_BUDGET_SECONDS * MILLIS_PER_SECOND;

    /** Long enough that no plausible budget survives one look at the clock. */
    private static final long A_DAY_MILLIS = 24L * 60L * 60L * MILLIS_PER_SECOND;

    /** How many model replies a run gets before the model says the task is done. */
    private static final int STEPS_BEFORE_DONE = 4;


    @Mock
    private CommandRegistry mockRegistry;

    @Mock
    private AIManager mockAIManager;

    @Mock
    private ContextEngine mockContextEngine;

    @Mock
    private ConfigManager mockConfigManager;

    @Mock
    private Configuration mockConfig;

    @Mock
    private Configuration.AiConfig mockAIConfig;

    private TestableAgentCommand  agentCommand;
    private ByteArrayOutputStream outputStream;
    private ByteArrayOutputStream errorStream;
    private PrintStream           originalOut;
    private PrintStream           originalErr;
    private AutoCloseable         mocks;

    @Before
    public void setUp() throws Exception {
        // Reset singletons before each test
        resetSingletons();

        mocks        = MockitoAnnotations.openMocks(this);
        agentCommand = new TestableAgentCommand();
        agentCommand.setCommandRegistry(mockRegistry);
        agentCommand.setMockAIManager(mockAIManager);
        agentCommand.setMockConfigManager(mockConfigManager);
        agentCommand.setMockContextEngine(mockContextEngine);

        outputStream = new ByteArrayOutputStream();
        errorStream  = new ByteArrayOutputStream();
        originalOut  = System.out;
        originalErr  = System.err;
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(errorStream));

        // Setup default mock behavior
        when(mockConfig.getAi()).thenReturn(mockAIConfig);
        when(mockAIConfig.getMaxTokens()).thenReturn(4096);
        when(mockAIConfig.getModel()).thenReturn("test-model");
    }

    private void resetSingletons() {
        try {
            // Reset AIManager
            java.lang.reflect.Field aiInstance =
                    com.eonmux.cadetcoder.ai.AIManager.class.getDeclaredField("instance");
            aiInstance.setAccessible(true);
            aiInstance.set(null, null);

            // Reset ContextEngine
            java.lang.reflect.Field contextInstance = ContextEngine.class.getDeclaredField("instance");
            contextInstance.setAccessible(true);
            contextInstance.set(null, null);

            // Reset ConfigManager
            java.lang.reflect.Field configInstance = ConfigManager.class.getDeclaredField("instance");
            configInstance.setAccessible(true);
            configInstance.set(null, null);
        } catch (Exception e) {
            // Ignore
        }
    }

    @After
    public void tearDown() throws Exception {
        System.setOut(originalOut);
        System.setErr(originalErr);
        mocks.close();

        // Reset singletons to ensure test isolation
        resetSingletons();
    }

    @Test
    public void testAgent_SimpleTaskCompletion() throws Exception {
        // Create a mock IterativeExecutor that bypasses the iterative flow for testing
        TestableAgentCommand testCommand = new TestableAgentCommand() {
            @Override
            public int execute(String[] args) {
                // Bypass iterative executor and test the core logic directly
                try {
                    // Setup mocks
                    when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                    when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());

                    // Mock AI responses in sequence
                    when(mockAIManager.complete(any(PromptData.class), any()))
                            .thenReturn("COMMAND: read ARGS: test.txt")
                            .thenReturn("COMMAND: complete ARGS: Task completed successfully");

                    // Mock command execution
                    when(mockRegistry.executeCommand(eq("read"), any())).thenReturn(0);

                    // Simulate the agent execution flow
                    Map<String, Object> context = new HashMap<>();
                    context.put("task", "Read the test file");
                    context.put("timeoutSec", 300);
                    context.put("maxStepCount", 10);
                    context.put("verboseMode", false);
                    context.put("classic", true);
                    context.put("step", "confirm_execution");

                    // Simulate confirmation
                    StepResult confirmResult = executeStep(args, context, "yes");

                    // Execute agent steps
                    int steps = 0;
                    while (steps < 10) {
                        StepResult stepResult = executeStep(args, context, null);
                        if (stepResult.isComplete()) {
                            return stepResult.getOutput().contains("successfully") ? 0 : 1;
                        }
                        steps++;
                    }
                    return 1;
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            }
        };

        testCommand.setCommandRegistry(mockRegistry);
        testCommand.setMockAIManager(mockAIManager);
        testCommand.setMockConfigManager(mockConfigManager);
        testCommand.setMockContextEngine(mockContextEngine);

        // Execute agent
        int exitCode = testCommand.execute(new String[] {"Read the test file", "-m", "10"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputStream.toString();
        assertThat(output).contains("AI Agent Starting");
        assertThat(output).contains("Task: Read the test file");
        assertThat(output).contains("Agent task completed:");

        verify(mockRegistry).executeCommand(eq("read"), any());
    }

    @Test
    public void testAgent_MaxStepsReached() throws Exception {
        // Create a test command that simulates max steps
        TestableAgentCommand testCommand = new TestableAgentCommand() {
            @Override
            public int execute(String[] args) {
                try {
                    // Setup mocks
                    when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                    when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());

                    // Mock AI responses that never complete
                    when(mockAIManager.complete(any(PromptData.class), any()))
                            .thenReturn("COMMAND: edit ARGS: file.txt");

                    when(mockRegistry.executeCommand(any(), any())).thenReturn(0);

                    // Simulate agent execution with max steps
                    Map<String, Object> context = new HashMap<>();
                    // Instead of directly creating AgentState, simulate the proper flow
                    context.put("task", "Complex task");
                    context.put("timeoutSec", 300);
                    context.put("maxStepCount", 2);
                    context.put("verboseMode", false);
                    context.put("classic", true);
                    context.put("step", "confirm_execution");

                    // First confirm execution
                    StepResult confirmResult = executeStep(args, context, "yes");

                    // Execute steps until max is reached
                    for (int i = 0; i < 3; i++) {
                        StepResult result = executeStep(args, context, null);
                        if (result.isComplete()) {
                            return result.getOutput().contains("maximum steps") ? 1 : 0;
                        }
                    }
                    return 1;
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            }
        };

        testCommand.setCommandRegistry(mockRegistry);
        testCommand.setMockAIManager(mockAIManager);
        testCommand.setMockConfigManager(mockConfigManager);
        testCommand.setMockContextEngine(mockContextEngine);

        // Execute agent with small max steps
        int exitCode = testCommand.execute(new String[] {"Complex task", "-m", "2"});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        String output = outputStream.toString();
        // An EXPLICIT -m budget is still honoured; only the default changed (there is no longer one).
        // The message now names the limit the caller asked for, since it is no longer a built-in.
        assertThat(output).contains("Agent reached the requested limit of 2 steps without completing the task");
    }

    @Test
    public void testAgent_CommandFailure() throws Exception {
        // Create a test command that simulates command failure
        TestableAgentCommand testCommand = new TestableAgentCommand() {
            @Override
            public int execute(String[] args) {
                try {
                    // Setup mocks
                    when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                    when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());

                    // Mock AI responses with failing command
                    when(mockAIManager.complete(any(PromptData.class), any()))
                            .thenReturn("COMMAND: bash ARGS: invalid-command")
                            .thenReturn("COMMAND: complete ARGS: Handled error");

                    // Mock command failure
                    when(mockRegistry.executeCommand(eq("bash"), any())).thenReturn(1);

                    // Simulate the agent execution flow
                    Map<String, Object> context = new HashMap<>();
                    context.put("task", "Execute command");
                    context.put("timeoutSec", 300);
                    context.put("maxStepCount", 10);
                    context.put("verboseMode", false);
                    context.put("classic", true);
                    context.put("step", "confirm_execution");

                    // Simulate confirmation
                    StepResult confirmResult = executeStep(args, context, "yes");

                    // Execute agent steps
                    int steps = 0;
                    while (steps < 10) {
                        StepResult stepResult = executeStep(args, context, null);
                        if (stepResult.isComplete()) {
                            return stepResult.getOutput().contains("successfully") ? 0 : 1;
                        }
                        steps++;
                    }
                    return 1;
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            }
        };

        testCommand.setCommandRegistry(mockRegistry);
        testCommand.setMockAIManager(mockAIManager);
        testCommand.setMockConfigManager(mockConfigManager);
        testCommand.setMockContextEngine(mockContextEngine);

        // Execute agent
        int exitCode = testCommand.execute(new String[] {"Execute command"});

        // Verify - the agent should complete successfully even if a command fails
        assertThat(exitCode).isEqualTo(0);
        String output = outputStream.toString();
        // The command is named once, by the sub-header that announces it; the record beneath carries
        // the outcome -- "FAILED", plus the exit code -- without restating the invocation.
        assertSubheader(output, "bash invalid-command");
        assertWarning(output, "FAILED");
        assertThat(output).contains("exit 1");
        assertThat(output).contains("Agent task completed:");
    }

    @Test
    public void testAgent_NoTaskProvided() {
        // This test checks argument validation before any AI calls
        // No mocking needed as it should fail early
        int exitCode = agentCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        // Check both stdout and stderr for the error message
        String output = outputStream.toString() + errorStream.toString();
        assertThat(output).contains("No task description provided");
    }

    @Test
    public void testAgent_InvalidResponse() throws Exception {
        // Create a test command that simulates invalid response handling
        TestableAgentCommand testCommand = new TestableAgentCommand() {
            @Override
            public int execute(String[] args) {
                try {
                    // Setup mocks
                    when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                    when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());

                    // Mock AI with responses that lead to unknown commands
                    when(mockAIManager.complete(any(PromptData.class), any()))
                            .thenReturn("unknown_cmd some args")
                            .thenReturn("COMMAND: complete ARGS: Recovered from error");

                    // Mock unknown command to fail
                    when(mockRegistry.executeCommand(eq("unknown_cmd"), any())).thenReturn(1);

                    // Simulate the agent execution flow
                    Map<String, Object> context = new HashMap<>();
                    context.put("task", "Task");
                    context.put("timeoutSec", 300);
                    context.put("maxStepCount", 5);
                    context.put("verboseMode", false);
                    context.put("classic", true);
                    context.put("step", "confirm_execution");

                    // Simulate confirmation
                    StepResult confirmResult = executeStep(args, context, "yes");

                    // Execute agent steps
                    int steps = 0;
                    while (steps < 10) {
                        StepResult stepResult = executeStep(args, context, null);
                        if (stepResult.isComplete()) {
                            return stepResult.getOutput().contains("successfully") ? 0 : 1;
                        }
                        steps++;
                    }
                    return 1;
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            }
        };

        testCommand.setCommandRegistry(mockRegistry);
        testCommand.setMockAIManager(mockAIManager);
        testCommand.setMockConfigManager(mockConfigManager);
        testCommand.setMockContextEngine(mockContextEngine);

        // Execute agent
        int exitCode = testCommand.execute(new String[] {"Task", "-m", "5"});

        // Verify: arbitrary prose (no COMMAND:/complete marker) must NOT be dispatched as a command.
        // parseAction returns null, the agent warns and re-prompts, then completes on the next turn.
        assertThat(exitCode).isEqualTo(0);
        String output = outputStream.toString();
        // The first word of the prose ("unknown_cmd") must never be executed as a command.
        verify(mockRegistry, never()).executeCommand(eq("unknown_cmd"), any());
        assertThat(output).contains("Could not parse agent action");
        assertThat(output).contains("Agent task completed:");
    }

    @Test
    public void testAgent_VerboseMode() throws Exception {
        // Create a test command that simulates verbose mode
        TestableAgentCommand testCommand = new TestableAgentCommand() {
            @Override
            public int execute(String[] args) {
                try {
                    // Setup mocks
                    when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                    when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());

                    // Mock AI responses
                    when(mockAIManager.complete(any(PromptData.class), any()))
                            .thenReturn("COMMAND: ls ARGS: -la")
                            .thenReturn("COMMAND: complete ARGS: Listed files");

                    when(mockRegistry.executeCommand(eq("ls"), any())).thenReturn(0);

                    // Simulate the agent execution flow
                    Map<String, Object> context = new HashMap<>();
                    context.put("task", "List files");
                    context.put("timeoutSec", 300);
                    context.put("maxStepCount", 10);
                    context.put("verboseMode", true);
                    context.put("classic", true);
                    context.put("step", "confirm_execution");

                    // Simulate confirmation
                    StepResult confirmResult = executeStep(args, context, "yes");

                    // Execute agent steps
                    int steps = 0;
                    while (steps < 10) {
                        StepResult stepResult = executeStep(args, context, null);
                        if (stepResult.isComplete()) {
                            return stepResult.getOutput().contains("successfully") ? 0 : 1;
                        }
                        steps++;
                    }
                    return 1;
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            }
        };

        testCommand.setCommandRegistry(mockRegistry);
        testCommand.setMockAIManager(mockAIManager);
        testCommand.setMockConfigManager(mockConfigManager);
        testCommand.setMockContextEngine(mockContextEngine);

        // Execute agent in verbose mode
        int exitCode = testCommand.execute(new String[] {"List files", "-v"});

        // Verify verbose output
        assertThat(exitCode).isEqualTo(0);
        String output = outputStream.toString();
        assertThat(output).contains("Agent reasoning...");
        // The command about to run is announced as a sub-header on every run, not only under
        // --verbose, so the shell can open a browsable section for it.
        assertSubheader(output, "ls -la");
    }

    @Test
    public void aRunThatHasSpentItsTimeBudgetStopsInsteadOfTakingAnotherStep() throws Exception {
        // Two thirds of the budget passes between one look at the clock and the next, so the run is
        // inside its budget when its first step starts and past it when the second one would.
        TestableAgentCommand testCommand = runningOn(TIME_BUDGET_MILLIS * 2 / 3);
        modelThatNeverFinishes();

        IterativeCommand.StepResult ending =
                testCommand.executeStep(TASK_ARGV, classicRunAllowed(TIME_BUDGET_SECONDS), "yes");

        assertThat(ending.isComplete()).isTrue();
        assertThat(ending.getOutput()).contains("Agent timed out");
        assertWarning(outputStream.toString(),
                      "Agent reached its time budget (" + TIME_BUDGET_SECONDS
                      + "s) without completing the task");
        verify(mockAIManager, times(1)).complete(any(PromptData.class), any());
    }

    @Test
    public void aRunThatStaysInsideItsTimeBudgetIsLeftToFinish() throws Exception {
        TestableAgentCommand testCommand = runningOn(MILLIS_PER_SECOND);
        modelThatFinishesOnItsSecondStep();

        IterativeCommand.StepResult ending =
                testCommand.executeStep(TASK_ARGV, classicRunAllowed(TIME_BUDGET_SECONDS), "yes");

        assertThat(ending.getOutput()).contains("Agent completed successfully");
        assertThat(outputStream.toString()).doesNotContain("time budget");
        verify(mockAIManager, times(2)).complete(any(PromptData.class), any());
    }

    @Test
    public void aRunNobodyGaveATimeBudgetIsNeverStoppedByTheClock() throws Exception {
        TestableAgentCommand testCommand = runningOn(A_DAY_MILLIS);
        modelThatFinishesOnItsSecondStep();

        IterativeCommand.StepResult ending =
                testCommand.executeStep(TASK_ARGV, classicRunAllowed(AgentOptions.UNLIMITED), "yes");

        assertThat(ending.getOutput()).contains("Agent completed successfully");
        assertThat(outputStream.toString()).doesNotContain("time budget");
    }

    @Test
    public void aStepDoesNotStandOnTopOfTheStepBeforeIt() throws Exception {
        // Each step used to end by calling executeStep again, so a run's stack depth was its step
        // count and a run long enough to be worth starting died of a StackOverflowError. Depth that
        // does not grow with the step number is what says the loop is a loop.
        List<Integer> depthPerStep = new ArrayList<>();
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);
        when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());
        when(mockRegistry.executeCommand(any(), any())).thenReturn(0);
        when(mockAIManager.complete(any(PromptData.class), any())).thenAnswer(asked -> {
            depthPerStep.add(new Throwable().getStackTrace().length);
            return depthPerStep.size() < STEPS_BEFORE_DONE
                   ? "COMMAND: read ARGS: file" + depthPerStep.size() + ".txt"
                   : "COMMAND: complete ARGS: done";
        });

        IterativeCommand.StepResult ending = runningOn(MILLIS_PER_SECOND)
                .executeStep(TASK_ARGV, classicRunAllowed(AgentOptions.UNLIMITED), "yes");

        assertThat(ending.getOutput()).contains("Agent completed successfully");
        assertThat(depthPerStep).hasSize(STEPS_BEFORE_DONE)
                                .as("a step that is deeper than the one before it is a step standing "
                                    + "on it, and enough of those overflow the stack")
                                .containsOnly(depthPerStep.get(0));
    }

    /**
     * An agent whose only difference from the real one is that the test owns its clock.
     *
     * @param tickMillis how much later each look at the clock is than the one before it
     */
    private TestableAgentCommand runningOn(long tickMillis) {
        AtomicLong           clock       = new AtomicLong();
        TestableAgentCommand testCommand = new TestableAgentCommand() {
            @Override
            protected long nowMillis() {
                return clock.getAndAdd(tickMillis);
            }
        };
        testCommand.setCommandRegistry(mockRegistry);
        testCommand.setMockAIManager(mockAIManager);
        testCommand.setMockConfigManager(mockConfigManager);
        testCommand.setMockContextEngine(mockContextEngine);
        return testCommand;
    }

    /**
     * A model that always asks to read a file it has not read yet, so that only a budget can end the
     * run: one that proposed the same action twice would be stopped by {@link ActionLoopGuard}
     * instead, and the test would be measuring that rather than the clock.
     */
    private void modelThatNeverFinishes() throws Exception {
        AtomicLong filesAskedFor = new AtomicLong();
        commonMocks();
        when(mockAIManager.complete(any(PromptData.class), any()))
                .thenAnswer(asked -> "COMMAND: read ARGS: file"
                                     + filesAskedFor.incrementAndGet() + ".txt");
    }

    /** A model that reads one file and then reports the task done. */
    private void modelThatFinishesOnItsSecondStep() throws Exception {
        commonMocks();
        when(mockAIManager.complete(any(PromptData.class), any()))
                .thenReturn("COMMAND: read ARGS: file1.txt")
                .thenReturn("COMMAND: complete ARGS: done");
    }

    private void commonMocks() throws Exception {
        when(mockConfigManager.getConfig()).thenReturn(mockConfig);
        when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());
        when(mockRegistry.executeCommand(any(), any())).thenReturn(0);
    }

    /** A classic run about to be confirmed, with the given time budget and no step budget. */
    private Map<String, Object> classicRunAllowed(int timeoutSeconds) {
        Map<String, Object> context = new HashMap<>();
        context.put("task", TASK_ARGV[0]);
        context.put("timeoutSec", timeoutSeconds);
        context.put("maxStepCount", AgentOptions.UNLIMITED);
        context.put("verboseMode", false);
        context.put("classic", true);
        context.put("step", "confirm_execution");
        return context;
    }

    @Test
    public void testAgent_ParsesMultiLineActionBlock() throws Exception {
        // The model follows the agent prompt's multi-line ACTION_START block format (COMMAND and
        // ARGS on separate lines). The parser must extract the ARGS line (previously dropped).
        TestableAgentCommand testCommand = new TestableAgentCommand() {
            @Override
            public int execute(String[] args) {
                try {
                    when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                    when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());
                    when(mockAIManager.complete(any(PromptData.class), any()))
                            .thenReturn("STEP 1:\nTHINKING: inspect main\nACTION_START\n"
                                    + "COMMAND: read\nARGS: src/Main.java\nREASON: inspect\nACTION_END")
                            .thenReturn("TASK COMPLETE: finished reading");
                    when(mockRegistry.executeCommand(eq("read"), any())).thenReturn(0);

                    Map<String, Object> context = new HashMap<>();
                    context.put("task", "Read main");
                    context.put("timeoutSec", 300);
                    context.put("maxStepCount", 5);
                    context.put("verboseMode", false);
                    context.put("classic", true);
                    context.put("step", "confirm_execution");
                    executeStep(args, context, "yes");
                    int steps = 0;
                    while (steps < 10) {
                        StepResult r = executeStep(args, context, null);
                        if (r.isComplete()) {
                            return 0;
                        }
                        steps++;
                    }
                    return 1;
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            }
        };
        testCommand.setCommandRegistry(mockRegistry);
        testCommand.setMockAIManager(mockAIManager);
        testCommand.setMockConfigManager(mockConfigManager);
        testCommand.setMockContextEngine(mockContextEngine);

        int exitCode = testCommand.execute(new String[] {"Read main", "-m", "5"});

        assertThat(exitCode).isEqualTo(0);
        // The multi-line ARGS line must be captured and dispatched with the read command.
        verify(mockRegistry).executeCommand(eq("read"),
                argThat((String[] a) -> a.length == 1 && a[0].equals("src/Main.java")));
        assertThat(outputStream.toString()).contains("Agent task completed:");
    }

    @Test
    public void testAgent_TaskCompleteSignalTerminates() throws Exception {
        // "TASK COMPLETE: ..." must terminate the agent without dispatching any command.
        TestableAgentCommand testCommand = new TestableAgentCommand() {
            @Override
            public int execute(String[] args) {
                try {
                    when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                    when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());
                    when(mockAIManager.complete(any(PromptData.class), any()))
                            .thenReturn("TASK COMPLETE: nothing further required");

                    Map<String, Object> context = new HashMap<>();
                    context.put("task", "Trivial");
                    context.put("timeoutSec", 300);
                    context.put("maxStepCount", 5);
                    context.put("verboseMode", false);
                    context.put("classic", true);
                    context.put("step", "confirm_execution");
                    executeStep(args, context, "yes");
                    StepResult r = executeStep(args, context, null);
                    return r.isComplete() ? 0 : 1;
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            }
        };
        testCommand.setCommandRegistry(mockRegistry);
        testCommand.setMockAIManager(mockAIManager);
        testCommand.setMockConfigManager(mockConfigManager);
        testCommand.setMockContextEngine(mockContextEngine);

        int exitCode = testCommand.execute(new String[] {"Trivial", "-m", "5"});

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputStream.toString()).contains("Agent task completed:");
        verify(mockRegistry, never()).executeCommand(any(), any());
    }

    @Test
    public void testAgent_FeedsCommandOutputIntoNextPrompt() throws Exception {
        // The agent must feed a command's captured output back into the next prompt so the model can
        // reason about results (not just exit codes).
        org.mockito.ArgumentCaptor<PromptData> captor = org.mockito.ArgumentCaptor.forClass(PromptData.class);
        TestableAgentCommand testCommand = new TestableAgentCommand() {
            @Override
            public int execute(String[] args) {
                try {
                    when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                    when(mockContextEngine.searchRelevantSnippets(anyString())).thenReturn(new ArrayList<>());
                    when(mockAIManager.complete(any(PromptData.class), any()))
                            .thenReturn("COMMAND: read ARGS: notes.txt")
                            .thenReturn("COMMAND: complete ARGS: done");
                    when(mockRegistry.executeCommand(eq("read"), any())).thenAnswer(inv -> {
                        System.out.println("UNIQUE_FILE_BODY_42");
                        return 0;
                    });

                    Map<String, Object> context = new HashMap<>();
                    context.put("task", "Read notes");
                    context.put("timeoutSec", 300);
                    context.put("maxStepCount", 5);
                    context.put("verboseMode", false);
                    context.put("classic", true);
                    context.put("step", "confirm_execution");
                    executeStep(args, context, "yes");
                    int steps = 0;
                    while (steps < 10) {
                        StepResult r = executeStep(args, context, null);
                        if (r.isComplete()) {
                            return 0;
                        }
                        steps++;
                    }
                    return 1;
                } catch (Exception e) {
                    e.printStackTrace();
                    return 1;
                }
            }
        };
        testCommand.setCommandRegistry(mockRegistry);
        testCommand.setMockAIManager(mockAIManager);
        testCommand.setMockConfigManager(mockConfigManager);
        testCommand.setMockContextEngine(mockContextEngine);

        int exitCode = testCommand.execute(new String[] {"Read notes", "-m", "5"});

        assertThat(exitCode).isEqualTo(0);
        verify(mockAIManager, times(2)).complete(captor.capture(), any());
        // The second prompt (issued after the read) must include the captured command output.
        assertThat(captor.getAllValues().get(1).getUserPrompt()).contains("UNIQUE_FILE_BODY_42");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testExecute_NestedAgentRefusedByDepthGuard() throws Exception {
        // Simulate already running inside an agent on this thread: a second agent must be refused
        // (this closes the indirect agent -> unknown verb -> chat-fallback -> agent recursion path).
        java.lang.reflect.Field depthField = AgentCommand.class.getDeclaredField("AGENT_DEPTH");
        depthField.setAccessible(true);
        ThreadLocal<Integer> depth = (ThreadLocal<Integer>) depthField.get(null);
        depth.set(1);
        try {
            int result = new AgentCommand().execute(new String[] {"do something nested"});
            assertThat(result).isEqualTo(1);
            assertThat(outputStream.toString() + errorStream.toString()).contains("nested agent");
        } finally {
            depth.set(0);
        }
    }
}