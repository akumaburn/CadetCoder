package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class for IterativeExecutor
 */
public class IterativeExecutorTest {

    private TestOutputCapture outputCapture;
    private IterativeExecutor executor;
    private IterativeCommand  mockCommand;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        executor      = new IterativeExecutor();
        mockCommand   = mock(IterativeCommand.class);
    }

    @After
    public void tearDown() {
        outputCapture.restore();
    }

    @Test
    public void testExecute_NonIterativeCommand() {
        String[] args = {"test", "args"};
        when(mockCommand.supportsIterativeExecution(args)).thenReturn(false);
        when(mockCommand.execute(args)).thenReturn(0);

        int result = executor.execute(mockCommand, args);

        assertThat(result).isEqualTo(0);
        verify(mockCommand).execute(args);
        verify(mockCommand, never()).executeStep(any(), any(), any());
    }

    @Test
    public void testExecute_NoInitialPrompt_NonInteractive() {
        String[] args = {"test"};

        // Set non-interactive mode
        System.setProperty("cadet.interactive", "false");

        try {
            // Setup command behavior
            when(mockCommand.supportsIterativeExecution(args)).thenReturn(true);
            when(mockCommand.getInitialPrompt(args)).thenReturn(null);

            // Step completes immediately
            IterativeCommand.StepResult step = new IterativeCommand.StepResult(
                    true, "Completed", new HashMap<>(), null
            );

            when(mockCommand.executeStep(eq(args), any(Map.class), isNull())).thenReturn(step);

            int result = executor.execute(mockCommand, args);

            assertThat(result).isEqualTo(0);
            verify(mockCommand).executeStep(eq(args), any(Map.class), isNull());

            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Completed");
        } finally {
            System.clearProperty("cadet.interactive");
        }
    }

    @Test
    public void testExecute_NoInitialPrompt_Interactive() {
        String[] args = {"test"};

        // Set interactive mode
        System.setProperty("cadet.interactive", "true");

        try {
            // Setup command behavior
            when(mockCommand.supportsIterativeExecution(args)).thenReturn(true);
            when(mockCommand.getInitialPrompt(args)).thenReturn("");

            // Step completes immediately
            IterativeCommand.StepResult step = new IterativeCommand.StepResult(
                    true, "Interactive completed", new HashMap<>(), null
            );

            when(mockCommand.executeStep(eq(args), any(Map.class), isNull())).thenReturn(step);

            int result = executor.execute(mockCommand, args);

            assertThat(result).isEqualTo(0);
            verify(mockCommand).executeStep(eq(args), any(Map.class), isNull());

            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Interactive completed");
        } finally {
            System.clearProperty("cadet.interactive");
        }
    }

    @Test
    public void testExecute_ErrorInNonInteractiveMode() {
        String[] args = {"test"};

        System.setProperty("cadet.interactive", "false");

        try {
            when(mockCommand.supportsIterativeExecution(args)).thenReturn(true);
            when(mockCommand.getInitialPrompt(args)).thenReturn(null);

            // Step returns an EXPLICIT failure (authoritative error flag). The executor must map this
            // to exit code 1. Note: error state is now driven by the explicit flag, not by scanning the
            // output text for the word "Error".
            IterativeCommand.StepResult errorStep =
                    IterativeCommand.StepResult.failure("Error: File not found", new HashMap<>());

            when(mockCommand.executeStep(eq(args), any(Map.class), any())).thenReturn(errorStep);

            int result = executor.execute(mockCommand, args);

            assertThat(result).isEqualTo(1);

            String output = outputCapture.getAllOutput();
            assertThat(output).contains("Error: File not found");
        } finally {
            System.clearProperty("cadet.interactive");
        }
    }

    @Test
    public void testExecute_MaxIterationsInNonInteractiveMode() {
        String[] args = {"test"};

        System.setProperty("cadet.interactive", "false");
        // Pin the cap to a small, explicit value so the test exercises the ceiling deterministically and
        // fast, independent of the (configurable) default. The executor reads the cap at construction.
        System.setProperty("cadet.iterative.maxIterations", "3");

        try {
            IterativeExecutor cappedExecutor = new IterativeExecutor();

            when(mockCommand.supportsIterativeExecution(args)).thenReturn(true);
            when(mockCommand.getInitialPrompt(args)).thenReturn(null);

            // Always return incomplete step
            IterativeCommand.StepResult incompleteStep = new IterativeCommand.StepResult(
                    false, "Still working", new HashMap<>(), "Continue?"
            );

            when(mockCommand.executeStep(any(), any(), any())).thenReturn(incompleteStep);

            int result = cappedExecutor.execute(mockCommand, args);

            assertThat(result).isEqualTo(1);
            // The ceiling is honored exactly: the step is attempted maxIterations times, then the loop ends.
            verify(mockCommand, times(3)).executeStep(any(), any(), any());
        } finally {
            System.clearProperty("cadet.interactive");
            System.clearProperty("cadet.iterative.maxIterations");
        }
    }

    @Test
    public void testExecute_ExceptionHandling() {
        String[] args = {"test"};

        when(mockCommand.supportsIterativeExecution(args)).thenReturn(true);
        when(mockCommand.getInitialPrompt(args)).thenThrow(new RuntimeException("Test exception"));

        int result = executor.execute(mockCommand, args);

        assertThat(result).isEqualTo(1);

        String output = outputCapture.getAllOutput();
        assertThat(output)
                .as("a failure line that names the class driving the run tells the reader nothing "
                    + "they can act on")
                .contains("The command could not finish: Test exception")
                .doesNotContain("Iterative execution");
    }

    @Test
    public void testExecutor_Construction() {
        IterativeExecutor testExecutor = new IterativeExecutor();
        assertThat(testExecutor).isNotNull();
    }

    /**
     * Finding 21: benign command output that merely mentions failure-like words ("Error", "not found")
     * must NOT be misclassified as a failure when no explicit error flag is set. Only a structured
     * leading "ERROR:" sentinel (or the explicit flag) marks an error.
     */
    @Test
    public void testStepResult_benignOutputWithErrorWordIsNotAnError() {
        IterativeCommand.StepResult benign = new IterativeCommand.StepResult(
                false, "grep result: the file mentions Error handling and a not found case",
                new HashMap<>(), "next?");
        assertThat(benign.isError()).isFalse();
        assertThat(benign.hasExplicitErrorState()).isFalse();
    }

    /** Finding 21: a structured leading ERROR: sentinel is still treated as a failure. */
    @Test
    public void testStepResult_errorSentinelIsAnError() {
        IterativeCommand.StepResult sentinel = new IterativeCommand.StepResult(
                false, "ERROR: the action could not be completed", new HashMap<>(), "next?");
        assertThat(sentinel.isError()).isTrue();
    }

    /** Finding 21: an explicit failure flag remains authoritative regardless of output text. */
    @Test
    public void testStepResult_explicitFailureIsAuthoritative() {
        IterativeCommand.StepResult explicit =
                IterativeCommand.StepResult.failure("all good in the prose", new HashMap<>());
        assertThat(explicit.isError()).isTrue();
        assertThat(explicit.hasExplicitErrorState()).isTrue();
    }

    /**
     * Finding 6: a yes/no confirmation prompt must be recognized as direct-user-input so it is never
     * routed to the LLM to answer (which would let the model approve its own privileged action).
     */
    @Test
    public void testRequiresUserInput_confirmationPromptIsUserInput() {
        // A step that does not declare its audience falls back to reading the prompt, which is what
        // the commands asking these questions still rely on.
        assertThat(new UserAsk(true).requiresUserInput(new IterativeCommand.StepResult(
                false, "", new HashMap<>(), "Do you want to continue? (yes/no)")))
                .as("(yes/no) confirmation must require direct user input, not an LLM answer")
                .isTrue();
        assertThat(new UserAsk(true).requiresUserInput(new IterativeCommand.StepResult(
                false, "", new HashMap<>(), "Proceed? (y/n)"))).isTrue();
    }

    /**
     * Finding 6: in non-interactive mode the default answer to a confirmation/permission prompt must
     * be a SAFE DENIAL ("no"), never an auto-"yes" that bypasses the confirmation gate.
     */
    @Test
    public void testGetDefaultUserInput_confirmationDefaultsToDenial() throws Exception {
        UserAsk ask = new UserAsk(false);
        assertThat(ask.getDefaultUserInput("Do you want to continue? (yes/no)", ""))
                .as("non-interactive confirmation must default to a safe denial, not auto-yes")
                .isEqualTo("no");
        // A bare yes/no question (no marker) is also denied rather than auto-approved.
        assertThat(ask.getDefaultUserInput("Should we proceed: yes or no?", ""))
                .isEqualTo("no");
    }

    private int readMaxIterations(IterativeExecutor exec) throws Exception {
        java.lang.reflect.Field f = IterativeExecutor.class.getDeclaredField("maxIterations");
        f.setAccessible(true);
        return (int) f.get(exec);
    }

    /**
     * Iterations are UNBOUNDED by default.
     *
     * <p>A fixed ceiling cut off long tasks that were making steady progress while doing nothing for a
     * run stuck on step three. What ends a stuck run now is {@link ActionLoopGuard}: it refuses
     * repetition that cannot produce new information and stops the run after several such refusals in
     * a row.</p>
     */
    @Test
    public void testMaxIterations_isUnlimitedByDefault() throws Exception {
        System.clearProperty("cadet.iterative.maxIterations");
        assertThat(readMaxIterations(new IterativeExecutor())).isEqualTo(Integer.MAX_VALUE);
    }

    /** An explicit ceiling can still be imposed for callers that want one. */
    @Test
    public void testMaxIterations_honorsSystemProperty() throws Exception {
        System.setProperty("cadet.iterative.maxIterations", "40");
        try {
            assertThat(readMaxIterations(new IterativeExecutor())).isEqualTo(40);
        } finally {
            System.clearProperty("cadet.iterative.maxIterations");
        }
    }

    /** A very large explicit ceiling is honored verbatim; there is no upper clamp any more. */
    @Test
    public void testMaxIterations_largeExplicitValueIsHonored() throws Exception {
        System.setProperty("cadet.iterative.maxIterations", "99999");
        try {
            assertThat(readMaxIterations(new IterativeExecutor())).isEqualTo(99999);
        } finally {
            System.clearProperty("cadet.iterative.maxIterations");
        }
    }

    /** Zero, a negative value and an unparseable value all mean "unlimited". */
    @Test
    public void testMaxIterations_zeroNegativeAndGarbageMeanUnlimited() throws Exception {
        for (String value : new String[] {"0", "-1", "not-a-number"}) {
            System.setProperty("cadet.iterative.maxIterations", value);
            try {
                assertThat(readMaxIterations(new IterativeExecutor()))
                        .as("'%s' must mean unlimited", value)
                        .isEqualTo(Integer.MAX_VALUE);
            } finally {
                System.clearProperty("cadet.iterative.maxIterations");
            }
        }
    }

    /**
     * Fix C: the follow-up system-prompt hook defaults to null, so a command that does not override it
     * keeps the executor's generic follow-up prompt. Commands that need to hold the model to a specific
     * contract on every turn (e.g. ChatCommand) override it; the default must remain opt-in.
     */
    @Test
    public void testGetIterativeSystemPrompt_defaultsToNull() {
        assertThat(new TestIterativeCommand().getIterativeSystemPrompt())
                .as("commands that do not override keep the executor's generic follow-up prompt")
                .isNull();
    }

    // Helper class for testing
    public static class TestIterativeCommand implements IterativeCommand {
        @Override
        public StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse) {
            return new StepResult(true, "Done", context, null);
        }

        @Override
        public String getInitialPrompt(String[] args) {
            return "Test initial prompt";
        }

        @Override
        public boolean supportsIterativeExecution(String[] args) {
            return true;
        }

        @Override
        public int execute(String[] args) {
            return 0;
        }

        @Override
        public String getDescription() {
            return "Test command";
        }

        @Override
        public String getUsage() {
            return "test [args]";
        }
    }
}