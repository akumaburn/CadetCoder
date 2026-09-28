package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.ai.AIClient;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.net.LLMAuthException;
import com.eonmux.cadetcoder.net.LLMTransportException;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the reported defect: a chat/iterative run whose provider call fails
 * completely used to print "Command completed successfully" and exit 0, because the backend's error
 * text arrived as if it were the model's answer. A failed AI call must now end the run with a
 * non-zero exit code and an actionable message.
 *
 * <p>The code is {@link ExitCode#UNREACHABLE} rather than a plain failure, because nothing was
 * attempted: the request never arrived. {@code loop} reads that to tell a pass that failed from a
 * pass that never started.</p>
 */
public class IterativeExecutorAiFailureTest {

    private TestOutputCapture outputCapture;
    private IterativeCommand  mockCommand;
    private Object            previousAiManagerInstance;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        mockCommand   = mock(IterativeCommand.class);
        System.setProperty("cadet.interactive", "false");
    }

    @After
    public void tearDown() throws Exception {
        restoreAiManager();
        System.clearProperty("cadet.interactive");
        outputCapture.restore();
    }

    @Test
    public void authFailureFromTheProviderEndsTheRunWithANonZeroExitCode() throws Exception {
        installFailingAiClient(new LLMAuthException("opencode-go", "deepseek-v4-flash",
                                                    "https://example.invalid/v1", 403,
                                                    "{\"type\":\"error\"}"));
        String[] args = {"frobnicate"};
        when(mockCommand.supportsIterativeExecution(args)).thenReturn(true);
        when(mockCommand.getInitialPrompt(args)).thenReturn("Frobnicate the widget");

        int exitCode = new IterativeExecutor().execute(mockCommand, args);

        assertThat(exitCode).isEqualTo(ExitCode.UNREACHABLE);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("AI request failed")
                          .contains("HTTP 403")
                          .contains("The key was accepted");
        assertThat(output).doesNotContain("Command completed successfully");
    }

    @Test
    public void transportFailureReportsTheEndpointItCouldNotReach() throws Exception {
        installFailingAiClient(new LLMTransportException("opencode-go", "deepseek-v4-flash",
                                                         "http://127.0.0.1:1/v1",
                                                         new java.net.ConnectException("Connection refused")));
        String[] args = {"frobnicate"};
        when(mockCommand.supportsIterativeExecution(args)).thenReturn(true);
        when(mockCommand.getInitialPrompt(args)).thenReturn("Frobnicate the widget");

        int exitCode = new IterativeExecutor().execute(mockCommand, args);

        assertThat(exitCode).isEqualTo(ExitCode.UNREACHABLE);
        assertThat(outputCapture.getAllOutput()).contains("Cannot reach http://127.0.0.1:1/v1")
                                                .doesNotContain("Command completed successfully");
    }

    /**
     * Points the {@link AIManager} singleton at a client that always fails, without going through
     * the package-private test factory (which is not reachable from this package).
     */
    private void installFailingAiClient(RuntimeException failure) throws Exception {
        Field instanceField = AIManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        previousAiManagerInstance = instanceField.get(null);

        Constructor<AIManager> constructor = AIManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        AIManager manager = constructor.newInstance();

        setField(manager, "activeClient", new FailingAIClient(failure));
        setField(manager, "initialized", Boolean.TRUE);
        instanceField.set(null, manager);
    }

    private void restoreAiManager() throws Exception {
        Field instanceField = AIManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, previousAiManagerInstance);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = AIManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** AI client whose every completion attempt fails the way a dead/forbidden provider does. */
    private static final class FailingAIClient implements AIClient {

        private final RuntimeException failure;

        private FailingAIClient(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public String complete(PromptData promptData, Map<String, Object> parameters) {
            throw failure;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getModelName() {
            return "opencode-go/deepseek-v4-flash";
        }
    }
}
