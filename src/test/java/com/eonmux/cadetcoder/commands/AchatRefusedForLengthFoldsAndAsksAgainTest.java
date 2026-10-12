package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.metrics.TokenCalibration;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.net.LLMErrorMapper;
import com.eonmux.cadetcoder.test.StubbedProvider;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chat run whose prompt the provider refuses as longer than the model's context window.
 *
 * <h2>The defect</h2>
 *
 * <p>The transcript was folded only when the estimate passed the trigger, and the estimate took
 * four characters per token. A Qwen model on vLLM counted more, so its prompt passed the window
 * first: the provider refused the request, and the refusal ended the run.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the refusal folds the transcript on the provider's count and the turn is asked again, and
 * that a refusal with nothing to fold still ends the run after one request.</p>
 */
public class AchatRefusedForLengthFoldsAndAsksAgainTest {

    /** Longer than this, in characters, and the stub refuses the prompt. */
    private static final int REFUSED_ABOVE = 40_000;

    private static final int WINDOW = 1_000_000;

    private TestOutputCapture      output;
    private Configuration.AiConfig ai;
    private int                    originalContextTokens;

    @Before
    public void setUp() {
        System.setProperty("cadet.interactive", "false");
        TokenCalibration.clear();
        ai                    = ConfigManager.getInstance().getConfig().getAi();
        originalContextTokens = ai.getContextTokens();
        // A window the estimate never comes near, so only the provider's refusal can fold.
        ai.setContextTokens(WINDOW);
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        ai.setContextTokens(originalContextTokens);
        TokenCalibration.clear();
        System.clearProperty("cadet.interactive");
    }

    /** Refuses a long prompt the way the user's vLLM server did, with its count at the window. */
    private static StubbedProvider refusingLongPrompts() {
        AtomicInteger turn = new AtomicInteger();
        return StubbedProvider.answeringWith(request -> {
            if (request.getUserPrompt().length() > REFUSED_ABOVE) {
                throw LLMErrorMapper.fromStatus("local", "qwen", null, 400,
                        "{\"error\":{\"message\":\"This model's maximum context length is "
                        + WINDOW + " tokens. However, you requested 0 output tokens and your"
                        + " prompt contains at least " + (WINDOW + 1) + " input tokens.\"}}",
                        null);
            }
            return "Looked at part " + turn.incrementAndGet() + ".";
        });
    }

    /** A command whose every step reports a long output, for {@code steps} steps. */
    private static IterativeCommand longSteps(String[] args, int steps) {
        IterativeCommand command = Mockito.mock(IterativeCommand.class);
        Mockito.when(command.supportsIterativeExecution(args)).thenReturn(true);
        Mockito.when(command.getInitialPrompt(args)).thenReturn("Read the log");
        AtomicInteger step = new AtomicInteger();
        Mockito.when(command.executeStep(Mockito.any(), Mockito.anyMap(), Mockito.anyString()))
               .thenAnswer(call -> step.incrementAndGet() > steps
                       ? IterativeCommand.StepResult.success("done", new HashMap<>())
                       : new IterativeCommand.StepResult(false,
                               "part " + step.get() + "\n" + "log line\n".repeat(700),
                               new HashMap<>(), "What next?"));
        return command;
    }

    @Test
    public void theTranscriptIsFoldedOnTheProvidersCountAndTheTurnAskedAgain() {
        String[] args = {"read"};
        try (StubbedProvider provider = refusingLongPrompts()) {
            int exit = new IterativeExecutor().execute(longSteps(args, 12), args);

            List<String> prompts = provider.asked().stream()
                                           .map(PromptData::getUserPrompt).toList();
            int refused = indexOfFirstRefused(prompts);
            assertThat(refused).as("the run grew past what the provider takes").isPositive();
            assertThat(prompts.get(refused + 1))
                    .as("asked again with the transcript folded")
                    .contains("omitted to stay within the model's context window")
                    .hasSizeLessThan(prompts.get(refused).length());
            assertThat(exit).as("the run went on to finish").isZero();
        }
        assertThat(output.getAllOutput())
                .contains("The prompt was too long for the model. Context compacted:");
    }

    /** Nothing is left to fold, so the refusal stands and the run ends as before. */
    @Test
    public void aRefusalWithNothingToFoldEndsTheRunAfterOneRequest() {
        String[] args = {"read"};
        IterativeCommand command = Mockito.mock(IterativeCommand.class);
        Mockito.when(command.supportsIterativeExecution(args)).thenReturn(true);
        Mockito.when(command.getInitialPrompt(args)).thenReturn("x".repeat(REFUSED_ABOVE + 1));

        try (StubbedProvider provider = refusingLongPrompts()) {
            int exit = new IterativeExecutor().execute(command, args);

            assertThat(provider.asked()).hasSize(1);
            assertThat(exit).isNotZero();
        }
        assertThat(output.getAllOutput()).contains("longer than the model's context window");
    }

    private static int indexOfFirstRefused(List<String> prompts) {
        for (int i = 0; i < prompts.size(); i++) {
            if (prompts.get(i).length() > REFUSED_ABOVE) {
                return i;
            }
        }
        return -1;
    }
}
